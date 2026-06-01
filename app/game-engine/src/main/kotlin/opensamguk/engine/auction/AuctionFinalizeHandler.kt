package opensamguk.engine.auction

import opensamguk.common.wire.AuctionFinalizeFail
import opensamguk.common.wire.AuctionFinalizeOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.logic.auction.AuctionResultCalculator
import opensamguk.logic.auction.AuctionStatus
import opensamguk.logic.auction.AuctionType
import opensamguk.logic.auction.FinishResult
import opensamguk.logic.auction.RollbackResult

/**
 * 경매 마감 핸들러 — [TurnDaemonCommand.AuctionFinalize] 명령 처리.
 *
 * PHP `AuctionFinalizer::finalize()`의 Kotlin 포팅. 마감 시의 전체 흐름:
 * 1. 경매 조회 (FINALIZING 상태 확인)
 * 2. 최고 입찰자 조회 (isReverse에 따라 정렬)
 * 3. 입찰 없음 → rollback ([AuctionResultCalculator.calculateRollback])
 * 4. 입찰 있음 → finish ([AuctionResultCalculator.calculateFinish])
 *    - BUY/SELL_RICE: host↔bidder 자원 교환
 *    - UNIQUE_ITEM: 보유 제한 확인 → 제한 시 연장, 아니면 아이템 지급
 * 5. 로그 작성
 * 6. status = FINISHED/CANCELED 업데이트
 * 7. 결과 반환 ([AuctionFinalizeOk] / [AuctionFinalizeFail])
 *
 * @param world 인메모리 턴 월드 — 장수 자원 조작용
 *
 * [AuctionBidHandler]와 동일하게 per-run plain 클래스다([InMemoryTurnWorld]는 싱글톤 빈이 아닌
 * 스냅샷 기반 per-run 상태). Spring `@Component` 미등록, 턴 파이프라인이 직접 인스턴스화한다.
 */
class AuctionFinalizeHandler(
    private val world: InMemoryTurnWorld,
) : TurnDaemonCommandHandler<TurnDaemonCommand.AuctionFinalize> {

    override suspend fun handle(command: TurnDaemonCommand.AuctionFinalize): TurnDaemonCommandResult {
        val auctionId = command.auctionId

        // ── 1. 경매 조회 (FINALIZING 상태 확인) ────────────────────────────────
        // TODO: AuctionRepository를 통해 경매 조회
        // val auction = auctionRepository.findById(auctionId)
        //   ?: return AuctionFinalizeFail(auctionId = auctionId, reason = "경매가 존재하지 않습니다.")
        // if (auction.status != AuctionStatus.FINALIZING) {
        //     return AuctionFinalizeFail(auctionId = auctionId, reason = "FINALIZING 상태가 아닙니다.")
        // }

        // 스텁 데이터 — TODO: repository에서 조회한 실제 데이터로 대체
        val auctionType = AuctionType.BUY_RICE // TODO: from auction.type
        val hostGeneralId = 0 // TODO: from auction.hostGeneralId
        val detail = opensamguk.logic.auction.AuctionDetail() // TODO: from auction.detail
        val isReverse = false // TODO: from auction.detail.isReverse
        val uniqueItemKey: String? = null // TODO: from auction.targetCode

        // ── 2. 최고 입찰자 조회 (isReverse에 따라 정렬 방향 결정) ─────────────
        // TODO: AuctionBidRepository.findHighestBid(auctionId, isReverse)
        // val highestBid = bidRepository.findHighestBid(auctionId, isReverse)
        // SQL: ORDER BY amount ${isReverse ? "ASC" : "DESC"}, id ASC LIMIT 1
        val highestBidAmount: Int? = null // TODO: from repository
        val highestBidGeneralId: Int? = null // TODO: from repository

        // ── 3. 입찰 없음 → rollback ─────────────────────────────────────────
        if (highestBidAmount == null || highestBidGeneralId == null) {
            return handleRollback(auctionId, auctionType, detail, hostGeneralId)
        }

        // ── 4. 입찰 있음 → finish ──────────────────────────────────────────
        return handleFinish(
            auctionId = auctionId,
            auctionType = auctionType,
            highestBidAmount = highestBidAmount,
            highestBidGeneralId = highestBidGeneralId,
            detail = detail,
            uniqueItemKey = uniqueItemKey,
            hostGeneralId = hostGeneralId,
        )
    }

    /**
     * 유찰(rollback) 처리 — 입찰이 없을 때 주최자에게 자원 반환.
     */
    private fun handleRollback(
        auctionId: Int,
        auctionType: AuctionType,
        detail: opensamguk.logic.auction.AuctionDetail,
        hostGeneralId: Int,
    ): TurnDaemonCommandResult {
        val result = AuctionResultCalculator.calculateRollback(auctionType, detail, hostGeneralId)

        // 주최자에게 자원 반환
        if (hostGeneralId > 0 && result.returnResourceType != null && result.returnAmount > 0) {
            val host = world.getGeneralById(hostGeneralId)
            if (host != null) {
                val updated = when (result.returnResourceType) {
                    "gold" -> host.copy(gold = host.gold + result.returnAmount)
                    "rice" -> host.copy(rice = host.rice + result.returnAmount)
                    else -> host
                }
                world.updateGeneral(updated)
            }
        }

        // 로그
        world.pushLog(
            LogEntryDraft(
                scope = "action",
                category = "auction",
                text = result.logMessage,
            )
        )

        // TODO: AuctionRepository.updateStatus(auctionId, result.finalStatus)

        return AuctionFinalizeOk(
            auctionId = auctionId,
        )
    }

    /**
     * 거래 성사(finish) 처리 — 낙찰자와 주최자 간 자원 교환.
     */
    private fun handleFinish(
        auctionId: Int,
        auctionType: AuctionType,
        highestBidAmount: Int,
        highestBidGeneralId: Int,
        detail: opensamguk.logic.auction.AuctionDetail,
        uniqueItemKey: String?,
        hostGeneralId: Int,
    ): TurnDaemonCommandResult {
        // 유니크 아이템의 경우 보유 제한 확인
        if (auctionType == AuctionType.UNIQUE_ITEM) {
            val bidder = world.getGeneralById(highestBidGeneralId)
            if (bidder != null && uniqueItemKey != null) {
                // 동일 슬롯 유니크 보유 여부 확인
                val slot = detectItemSlot(uniqueItemKey)
                val currentItem = when (slot) {
                    "weapon" -> bidder.role.items.weapon
                    "book" -> bidder.role.items.book
                    "horse" -> bidder.role.items.horse
                    "item" -> bidder.role.items.item
                    else -> null
                }
                if (currentItem != null && currentItem != "None") {
                    // 보유 제한 → 경매 연장 (status를 OPEN으로 되돌림)
                    // TODO: AuctionRepository.updateStatus(auctionId, AuctionStatus.OPEN)
                    // TODO: extendCloseDateAndReopen(auction)
                    world.pushLog(
                        LogEntryDraft(
                            scope = "action",
                            category = "auction",
                            text = "유니크 아이템 보유 제한으로 경매 #$auctionId 를 연장합니다.",
                            generalId = highestBidGeneralId,
                        )
                    )
                    return AuctionFinalizeOk(auctionId = auctionId)
                }

                // 아이템 지급
                val updatedItems = when (slot) {
                    "weapon" -> bidder.role.items.copy(weapon = uniqueItemKey)
                    "book" -> bidder.role.items.copy(book = uniqueItemKey)
                    "horse" -> bidder.role.items.copy(horse = uniqueItemKey)
                    "item" -> bidder.role.items.copy(item = uniqueItemKey)
                    else -> bidder.role.items
                }
                val updatedBidder = bidder.copy(
                    role = bidder.role.copy(items = updatedItems),
                )
                world.updateGeneral(updatedBidder)
            }
        }

        // 자원 교환 결과 계산
        val result = AuctionResultCalculator.calculateFinish(auctionType, highestBidAmount, detail)

        // 주최자에게 입찰자의 자원 이전 (hostGeneralId > 0인 경우만)
        if (hostGeneralId > 0 && result.hostReceiveResource != null && result.hostReceiveAmount > 0) {
            val host = world.getGeneralById(hostGeneralId)
            if (host != null) {
                val updated = when (result.hostReceiveResource) {
                    "gold" -> host.copy(gold = host.gold + result.hostReceiveAmount)
                    "rice" -> host.copy(rice = host.rice + result.hostReceiveAmount)
                    else -> host
                }
                world.updateGeneral(updated)
            }
        }

        // 낙찰자에게 경매 물품 이전
        if (result.bidderReceiveResource != null && result.bidderReceiveAmount > 0) {
            val bidder = world.getGeneralById(highestBidGeneralId)
            if (bidder != null) {
                val updated = when (result.bidderReceiveResource) {
                    "gold" -> bidder.copy(gold = bidder.gold + result.bidderReceiveAmount)
                    "rice" -> bidder.copy(rice = bidder.rice + result.bidderReceiveAmount)
                    else -> bidder
                }
                world.updateGeneral(updated)
            }
        }

        // 유니크 아이템의 경우 유산 포인트 차감 로그
        if (auctionType == AuctionType.UNIQUE_ITEM && result.bidderInheritancePointDelta < 0) {
            world.pushLog(
                LogEntryDraft(
                    scope = "action",
                    category = "auction",
                    text = "유니크 아이템 경매로 ${-result.bidderInheritancePointDelta} 포인트를 사용했습니다.",
                    generalId = highestBidGeneralId,
                )
            )
        }

        // 일반 로그
        world.pushLog(
            LogEntryDraft(
                scope = "action",
                category = "auction",
                text = result.logMessage,
                generalId = highestBidGeneralId,
            )
        )

        // TODO: AuctionRepository.updateStatus(auctionId, result.finalStatus)

        return AuctionFinalizeOk(auctionId = auctionId)
    }

    /**
     * 아이템 키에서 슬롯(weapon/book/horse/item)을 추출한다.
     */
    private fun detectItemSlot(itemKey: String): String {
        return when {
            itemKey.startsWith("che_무기_") -> "weapon"
            itemKey.startsWith("che_서적_") -> "book"
            itemKey.startsWith("che_명마_") -> "horse"
            else -> "item"
        }
    }
}

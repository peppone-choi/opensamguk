package opensamguk.engine.auction

import opensamguk.common.wire.AuctionFinalizeFail
import opensamguk.common.wire.AuctionFinalizeOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import opensamguk.logic.auction.AuctionBidItemData
import opensamguk.logic.auction.AuctionDetail
import opensamguk.logic.auction.AuctionInfo
import opensamguk.logic.auction.AuctionInfoDetail
import opensamguk.logic.auction.AuctionResultCalculator
import opensamguk.logic.auction.AuctionStatus
import opensamguk.logic.auction.AuctionType

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
 * @param recorder 변경 녹음기 — auction flush 채널의 단일 dirty source
 * @param auctionRepository JPA read repository — 경매 조회
 * @param bidRepository JPA read repository — 입찰 조회
 *
 * [AuctionBidHandler]와 동일하게 per-run plain 클래스다([InMemoryTurnWorld]는 싱글톤 빈이 아닌
 * 스냅샷 기반 per-run 상태). Spring `@Component` 미등록, 턴 파이프라인이 직접 인스턴스화한다.
 */
class AuctionFinalizeHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val auctionRepository: AuctionRepository,
    private val bidRepository: AuctionBidRepository,
) : TurnDaemonCommandHandler<TurnDaemonCommand.AuctionFinalize> {

    override fun handle(command: TurnDaemonCommand.AuctionFinalize): TurnDaemonCommandResult {
        val auctionId = command.auctionId

        // ── 1. 경매 조회 (FINALIZING 상태 확인) ────────────────────────────────
        val auctionEntity = auctionRepository.findById(auctionId).orElse(null)
            ?: return AuctionFinalizeFail(auctionId = auctionId, reason = "경매가 존재하지 않습니다.")
        if (auctionEntity.finished) {
            return AuctionFinalizeFail(auctionId = auctionId, reason = "이미 마감된 경매입니다.")
        }

        val auction = toAuctionInfo(auctionEntity)
        val auctionType = auction.type
        val hostGeneralId = auction.hostGeneralId
        val detail = auction.detail
        val isReverse = detail.isReverse ?: false
        val uniqueItemKey = auction.target

        // ── 2. 최고 입찰자 조회 (isReverse에 따라 정렬 방향 결정) ─────────────
        val allBids = bidRepository.findByAuctionIdOrderByAmountDesc(auctionId)
            .map { toAuctionBidItem(it) }

        val highestBid = selectHighestBid(allBids, isReverse)
        val highestBidAmount = highestBid?.amount
        val highestBidGeneralId = highestBid?.generalId

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
        detail: AuctionInfoDetail,
        hostGeneralId: Int,
    ): TurnDaemonCommandResult {
        val result = AuctionResultCalculator.calculateRollback(auctionType, toAuctionDetail(detail), hostGeneralId)

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

        // 로그 없음 — 위조 로그(scope/category가 PG enum에 없어 flush 턴동결)는 바퀴 23에서 제거.
        // PHP 실로그(pushGeneralActionLog/pushAuctionLog)는 LEDGER 백로그(골든 캡처 동반).

        // 경매 상태 업데이트 (CANCELED)
        recordAuctionStatusUpdate(auctionId, result.finalStatus)

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
        detail: AuctionInfoDetail,
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
                    // 보유 제한 → 경매 연장 (status를 OPEN으로 되돌림). 위조 로그는 바퀴 23 제거.
                    recordAuctionStatusUpdate(auctionId, AuctionStatus.OPEN)
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
        val result = AuctionResultCalculator.calculateFinish(auctionType, highestBidAmount, toAuctionDetail(detail))

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

        // 로그 없음 — 위조 로그 2종(유산 차감/일반)은 바퀴 23 제거. PHP 실로그는
        // AuctionUniqueItem.php:345(습득)/:351(UserLogger inheritPoint)/AuctionBasicResource.php:197-203
        // — 골든 캡처 동반 byte-port가 LEDGER 백로그.

        // 경매 상태 업데이트 (FINISHED)
        recordAuctionStatusUpdate(auctionId, result.finalStatus)

        return AuctionFinalizeOk(auctionId = auctionId)
    }

    /**
     * 경매 상태를 업데이트하고 ChangeRecorder에 기록한다.
     */
    private fun recordAuctionStatusUpdate(auctionId: Int, status: AuctionStatus) {
        val entity = auctionRepository.findById(auctionId).orElse(null) ?: return
        val auction = toAuctionInfo(entity)
        val finished = status == AuctionStatus.FINISHED || status == AuctionStatus.CANCELED
        val updatedAuction = auction.copy(
            finished = finished,
        )
        recorder.recordAuctionUpsert(
            id = auctionId,
            columns = updatedAuction.toArray(withoutId = true),
        )
    }

    /**
     * isReverse에 따라 최고/최저 입찰을 선택한다.
     */
    private fun selectHighestBid(bids: List<opensamguk.logic.auction.AuctionBidItem>, isReverse: Boolean): opensamguk.logic.auction.AuctionBidItem? {
        if (bids.isEmpty()) return null
        return if (isReverse) bids.minByOrNull { it.amount } else bids.maxByOrNull { it.amount }
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

    /**
     * JPA [opensamguk.infra.entity.AuctionEntity] → logic [AuctionInfo].
     */
    private fun toAuctionInfo(entity: opensamguk.infra.entity.AuctionEntity): AuctionInfo {
        val detail = AuctionInfoDetail.fromArray(
            opensamguk.logic.util.jsonDecode(entity.detail)
        )
        return AuctionInfo(
            id = entity.id,
            type = entity.type,
            finished = entity.finished,
            target = entity.target,
            hostGeneralId = entity.hostGeneralId,
            reqResource = entity.reqResource,
            openDate = entity.openDate.toString(),
            closeDate = entity.closeDate.toString(),
            detail = detail,
        )
    }

    /**
     * JPA [opensamguk.infra.entity.AuctionBidEntity] → logic [AuctionBidItem].
     */
    private fun toAuctionBidItem(entity: opensamguk.infra.entity.AuctionBidEntity): opensamguk.logic.auction.AuctionBidItem {
        val aux = AuctionBidItemData.fromArray(
            opensamguk.logic.util.jsonDecode(entity.aux)
        )
        return opensamguk.logic.auction.AuctionBidItem(
            no = entity.no,
            auctionId = entity.auctionId,
            owner = entity.owner,
            generalId = entity.generalId,
            amount = entity.amount,
            date = entity.date.toString(),
            aux = aux,
        )
    }

    /**
     * [AuctionInfoDetail] → [AuctionDetail] (the shape [AuctionResultCalculator] expects).
     */
    private fun toAuctionDetail(d: AuctionInfoDetail): AuctionDetail = AuctionDetail(
        title = d.title,
        amount = d.amount,
        isReverse = d.isReverse ?: false,
        startBidAmount = d.startBidAmount,
        finishBidAmount = d.finishBidAmount,
        availableLatestBidCloseDate = d.availableLatestBidCloseDate,
        remainCloseDateExtensionCnt = d.remainCloseDateExtensionCnt,
    )
}

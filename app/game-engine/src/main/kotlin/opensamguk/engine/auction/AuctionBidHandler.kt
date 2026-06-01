package opensamguk.engine.auction

import opensamguk.common.wire.AuctionBidFail
import opensamguk.common.wire.AuctionBidOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.auction.AuctionBidValidator
import opensamguk.logic.auction.AuctionDetail
import opensamguk.logic.auction.AuctionStatus
import opensamguk.logic.auction.AuctionType
import opensamguk.logic.auction.BidValidationResult
import org.springframework.stereotype.Component

/**
 * 경매 입찰 핸들러 — [TurnDaemonCommand.AuctionBid] 명령 처리.
 *
 * PHP `AuctionBidder::bid()`의 Kotlin 포팅. 입찰의 전체 흐름을 처리:
 * 1. 경매 상태 확인 (OPEN)
 * 2. 최고 입찰자 / 이전 내 입찰 조회
 * 3. 입찰가 검증 ([AuctionBidValidator])
 * 4. 차액 계산 (morePoint)
 * 5. 자원/포인트 검증
 * 6. 이전 최고 입찰자 환불 ([InMemoryTurnWorld.updateGeneral])
 * 7. bid 삽입 + auction close_at 업데이트
 * 8. 결과 반환 ([AuctionBidOk] / [AuctionBidFail])
 *
 * @param world 인메모리 턴 월드 — 장수 자원 조작용
 */
@Component
class AuctionBidHandler(
    private val world: InMemoryTurnWorld,
) : TurnDaemonCommandHandler<TurnDaemonCommand.AuctionBid> {

    override suspend fun handle(command: TurnDaemonCommand.AuctionBid): TurnDaemonCommandResult {
        val auctionId = command.auctionId
        val generalId = command.generalId
        val amount = command.amount
        val tryExtendCloseDate = command.tryExtendCloseDate ?: true

        // ── 1. 경매 조회 및 상태 검증 ─────────────────────────────────────────
        // TODO: AuctionRepository를 통해 경매 조회
        // val auction = auctionRepository.findById(auctionId)
        //   ?: return AuctionBidFail(auctionId = auctionId, reason = "경매가 존재하지 않습니다.")
        // if (auction.status != AuctionStatus.OPEN) {
        //     return AuctionBidFail(auctionId = auctionId, reason = "마감된 경매입니다.")
        // }

        // ── 2. 장수 조회 ─────────────────────────────────────────────────────
        val general = world.getGeneralById(generalId)
            ?: return AuctionBidFail(auctionId = auctionId, reason = "장수가 존재하지 않습니다.")

        // ── 3. 최고 입찰자 / 이전 내 입찰 조회 ─────────────────────────────────
        // TODO: AuctionBidRepository를 통해 조회
        // val highestBid = bidRepository.findHighestBid(auctionId, auction.detail.isReverse)
        // val myPrevBid = bidRepository.findMyBid(auctionId, generalId)
        val highestBidAmount: Int? = null // TODO: from repository
        val myPrevBidAmount: Int? = null // TODO: from repository
        val highestBidGeneralId: Int? = null // TODO: from repository (for refund)
        val isReverse = false // TODO: from auction.detail.isReverse
        val startBidAmount: Int? = null // TODO: from auction.detail.startBidAmount
        val auctionType = AuctionType.BUY_RICE // TODO: from auction.type

        // ── 4. 입찰가 검증 ───────────────────────────────────────────────────
        val validationResult = if (auctionType == AuctionType.UNIQUE_ITEM) {
            // 유니크 아이템: validateUniqueBid로만 검증 (상승폭 + 유산 포인트)
            val inheritancePoint = general.meta["inheritancePoint"] as? Int ?: 0
            AuctionBidValidator.validateUniqueBid(
                bidAmount = amount,
                highestBidAmount = highestBidAmount,
                generalInheritancePoint = inheritancePoint,
                previousBidAmount = myPrevBidAmount,
            )
        } else {
            // 자원 경매: validateBid로 검증 (가격 + 자원 보유량)
            AuctionBidValidator.validateBid(
                auctionType = auctionType,
                bidAmount = amount,
                currentWinningBidAmount = highestBidAmount,
                startBidAmount = startBidAmount,
                isReverse = isReverse,
                generalGold = general.gold,
                generalRice = general.rice,
                previousBidAmount = myPrevBidAmount,
            )
        }
        if (validationResult is BidValidationResult.Fail) {
            return AuctionBidFail(auctionId = auctionId, reason = validationResult.reason)
        }

        // ── 5. 차액 계산 (morePoint) ─────────────────────────────────────────
        val morePoint = AuctionBidValidator.calculateMorePoint(amount, myPrevBidAmount)

        // ── 6. 자원 차감 및 이전 최고 입찰자 환불 ───────────────────────────────
        // 자원 경매: world 메모리상 즉시 처리
        when (auctionType) {
            AuctionType.BUY_RICE -> {
                // 금 차감
                if (morePoint > 0) {
                    val updated = general.copy(gold = general.gold - morePoint)
                    world.updateGeneral(updated)
                }
                // 이전 최고 입찰자 환불 (금)
                if (highestBidAmount != null) {
                    // TODO: highestBid.generalId로 환불 처리
                    // val prevHighest = world.getGeneralById(highestBid.generalId)
                    // prevHighest?.let { world.updateGeneral(it.copy(gold = it.gold + highestBid.amount)) }
                }
            }

            AuctionType.SELL_RICE -> {
                // 쌀 차감
                if (morePoint > 0) {
                    val updated = general.copy(rice = general.rice - morePoint)
                    world.updateGeneral(updated)
                }
                // 이전 최고 입찰자 환불 (쌀)
                if (highestBidAmount != null) {
                    // TODO: highestBid.generalId로 환불 처리
                }
            }

            AuctionType.UNIQUE_ITEM -> {
                // 유산 포인트 차감 (DB 직접 upsert)
                // TODO: inheritancePointRepository.upsert(generalId, -morePoint)
                // 이전 최고 입찰자 환불 (유산 포인트)
                if (highestBidAmount != null) {
                    // TODO: inheritancePointRepository.upsert(highestBid.generalId, +highestBid.amount)
                }
            }
        }

        // ── 7. bid 삽입 + auction close_at 업데이트 ──────────────────────────
        // TODO: AuctionBidRepository.insertBid(auctionId, generalId, amount, eventId, eventAt)
        // TODO: AuctionRepository.updateCloseAt(auctionId, nextCloseAt, latestEventId, latestEventAt)
        //       with optimistic locking (latest_event_at + latest_event_id)
        //       tryExtendCloseDate가 false면 close_at 연장하지 않음 (UNIQUE_ITEM 선택적 연장)

        // ── 8. 로그 작성 ─────────────────────────────────────────────────────
        world.pushLog(
            LogEntryDraft(
                scope = "action",
                category = "auction",
                text = "경매 #$auctionId 에 ${general.name} 이(가) $amount 에 입찰했습니다.",
                generalId = generalId,
            )
        )

        // ── 9. 결과 반환 ─────────────────────────────────────────────────────
        val closeAt = "TODO" // TODO: from updated auction
        return AuctionBidOk(
            auctionId = auctionId,
            closeAt = closeAt,
        )
    }
}

package opensamguk.engine.auction

import opensamguk.common.wire.AuctionBidFail
import opensamguk.common.wire.AuctionBidOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.TurnGeneral
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import opensamguk.logic.auction.AuctionBidItemData
import opensamguk.logic.auction.AuctionBidValidator
import opensamguk.logic.auction.AuctionDetail
import opensamguk.logic.auction.AuctionInfo
import opensamguk.logic.auction.AuctionInfoDetail
import opensamguk.logic.auction.AuctionStatus
import opensamguk.logic.auction.AuctionType
import opensamguk.logic.auction.BidValidationResult
import opensamguk.logic.domain.metaInt
import opensamguk.logic.util.jsonEncode
import java.time.Instant

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
 * @param recorder 변경 녹음기 — auction/bid flush 채널의 단일 dirty source
 * @param auctionRepository JPA read repository — 경매 조회
 * @param bidRepository JPA read repository — 입찰 조회
 *
 * 형제 turn 컴포넌트([ReservedTurnHandler] 등)와 동일하게 per-run plain 클래스다.
 * [InMemoryTurnWorld]는 스냅샷 기반 per-run 상태(싱글톤 빈 아님)이므로 Spring `@Component`로
 * 등록하지 않고 턴 파이프라인이 직접 인스턴스화한다.
 */
class AuctionBidHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val auctionRepository: AuctionRepository,
    private val bidRepository: AuctionBidRepository,
) : TurnDaemonCommandHandler<TurnDaemonCommand.AuctionBid> {

    override fun handle(command: TurnDaemonCommand.AuctionBid): TurnDaemonCommandResult {
        val auctionId = command.auctionId
        val generalId = command.generalId
        val amount = command.amount
        val tryExtendCloseDate = command.tryExtendCloseDate ?: true

        // ── 1. 경매 조회 및 상태 검증 ─────────────────────────────────────────
        val auctionEntity = auctionRepository.findById(auctionId).orElse(null)
            ?: return AuctionBidFail(auctionId = auctionId, reason = "경매가 존재하지 않습니다.")
        if (auctionEntity.finished) {
            return AuctionBidFail(auctionId = auctionId, reason = "마감된 경매입니다.")
        }

        val auction = toAuctionInfo(auctionEntity)
        val auctionType = auction.type
        val isReverse = auction.detail.isReverse ?: false
        val startBidAmount = auction.detail.startBidAmount

        // ── 2. 장수 조회 ─────────────────────────────────────────────────────
        val general = world.getGeneralById(generalId)
            ?: return AuctionBidFail(auctionId = auctionId, reason = "장수가 존재하지 않습니다.")

        // ── 3. 최고 입찰자 / 이전 내 입찰 조회 ─────────────────────────────────
        val allBids = bidRepository.findByAuctionIdOrderByAmountDesc(auctionId)
            .map { toAuctionBidItem(it) }

        val highestBid = selectHighestBid(allBids, isReverse)
        val myPrevBid = allBids.filter { it.generalId == generalId }
            .let { prevBids -> selectHighestBid(prevBids, isReverse) }

        val highestBidAmount = highestBid?.amount
        val myPrevBidAmount = myPrevBid?.amount
        val highestBidGeneralId = highestBid?.generalId

        // ── 4. 입찰가 검증 ───────────────────────────────────────────────────
        val validationResult = if (auctionType == AuctionType.UNIQUE_ITEM) {
            val inheritancePoint = metaInt(general.meta, "inheritancePoint")
            AuctionBidValidator.validateUniqueBid(
                bidAmount = amount,
                highestBidAmount = highestBidAmount,
                generalInheritancePoint = inheritancePoint,
                previousBidAmount = myPrevBidAmount,
            )
        } else {
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
        when (auctionType) {
            AuctionType.BUY_RICE -> {
                if (morePoint > 0) {
                    val updated = general.copy(gold = general.gold - morePoint)
                    world.updateGeneral(updated)
                }
                if (highestBidGeneralId != null && highestBidAmount != null) {
                    refundGeneral(highestBidGeneralId, "gold", highestBidAmount)
                }
            }

            AuctionType.SELL_RICE -> {
                if (morePoint > 0) {
                    val updated = general.copy(rice = general.rice - morePoint)
                    world.updateGeneral(updated)
                }
                if (highestBidGeneralId != null && highestBidAmount != null) {
                    refundGeneral(highestBidGeneralId, "rice", highestBidAmount)
                }
            }

            AuctionType.UNIQUE_ITEM -> {
                // 유산 포인트 차감 — KV channel via ChangeRecorder
                if (morePoint > 0) {
                    recorder.recordInheritancePointIncrease(
                        ownerID = generalId,
                        key = "auction_bid",
                        value = -morePoint.toDouble(),
                        aux = mapOf("auctionId" to auctionId, "amount" to amount),
                    )
                }
                if (highestBidGeneralId != null && highestBidAmount != null) {
                    recorder.recordInheritancePointIncrease(
                        ownerID = highestBidGeneralId,
                        key = "auction_refund",
                        value = highestBidAmount.toDouble(),
                        aux = mapOf("auctionId" to auctionId),
                    )
                }
            }
        }

        // ── 7. bid 삽입 + auction close_at 업데이트 ──────────────────────────
        val now = Instant.now()
        val bidData = AuctionBidItemData(
            generalName = general.name,
            tryExtendCloseDate = tryExtendCloseDate,
        )
        val bidItem = opensamguk.logic.auction.AuctionBidItem(
            no = null,
            auctionId = auctionId,
            owner = null,
            generalId = generalId,
            amount = amount,
            date = now.toString(),
            aux = bidData,
        )
        recorder.recordAuctionBidInsert(bidItem.toArray())

        // close_at 연장
        val newCloseDate = if (tryExtendCloseDate) {
            val turnTerm = resolveTurnTerm()
            val availableLatest = auction.detail.availableLatestBidCloseDate?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            }
            val extendedMs = opensamguk.logic.auction.AuctionResultCalculator.extendCloseDate(
                now = now.toEpochMilli(),
                closeAt = auctionEntity.closeDate.toEpochMilli(),
                turnMinutes = turnTerm,
                availableLatestBidCloseDate = availableLatest,
            )
            Instant.ofEpochMilli(extendedMs)
        } else {
            auctionEntity.closeDate
        }

        // detail 업데이트 (remainCloseDateExtensionCnt 감소)
        val nextDetail = auction.detail.copy(
            remainCloseDateExtensionCnt = (auction.detail.remainCloseDateExtensionCnt ?: 0) - 1,
        )
        val updatedAuction = auction.copy(
            closeDate = newCloseDate.toString(),
            detail = nextDetail,
        )
        recorder.recordAuctionUpsert(
            id = auctionId,
            columns = updatedAuction.toArray(withoutId = true),
        )

        // ── 8. 로그 작성 ─────────────────────────────────────────────────────
        world.pushLog(
            LogEntryDraft(
                scope = "action",
                category = "auction",
                text = "경매 #${auctionId} 에 ${general.name} 이(가) $amount 에 입찰했습니다.",
                generalId = generalId,
            )
        )

        // ── 9. 결과 반환 ─────────────────────────────────────────────────────
        return AuctionBidOk(
            auctionId = auctionId,
            closeAt = newCloseDate.toString(),
        )
    }

    /**
     * 이전 최고 입찰자에게 자원을 환불한다.
     */
    private fun refundGeneral(generalId: Int, resourceType: String, amount: Int) {
        val prevHighest = world.getGeneralById(generalId) ?: return
        val updated = when (resourceType) {
            "gold" -> prevHighest.copy(gold = prevHighest.gold + amount)
            "rice" -> prevHighest.copy(rice = prevHighest.rice + amount)
            else -> prevHighest
        }
        world.updateGeneral(updated)
    }

    /**
     * isReverse에 따라 최고/최저 입찰을 선택한다.
     */
    private fun selectHighestBid(bids: List<opensamguk.logic.auction.AuctionBidItem>, isReverse: Boolean): opensamguk.logic.auction.AuctionBidItem? {
        if (bids.isEmpty()) return null
        return if (isReverse) bids.minByOrNull { it.amount } else bids.maxByOrNull { it.amount }
    }

    /**
     * world state에서 turnterm을 해석한다 (분 단위).
     */
    private fun resolveTurnTerm(): Int {
        val state = world.getState()
        return (state.meta["turnterm"] as? Number)?.toInt() ?: (state.tickSeconds / 60)
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
}

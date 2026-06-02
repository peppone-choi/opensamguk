package opensamguk.engine.auction

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import opensamguk.logic.auction.AuctionStatus
import opensamguk.logic.auction.AuctionType
import java.time.Instant

/**
 * 경매 만료 데몬 — 매 턴 종료 시점에 활성 경매를 스캔하여 마감 시간이 지난 경매를 자동 종료한다.
 *
 * PHP `AuctionWorker::processExpiredAuctions()`의 Kotlin 포팅. 흐름:
 * 1. `finished = false`인 경매 조회
 * 2. `closeDate < now`인 경매 필터링
 * 3. 각 만료 경매에 대해 [AuctionFinalizeHandler] 호출
 * 4. 유니크 아이템 슬롯 충돌 시 경매 연장 (closeDate 재설정)
 * 5. 전역 액션 로그 기록
 *
 * [TurnRunService.runTick]에서 턴 드레인 후, 플러시 전에 호출된다.
 * 모든 변경은 동일한 [ChangeRecorder]에 기록되어 한 번에 플러시된다.
 *
 * @param auctionRepository JPA read repository — 경매 조회
 * @param bidRepository JPA read repository — 입찰 조회
 */
class AuctionExpiryDaemon(
    private val auctionRepository: AuctionRepository,
    private val bidRepository: AuctionBidRepository,
) {

    /**
     * 만료된 경매를 스캔하고 처리한다.
     *
     * @param world 인메모리 턴 월드
     * @param recorder 변경 녹음기
     * @param now 기준 시각 (기본값: [Instant.now])
     * @return 처리된 만료 경매 ID 목록
     */
    fun checkExpiredAuctions(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        now: Instant = Instant.now(),
    ): List<Int> {
        val activeAuctions = auctionRepository.findByFinishedFalse()
        val expired = activeAuctions.filter { it.closeDate.isBefore(now) }
        if (expired.isEmpty()) return emptyList()

        val processed = mutableListOf<Int>()
        for (auctionEntity in expired) {
            val auctionId = auctionEntity.id ?: continue

            // AuctionFinalizeHandler를 per-run으로 인스턴스화하여 처리
            val handler = AuctionFinalizeHandler(world, recorder, auctionRepository, bidRepository)
            val result = handler.handle(TurnDaemonCommand.AuctionFinalize(auctionId = auctionId))

            // 전역 액션 로그 (handler 내에서도 로그를 남기지만, 만료 처리임을 명시)
            world.pushLog(
                LogEntryDraft(
                    scope = "action",
                    category = "auction",
                    text = "경매 #${auctionId} 이(가) 마감 시간 만료로 자동 종료되었습니다.",
                )
            )

            processed.add(auctionId)
        }

        return processed
    }

    /**
     * 유니크 아이템 슬롯 충돌 여부를 검사한다.
     *
     * 낙찰자가 이미 동일 슬롯의 유니크 아이템을 보유 중이면 경매를 연장해야 한다.
     * 이 메서드는 [AuctionFinalizeHandler] 내에서 처리되지만, 외부에서 사전 검사용으로
     * 노출되어 있다.
     */
    fun hasUniqueItemSlotConflict(
        world: InMemoryTurnWorld,
        generalId: Int,
        itemKey: String,
    ): Boolean {
        val general = world.getGeneralById(generalId) ?: return false
        val slot = detectItemSlot(itemKey)
        val currentItem = when (slot) {
            "weapon" -> general.role.items.weapon
            "book" -> general.role.items.book
            "horse" -> general.role.items.horse
            "item" -> general.role.items.item
            else -> null
        }
        return currentItem != null && currentItem != "None"
    }

    private fun detectItemSlot(itemKey: String): String = when {
        itemKey.startsWith("che_무기_") -> "weapon"
        itemKey.startsWith("che_서적_") -> "book"
        itemKey.startsWith("che_명마_") -> "horse"
        else -> "item"
    }
}

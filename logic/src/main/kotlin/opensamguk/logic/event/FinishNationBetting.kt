package opensamguk.logic.event

import kotlinx.serialization.json.JsonPrimitive
import opensamguk.logic.betting.BettingInfo
import opensamguk.logic.betting.BettingResult
import opensamguk.logic.betting.BettingStatus
import opensamguk.logic.betting.CloseCondition

/**
 * 국가 강약 베팅 마감 이벤트 액션 — `FinishNationBetting`.
 *
 * PHP `Betting::giveReward()`의 Kotlin 포팅.
 *
 * 동작:
 * 1. KV 스토리지에서 [BettingInfo] 조회
 * 2. 남은 국가 수 확인 → 목표 도달 여부 판정
 * 3. 목표 미달 → [BettingResult.NotYet]
 * 4. 목표 도달 → 승자 결정 및 보상 분배
 *    - [BettingInfo.isExclusivePayout] == true: 1명이 전부
 *    - false: N등분
 * 5. 베팅 상태를 [BettingStatus.PAYOUT_DONE]으로 업데이트
 *
 * Factory args:
 * - args[0]: bettingId (String)
 *
 * 등록:
 * ```kotlin
 * factory.register(FinishNationBettingAction.NAME) { args ->
 *     val bettingId = (args[0] as JsonPrimitive).content
 *     FinishNationBettingAction(bettingId)
 * }
 * ```
 */
class FinishNationBettingAction(
    private val bettingId: String,
) : EventAction {

    override fun run(ctx: EventActionContext) {
        // 1. KV 스토리지에서 BettingInfo 조회
        @Suppress("UNCHECKED_CAST")
        val kvStorage = ctx.env["kvStorage"] as? MutableMap<String, Any>
        val bettingInfo = kvStorage?.let { storage ->
            @Suppress("UNCHECKED_CAST")
            storage[KV_KEY_PREFIX + bettingId] as? BettingInfo
        } ?: return  // NotFound — 조용히 반환

        // 이미 보상 분배 완료된 경우
        if (bettingInfo.status == BettingStatus.PAYOUT_DONE) return

        // 2. 남은 국가 수 확인
        @Suppress("UNCHECKED_CAST")
        val aliveNationIds = ctx.env["aliveNationIds"] as? List<Int> ?: emptyList()
        val remainingNations = aliveNationIds.size

        // 3. 마감 조건 확인
        val targetCount = when (bettingInfo.closeCondition) {
            is CloseCondition.RemainNationCount -> bettingInfo.closeCondition.count
            is CloseCondition.SpecificDate -> {
                // 현재 연/월이 목표 연/월에 도달했는지 확인
                val currentYear = (ctx.env["year"] as? Number)?.toInt() ?: 0
                val currentMonth = (ctx.env["month"] as? Number)?.toInt() ?: 0
                val cond = bettingInfo.closeCondition as CloseCondition.SpecificDate
                if (currentYear < cond.year || (currentYear == cond.year && currentMonth < cond.month)) {
                    return  // 아직 마감 시점 아님
                }
                0 // 날짜 기준은 여기서 통과하면 바로 진행
            }
            is CloseCondition.TargetDestroyed -> {
                // 대상 국가가 멸망했는지 확인
                val cond = bettingInfo.closeCondition as CloseCondition.TargetDestroyed
                if (cond.targetNationId in aliveNationIds) {
                    return  // 대상 국가가 아직 존재함
                }
                0 // 대상 멸망 → 바로 진행
            }
        }

        // RemainNationCount인 경우 목표 수 체크
        if (bettingInfo.closeCondition is CloseCondition.RemainNationCount) {
            if (remainingNations > targetCount) {
                // 아직 마감 조건 미달
                return
            }
        }

        // 4. 승자 결정 — 살아남은 국가들
        val winningNationIds = aliveNationIds.filter { it in bettingInfo.targetNations }
        if (winningNationIds.isEmpty()) {
            // 승자가 없음 → 베팅 취소/환불 처리
            cancelBetting(ctx, kvStorage, bettingInfo)
            return
        }

        // 5. 보상 분배
        val totalPool = bettingInfo.totalPool
        if (totalPool > 0) {
            // ng_betting 테이블에서 각 참여자의 베팅 내역 조회
            @Suppress("UNCHECKED_CAST")
            val bettingRecords = ctx.env["bettingRecords"] as? List<BettingRecord> ?: emptyList()
            val winners = bettingRecords.filter { it.selectedNationId in winningNationIds }
            if (winners.isNotEmpty()) {
                distributeRewards(ctx, winners, totalPool, bettingInfo.isExclusivePayout)
            }
        }

        // 6. 상태 업데이트
        val updatedBettingInfo = bettingInfo.copy(status = BettingStatus.PAYOUT_DONE)
        kvStorage?.let { storage ->
            storage[KV_KEY_PREFIX + bettingId] = updatedBettingInfo
        }

        // 7. 로그
        @Suppress("UNCHECKED_CAST")
        val logger = ctx.env["actionLogger"] as? ActionLoggerSeam
        logger?.pushGlobalActionLog(
            "국가 강약 베팅 '${bettingInfo.bettingId}' 이(가) 마감되었습니다. " +
                "(남은 국가: $remainingNations, 승자 국가: ${winningNationIds.size})"
        )
    }

    /**
     * 보상 분배 — 승자들에게 베팅 풀을 분배.
     *
     * @param ctx 이벤트 액션 컨텍스트
     * @param winners 승자 레코드 목록
     * @param totalPool 총 베팅 풀
     * @param isExclusive 독점 보상 여부 (true = 1명이 전부, false = N등분)
     */
    private fun distributeRewards(
        ctx: EventActionContext,
        winners: List<BettingRecord>,
        totalPool: Int,
        isExclusive: Boolean,
    ) {
        if (isExclusive) {
            // 독점 보상: 1명이 전부 — 가장 높은 베팅액을 낸 1명에게
            val topWinner = winners.maxByOrNull { it.amount } ?: return
            // TODO: generalRepository.addGold(topWinner.generalId, totalPool)
        } else {
            // 공유 보상: N등분
            val share = totalPool / winners.size
            winners.forEach { winner ->
                // TODO: generalRepository.addGold(winner.generalId, share)
            }
        }
    }

    /**
     * 베팅 취소 처리 — 승자가 없을 때.
     *
     * PHP `Betting::cancelBetting()` parity: 모든 참여자에게 베팅금 전액 환불.
     */
    private fun cancelBetting(
        ctx: EventActionContext,
        kvStorage: MutableMap<String, Any>?,
        bettingInfo: BettingInfo,
    ) {
        // 모든 참여자에게 베팅금 환불
        @Suppress("UNCHECKED_CAST")
        val bettingRecords = ctx.env["bettingRecords"] as? List<BettingRecord>
        bettingRecords?.forEach { record ->
            // TODO: generalRepository.refundGold(record.generalId, record.amount)
        }

        val updatedBettingInfo = bettingInfo.copy(status = BettingStatus.PAYOUT_DONE)
        kvStorage?.let { storage ->
            storage[KV_KEY_PREFIX + bettingInfo.bettingId] = updatedBettingInfo
        }

        // 로그
        @Suppress("UNCHECKED_CAST")
        val logger = ctx.env["actionLogger"] as? ActionLoggerSeam
        logger?.pushGlobalActionLog(
            "국가 강약 베팅 '${bettingInfo.bettingId}' 이(가) 취소되었습니다. (승자 없음 — 전원 환불)"
        )
    }

    /**
     * 베팅 참여 레코드 — ng_betting 테이블의 Kotlin DTO.
     */
    data class BettingRecord(
        val generalId: Int,
        val userId: String,
        val selectedNationId: Int,
        val amount: Int,
    )

    companion object {
        const val NAME = "FinishNationBetting"
        /** BettingInfo KV 키 접두사 — [OpenNationBettingAction]과 동일한 prefix 사용 */
        const val KV_KEY_PREFIX = "betting:"

        /**
         * [EventActionFactory]에 `FinishNationBetting` 리프를 등록한다.
         *
         * Args: [bettingId(String)]
         */
        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { args ->
                val bettingId = (args[0] as JsonPrimitive).content
                FinishNationBettingAction(bettingId)
            }
    }
}

package opensamguk.logic.betting

import opensamguk.logic.util.jsonDecodeAny
import opensamguk.logic.util.jsonEncode
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.phpRound

/**
 * 베팅 보상 계산/지급 엔진 — PHP `sammo\Betting::calcReward()` / `giveReward()` 의 Kotlin 포팅.
 *
 * 순수 함수로 구성: [calcReward]는 외부 상태를 읽지 않고 [BettingItem] 목록만 받아
 * [RewardItem] 목록을 반환. [giveReward]는 [BettingWorldView] seam을 통해
 * 금/유산포인트 지급 + 로그 + rank_data 업데이트를 수행.
 *
 * @param info 베팅 마스터 정보
 */
class BettingEngine(
    private val info: BettingInfo,
) {

    // ═══════════════════════════════════════════════════════════════════════
    //  calcReward — 보상 계산 (PHP Betting::calcReward)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 당첨 후보 인덱스 목록으로부터 각 참여자의 보상을 계산.
     *
     * @param winnerType 당첨 후보 인덱스 배열 (PHP `winnerType`)
     * @return [RewardItem] 목록 (generalId > 0 인 참여자만)
     */
    fun calcReward(winnerType: List<Int>, allBets: List<BettingItem>): List<RewardItem> {
        val selectCnt = info.selectCnt
        if (selectCnt == 1 || info.isExclusive == true) {
            return calcRewardExclusive(winnerType, allBets)
        }
        // 아래는 2개 이상, 복합 보상 옵션
        return calcRewardCompound(winnerType, allBets)
    }

    /** 독점 보상 계산 — 전체 풀을 정확히 맞춘 사람들에게 비례 분배. */
    private fun calcRewardExclusive(
        winnerType: List<Int>,
        allBets: List<BettingItem>,
    ): List<RewardItem> {
        val totalAmount = allBets.sumOf { it.amount.toDouble() }
        if (totalAmount == 0.0) return emptyList()

        val winnerKey = convertBettingKey(winnerType)
        val winnerList = allBets.filter {
            it.generalId > 0 && it.bettingType == winnerKey
        }

        val subAmount = winnerList.sumOf { it.amount.toDouble() }
        if (subAmount == 0.0) {
            // 당첨자가 아물도 없다면 무효 — 빈 목록 반환 (PHP: return [])
            return emptyList()
        }

        val multiplier = totalAmount / subAmount
        return winnerList.map { bet ->
            RewardItem(
                generalId = bet.generalId,
                userId = bet.userId,
                amount = bet.amount * multiplier,
                matchPoint = info.selectCnt,
            )
        }
    }

    /** 복합 보상 계산 — match-point 기반 누적 분배. */
    private fun calcRewardCompound(
        winnerType: List<Int>,
        allBets: List<BettingItem>,
    ): List<RewardItem> {
        val selectCnt = info.selectCnt
        val winnerTypeMap = winnerType.associateWith { it }

        val calcMatchPoint = { bettingTypeKey: String ->
            val decoded = decodeBettingKey(bettingTypeKey)
            decoded.count { winnerTypeMap.containsKey(it) }
        }

        val totalAmount = allBets.sumOf { it.amount.toDouble() }
        val subAmount = Array(selectCnt + 1) { 0.0 }
        val subWinners = Array(selectCnt + 1) { mutableListOf<RewardItem>() }

        for (bet in allBets) {
            val matchPoint = calcMatchPoint(bet.bettingType)
            if (bet.generalId > 0) {
                subAmount[matchPoint] += bet.amount
                subWinners[matchPoint].add(
                    RewardItem(
                        generalId = bet.generalId,
                        userId = bet.userId,
                        amount = bet.amount.toDouble(),
                        matchPoint = matchPoint,
                    )
                )
            }
        }

        var remainRewardAmount = totalAmount
        var accumulatedRewardAmount = 0.0
        var givenRewardAmount = totalAmount
        val rewardAmount = mutableMapOf<Int, Double>()

        for (matchPoint in selectCnt downTo 1) {
            givenRewardAmount /= 2.0
            accumulatedRewardAmount += givenRewardAmount
            if (subWinners[matchPoint].isEmpty()) continue
            if (subAmount[matchPoint] == 0.0) continue

            rewardAmount[matchPoint] = accumulatedRewardAmount
            remainRewardAmount -= accumulatedRewardAmount
            accumulatedRewardAmount = 0.0
        }

        // 남은 상금은 '당첨자'에게 몰아준다.
        // 당첨자가 아물도 없다면, 0개 맞춘 그룹에게 돌아간다.
        if (rewardAmount.isNotEmpty()) {
            for (matchPoint in selectCnt downTo 0) {
                if (matchPoint !in rewardAmount) continue
                rewardAmount[matchPoint] = rewardAmount[matchPoint]!! + remainRewardAmount
                break
            }
        }

        val result = mutableListOf<RewardItem>()
        for (matchPoint in selectCnt downTo 0) {
            val subReward = rewardAmount[matchPoint] ?: continue
            if (subReward == 0.0) continue
            val multiplier = subReward / subAmount[matchPoint]
            for (subWinner in subWinners[matchPoint]) {
                result.add(subWinner.copy(amount = subWinner.amount * multiplier))
            }
        }
        return result
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  giveReward — 보상 지급 (PHP Betting::giveReward)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 계산된 보상을 실제로 지급하고 로그를 남긴다.
     *
     * @param winnerType 당첨 후보 인덱스 배열
     * @param allBets 전체 베팅 목록
     * @param world [BettingWorldView] seam (금/포인트 지급, 로그, rank_data)
     */
    fun giveReward(
        winnerType: List<Int>,
        allBets: List<BettingItem>,
        world: BettingWorldView,
    ) {
        val rewardList = calcReward(winnerType, allBets)
        val selectCnt = info.selectCnt

        if (info.reqInheritancePoint) {
            // 유산포인트 지급
            val userLoggers = linkedMapOf<Int, MutableList<String>>()
            for (reward in rewardList) {
                if (reward.userId == null) continue
                val userId = reward.userId
                val amount = reward.amount

                val nextPoint = world.increaseInheritancePointRaw(userId, amount)
                val previousPoint = nextPoint - amount

                world.increaseRankData(reward.generalId, "inherit_point_earned_by_action", amount)

                val amountText = numberFormat(phpRound(amount))
                val previousPointText = numberFormat(phpRound(previousPoint))
                val nextPointText = numberFormat(phpRound(nextPoint))

                val partialText = if (reward.matchPoint == selectCnt) {
                    "베팅 당첨"
                } else {
                    "베팅 부분 당첨(${reward.matchPoint}/$selectCnt)"
                }

                val lines = userLoggers.getOrPut(userId) { mutableListOf() }
                lines.add("${info.name} $partialText 보상으로 ${amountText} 포인트 획득.")
                lines.add("포인트 $previousPointText => $nextPointText")
            }

            for ((userId, lines) in userLoggers) {
                world.pushUserLogs(userId, lines, "inheritPoint")
            }
        } else {
            // 금 지급
            val generalIds = rewardList.map { it.generalId }.distinct()
            val generalMap = world.generalsById(generalIds)

            for (reward in rewardList) {
                val gambler = generalMap[reward.generalId] ?: continue
                val rewardGold = phpRound(reward.amount)
                val matchPoint = reward.matchPoint

                world.addGeneralGold(reward.generalId, rewardGold)

                if (gambler.npcType == 0 || (gambler.npcType == 1 && world.getRankVar(reward.generalId, "betgold", 0) > 0)) {
                    world.increaseRankData(reward.generalId, "betwingold", rewardGold.toDouble())
                    world.increaseRankData(reward.generalId, "betwin", 1.0)
                }

                val partialText = if (matchPoint == selectCnt) {
                    "베팅 당첨"
                } else {
                    "베팅 부분 당첨($matchPoint/$selectCnt)"
                }
                val rewardText = numberFormat(rewardGold)
                world.pushGeneralActionLog(
                    reward.generalId,
                    "<C>${info.name}</>의 $partialText 보상으로 <C>$rewardText</>의 <S>금</> 획득!",
                )
            }
        }

        // 베팅 상태 업데이트
        info.finished = true
        info.winner = winnerType
        world.saveBettingInfo(info)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Helpers — betting key encode/decode (PHP Betting::convertBettingKey)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 베팅 키 정화 — 정렬 + 중복 제거 + 개수 검증.
     * PHP `Betting::purifyBettingKey` parity.
     *
     * @param bettingType 후보 인덱스 배열
     * @param noValidate 후보 유효성 검증 스킵 (FinishNationBetting에서 베팅 이후 생성된 국가 처리 시)
     * @return 정렬된 유일한 인덱스 배열
     */
    fun purifyBettingKey(bettingType: List<Int>, noValidate: Boolean = false): List<Int> {
        val selectCnt = info.selectCnt
        val sorted = bettingType.sorted().distinct()
        require(sorted.size == selectCnt) { "중복된 값이 있습니다." }

        if (!noValidate) {
            for (key in sorted) {
                require(key in info.candidates) { "올바른 후보가 아닙니다: $key" }
            }
        }
        return sorted
    }

    /** JSON 인코딩된 베팅 키 생성. PHP `Betting::_convertBettingKey` parity. */
    fun convertBettingKey(bettingType: List<Int>): String = jsonEncode(bettingType)

    /** JSON 디코딩된 베팅 키 파싱. */
    fun decodeBettingKey(bettingTypeKey: String): List<Int> {
        @Suppress("UNCHECKED_CAST")
        val decoded = jsonDecodeAny(bettingTypeKey) as? List<Number> ?: return emptyList()
        return decoded.map { it.toInt() }
    }
}

/**
 * [BettingEngine.giveReward]가 필요로 하는 world seam.
 * 데몬 측 [InMemoryTurnWorld] 어댑터가 구현한다.
 */
interface BettingWorldView {
    /** 장수 ID로 장수 정보 조회. */
    fun generalsById(ids: List<Int>): Map<Int, GeneralForBetting>

    /** 장수에게 금 추가. */
    fun addGeneralGold(generalId: Int, amount: Int)

    /** rank_data.value 증가. */
    fun increaseRankData(generalId: Int, type: String, amount: Double)

    /** rank_data 값 조회 (기본값). */
    fun getRankVar(generalId: Int, type: String, default: Int): Int

    /** 유산 포인트 증가 — 증가 후 새 잔액 반환. */
    fun increaseInheritancePointRaw(userId: Int, amount: Double): Double

    /** 사용자 로그 푸시. */
    fun pushUserLogs(userId: Int, lines: List<String>, type: String)

    /** 장수 개인 액션 로그 푸시. */
    fun pushGeneralActionLog(generalId: Int, msg: String)

    /** 베팅 정보 저장 (KV 스토리지). */
    fun saveBettingInfo(info: BettingInfo)
}

/**
 * [BettingWorldView]가 반환하는 최소 장수 정보.
 * PHP `General::getNPCType()` + `General::getRankVar()` 에 해당.
 */
data class GeneralForBetting(
    val id: Int,
    val npcType: Int,
    val name: String,
)

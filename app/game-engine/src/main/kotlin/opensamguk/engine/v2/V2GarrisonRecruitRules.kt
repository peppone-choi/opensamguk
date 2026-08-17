package opensamguk.engine.v2

import opensamguk.common.constants.GameConst
import opensamguk.logic.util.phpRound

/**
 * OPENSAM-153 (v2 R4) — 도시병사 보충 순수 판정. draw 0, 세계 접근 0.
 *
 * PHP 근거는 `legacy/devsam-core/hwe/sammo/Command/General/che_징병.php`(이하 그냥 che_징병).
 */
sealed interface V2RecruitDecision {
    data class Denied(val reason: String) : V2RecruitDecision
    data class Applied(val goldCost: Long, val amount: Int, val popAfter: Int, val trustAfter: Double) : V2RecruitDecision
}

/** 100명당 금 9(che_징병 GameUnitConstBase보병 cost=9) → 1명당 0.09. 기술 계수는 곱하지 않는다 — divergence, 아래 함수 KDoc 참고. */
const val GOLD_PER_CREW: Double = 0.09

/**
 * che_징병 판정 순서 그대로: 하한(:107) → 통솔 상한(:95-96, v1은 clamp 하지만 우리는 deny) →
 * 인구 게이트(:118, `GameConst.minAvailableRecruitPop`) → 비용(기술 계수 미적용 — 도시 원장은 국가
 * 기술을 모른다, divergence) → 금 부족(:209 이전 가드).
 *
 * 인구·치안 차감은 묘섭 "+200 추가 보충은 인구감소 없음" 대우(기본 보충 = 있음)와 che_징병.php:209
 * `trust = valueFit(trust - (reqCrewDown/pop)/costOffset*100, 0)`(costOffset=1, pop=차감 전 인구).
 */
fun recruitDecision(
    amount: Int,
    leadership: Int,
    cityPopulation: Int,
    cityTrust: Double,
    ledgerGold: Long,
): V2RecruitDecision {
    if (amount < 100) return V2RecruitDecision.Denied("최소 100명부터 보충할 수 있습니다.")
    if (amount > leadership * 100) return V2RecruitDecision.Denied("통솔로 보충할 수 있는 한도를 넘었습니다.")
    if (cityPopulation - amount < GameConst.minAvailableRecruitPop) return V2RecruitDecision.Denied("주민이 부족합니다.")

    val goldCost = phpRound(amount * GOLD_PER_CREW).toLong()
    if (ledgerGold < goldCost) return V2RecruitDecision.Denied("도시의 금이 부족합니다.")

    val popAfter = cityPopulation - amount
    val trustAfter = (cityTrust - (amount.toDouble() / cityPopulation) * 100).coerceAtLeast(0.0)
    return V2RecruitDecision.Applied(goldCost, amount, popAfter, trustAfter)
}

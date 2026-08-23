package opensamguk.engine.v2

import opensamguk.logic.v2.command.V2GarrisonRecruitArgs
import opensamguk.logic.v2.command.V2GarrisonRecruitContext
import opensamguk.logic.v2.command.decideGarrisonRecruit

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
const val GOLD_PER_CREW: Double = opensamguk.logic.v2.command.V2_GARRISON_GOLD_PER_CREW

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
    return when (
        val decision = decideGarrisonRecruit(
            V2GarrisonRecruitArgs(cityId = 1, amount = amount),
            V2GarrisonRecruitContext(
                generalCityId = 1,
                generalNationId = 1,
                leadership = leadership,
                cityNationId = 1,
                cityPopulation = cityPopulation,
                cityTrust = cityTrust,
                ledgerGold = ledgerGold,
            ),
        )
    ) {
        is opensamguk.logic.v2.command.V2GarrisonRecruitDecision.Denied -> V2RecruitDecision.Denied(decision.reason)
        is opensamguk.logic.v2.command.V2GarrisonRecruitDecision.Applied -> V2RecruitDecision.Applied(
            decision.goldCost,
            decision.amount,
            decision.popAfter,
            decision.trustAfter,
        )
    }
}

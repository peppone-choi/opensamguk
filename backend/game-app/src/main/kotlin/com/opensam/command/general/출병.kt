package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.roundToInt
import kotlin.random.Random

class 출병(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "출병"

    override val fullConditionConstraints: List<Constraint>
        get() {
            val relYear = env.year - env.startYear
            val cost = getCost()
            return listOf(
                NotOpeningPart(relYear),
                NotSameDestCity(),
                NotBeNeutral(),
                OccupiedCity(),
                ReqGeneralCrew(),
                ReqGeneralRice(cost.rice),
                AllowWar(),
                HasRouteWithEnemy(),
            )
        }

    override val minConditionConstraints: List<Constraint>
        get() {
            val relYear = env.year - env.startYear
            val cost = getCost()
            return listOf(
                NotOpeningPart(relYear + 2),
                NotBeNeutral(),
                OccupiedCity(),
                ReqGeneralCrew(),
                ReqGeneralRice(cost.rice),
            )
        }

    override fun getCost(): CommandCost {
        val rice = (general.crew / 100.0).roundToInt()
        return CommandCost(gold = 0, rice = rice)
    }

    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    /**
     * 출병 실행:
     * - 최단거리 기반 적군 도시 탐색 (legacy: searchDistanceListToDest)
     * - 적군 도시가 없으면 아군 도시로 이동 (alternative: che_이동)
     * - 최종 목적지가 아닌 경유 도시를 공격할 수 있음
     * - 도시 state=43, term=3 설정
     * - 병종숙련 += crew/100
     * - 500명 이상 & 훈련*사기 > 70*70 이면 inheritancePoint +1
     * - processWar 호출
     */
    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val finalTargetCityId = arg?.get("destCityID") as? Int ?: destCity?.id ?: 0
        val finalTargetCityName = destCity?.name ?: "알 수 없음"
        val attackerNationId = general.nationId

        // Route finding is handled by the engine layer; here we produce
        // the result payload that the engine uses to trigger war.
        // The engine resolves candidateCities via searchDistanceListToDest.

        val cost = getCost()
        val dexGain = (general.crew / 100.0).roundToInt()

        // Inheritance point: 500명 이상, 훈련*사기 > 70*70
        val earnInheritance = general.crew > 500 &&
                general.train * general.atmos > 70 * 70

        val destCityName = destCity?.name ?: "알 수 없음"

        // If the resolved dest city belongs to own nation, this becomes a move command
        // (handled by engine layer checking defenderNationID == attackerNationID)
        // Here we emit the battle trigger payload for the general case.

        pushLog("<G><b>${destCityName}</b></>(으)로 출병합니다. <1>$date</>")

        return CommandResult(
            success = true,
            logs = logs,
            message = buildString {
                append("""{"statChanges":{"rice":${-cost.rice}}""")
                append(""","dexChanges":{"crewType":${general.crewType},"amount":$dexGain}""")
                append(""","battleTriggered":true""")
                append(""","targetCityId":$finalTargetCityId""")
                append(""","cityStateUpdate":{"cityId":${destCity?.id ?: 0},"state":43,"term":3}""")
                if (earnInheritance) {
                    append(""","inheritancePoint":{"key":"active_action","amount":1}""")
                }
                append("}")
            }
        )
    }
}

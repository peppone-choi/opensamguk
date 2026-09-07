package opensamguk.engine.war

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.war.BattleCommandContext
import opensamguk.logic.war.searchDistanceListToDest
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.domain.General
import opensamguk.logic.world.ActiveWorldMap
import opensamguk.logic.operation.OperationRules
import opensamguk.logic.tick.GameDate
import opensamguk.logic.war.plan.SealedBattlePlan

/**
 * BO2 — the engine-side builder that stages a [BattleCommandContext] for the `che_출병` resolver (BO3),
 * mirroring the core2026 `actionContextBuilder` battle slice. It reads the [InMemoryTurnWorld] in-memory
 * (the source of truth) and resolves exactly the PHP DB reads `che_출병`/`processWar` perform:
 *
 *  - the allowed-nation list = my-state-0 (AT-WAR / 교전중) diplomacy nations ∪ {me} ∪ {0 neutral}
 *    (`che_출병.php:157-159`; devsam diplomacy `state = 0` = at war, the attackable set);
 *  - the allowed city→nation map (`SELECT city, nation FROM city WHERE nation IN allowedNations`,
 *    `func.php:2020-2025`) → the [searchDistanceListToDest] input;
 *  - the per-city defender general list (same-nation, non-neutral, `process_war.php:40-41`);
 *  - the raw city / nation rows (`process_war.php:30-38`);
 *  - the war-seed lineage inputs (hidden seed + logger year/month).
 *
 * NO RNG draws (the choice draw lives in BO3); NO mutation (read-only world staging).
 */
object BattleCommandContextBuilder {

    fun build(
        world: InMemoryTurnWorld,
        attackerGeneralId: Int,
        finalTargetCityId: Int,
        hiddenSeed: String,
        loggerYear: Int,
        loggerMonth: Int,
        pipelineFor: ((General) -> GeneralActionPipeline)? = null,
        /** Phase 4X-C(M5): AI 가 명령을 바꾼 턴이면 봉인 계획을 싣지 않는다(계획은 사람이 예약한 목표 도시로의 출병에만). */
        autorunMode: Boolean = false,
    ): BattleCommandContext {
        val attacker = world.getGeneralById(attackerGeneralId)
            ?: error("BattleCommandContextBuilder: general $attackerGeneralId not in world")
        val myNationId = attacker.nationId
        val attackerCityId = attacker.cityId

        // allowed-nation list = my-state-0 (AT-WAR) nations ∪ {me} ∪ {0} (che_출병.php:157-159).
        val allowedNations = LinkedHashSet<Int>()
        for (d in world.listDiplomacy()) {
            if (d.fromNationId == myNationId && d.state == 0) allowedNations.add(d.toNationId)
        }
        allowedNations.add(myNationId)
        allowedNations.add(0)

        // allowed city→nation map (func.php:2020-2025) — every city whose nation is allowed.
        val allowedCityList = LinkedHashMap<Int, Int>()
        for (c in world.listCities()) {
            if (c.nationId in allowedNations) allowedCityList[c.id] = c.nationId
        }

        val state = world.getState()
        val cityConst = ActiveWorldMap.requireVariant(state.config, state.meta)
        val distanceList = searchDistanceListToDest(attackerCityId, finalTargetCityId, allowedCityList, cityConst)

        // per-city defender generals (same-nation, non-neutral) (process_war.php:40-41).
        val defenderGeneralsByCity = LinkedHashMap<Int, MutableList<opensamguk.logic.domain.General>>()
        for (g in world.listGenerals()) {
            if (g.nationId == 0) continue
            val c = world.getCityById(g.cityId) ?: continue
            if (c.nationId != g.nationId) continue
            defenderGeneralsByCity.getOrPut(g.cityId) { mutableListOf() }
                .add(PerTurnOverlay.toLogicGeneral(g))
        }
        // pin ascending PK per city — the SELECT had no ORDER BY (PR-4); BO1 re-sorts but stage deterministically.
        val defenderByCity = defenderGeneralsByCity.mapValues { (_, list) -> list.sortedBy { it.id } }

        val cityById = world.listCities().associate { it.id to PerTurnOverlay.toLogicCity(it) }
        val nationById = world.listNations().associate { it.id to PerTurnOverlay.toLogicNation(it) }
        val logicGenerals = world.listGenerals().map { PerTurnOverlay.toLogicGeneral(it) }

        // Phase 4X-C(spec v4.1 §5 F5·F6): 봉인·미소비 계획 중 `sealedDate <= executingDate`(같은 순 봉인도 적용), 키 = 목표 도시.
        val executing = GameDate(state.currentYear, state.currentMonth, state.currentPhase.coerceIn(1, 3))
        val sealedPlans = if (autorunMode) emptyMap() else world.battlePlansOf(attackerGeneralId)
            .filter { p -> p.sealed && !p.resolved && OperationRules.absoluteTurn(GameDate(p.sealedYear ?: 0, p.sealedMonth ?: 1, p.sealedPhase ?: 1)) <= OperationRules.absoluteTurn(executing) }
            .associate { p -> p.targetCityId to SealedBattlePlan(p.id, p.targetCityId, p.stance, p.retreatLossPct, p.retreatMoraleBelow) }

        return BattleCommandContext(
            attackerCityId = attackerCityId,
            finalTargetCityId = finalTargetCityId,
            myNationId = myNationId,
            distanceList = distanceList,
            defenderGeneralsByCity = defenderByCity,
            cityById = cityById,
            nationById = nationById,
            pipelinesByGeneralId = if (pipelineFor == null) {
                emptyMap()
            } else {
                logicGenerals.associate { it.id to pipelineFor(it) }
            },
            effectiveGeneralCountByNationId = world.listGenerals()
                .filter { it.npcState != 5 }
                .groupingBy { it.nationId }
                .eachCount(),
            hiddenSeed = hiddenSeed,
            loggerYear = loggerYear,
            loggerMonth = loggerMonth,
            sealedPlans = sealedPlans,
        )
    }
}

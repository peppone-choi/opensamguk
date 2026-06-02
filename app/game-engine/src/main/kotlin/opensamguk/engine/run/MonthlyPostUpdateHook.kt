package opensamguk.engine.run

import opensamguk.common.rng.RandUtil
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.tick.PostUpdateMonthly
import opensamguk.logic.world.DiplomacyRow
import opensamguk.logic.world.PostNationPowerInput
import opensamguk.logic.world.PowerCity
import opensamguk.logic.world.PowerGeneral
import opensamguk.logic.world.postUpdateMonthlyDiplomacy
import opensamguk.logic.world.postUpdateMonthlyPower
import opensamguk.logic.world.postUpdateMonthlyTail

/**
 * P6 Task 6 — the `postUpdateMonthly` hook wired into [MonthlyPipeline].
 *
 * Implements the Q1-Q17 ordered settlement set (POST1/POST2/POST3) on the single month-scoped RNG.
 * All mutations are recorded through [ChangeRecorder] (the single dirty source) and applied to
 * [InMemoryTurnWorld] read-state so downstream logic sees the post-state immediately.
 */
class MonthlyPostUpdateHook(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val pipeline: GeneralActionPipeline,
) : PostUpdateMonthly<RandUtil> {

    override fun run(monthlyRng: RandUtil) {
        val nations = world.listNations()
        val generals = world.listGenerals()
        val cities = world.listCities()

        // ---- POST1 (Q1-Q4): power aggregate + jitter ----
        val powerInputs = nations.map { nation ->
            val nationGenerals = generals.filter { it.nationId == nation.id }
            val nationCities = cities.filter { it.nationId == nation.id }
            val logicNation = PerTurnOverlay.toLogicNation(nation)
            PostNationPowerInput(
                nationId = nation.id,
                gennum = logicNation.gennum,
                gold = nation.gold,
                rice = nation.rice,
                tech = logicNation.tech.toInt(),
                level = nation.level,
                generals = nationGenerals.map { g ->
                    val meta = g.meta
                    PowerGeneral(
                        id = g.id,
                        gold = g.gold,
                        rice = g.rice,
                        leadership = g.stats.leadership,
                        strength = g.stats.strength,
                        intel = g.stats.intelligence,
                        npc = g.npcState,
                        dexSum = DEX_KEYS.sumOf { (meta[it] as? Number)?.toInt() ?: 0 },
                        experience = g.experience,
                        dedication = g.dedication,
                        crew = g.crew,
                        killcrewPersonRankValue = 0, // rank_data not loaded in-memory; defaults to 0 (PHP LEFT JOIN parity)
                        deathcrewPersonRankValue = 0,
                    )
                },
                cities = nationCities.map { c ->
                    PowerCity(
                        pop = c.population,
                        sumStat = c.population + c.agriculture + c.commerce + c.security + c.wall + c.defence,
                        sumMax = c.populationMax + c.agricultureMax + c.commerceMax + c.securityMax + c.wallMax + c.defenceMax,
                        supply = c.supplyState != 0,
                    )
                },
                cityNames = nationCities.map { it.name },
            )
        }

        // TODO: read existing max_power KV from nation_env when a KV read path is available.
        val existingMaxPower = emptyMap<Int, opensamguk.logic.world.PowerKv>()
        val powerResult = postUpdateMonthlyPower(powerInputs, existingMaxPower, monthlyRng)

        for (result in powerResult.nations) {
            val pre = world.getNationById(result.nationId) ?: continue
            val logicPre = PerTurnOverlay.toLogicNation(pre)
            val logicPost = logicPre.copy(power = result.power)
            recorder.diffNation(logicPre, logicPost)
            world.updateNation(pre.copy(power = result.power))
            recorder.recordNationEnvKv(
                result.nationId,
                "max_power",
                linkedMapOf(
                    "maxPower" to result.maxPowerKv.maxPower,
                    "maxCrew" to result.maxPowerKv.maxCrew,
                    "maxCities" to result.maxPowerKv.maxCities,
                ),
            )
        }

        // ---- POST2 (Q5-Q10): diplomacy settlement ----
        val diplomacyRows = world.listDiplomacy().map {
            DiplomacyRow(me = it.fromNationId, you = it.toNationId, state = it.state, term = it.term, dead = it.dead)
        }
        val genNum = generals.groupBy { it.nationId }.mapValues { it.value.size }
        val nationNames = nations.associate { it.id to it.name }
        val nationIds = nations.map { it.id }

        // TODO: read existing available_war_setting_cnt KV when a read path is available.
        val diplomacyResult = postUpdateMonthlyDiplomacy(
            rows = diplomacyRows,
            genNum = genNum,
            nationNames = nationNames,
            maxPower = emptyMap(),
            nations = nationIds,
        )

        // Q5 — war-term update on state=0 rows
        for (u in diplomacyResult.q5Updates) {
            val pre = world.getDiplomacy(u.me, u.you) ?: continue
            val post = world.updateDiplomacy(u.me, u.you, pre.state, u.newTerm, u.newDead) ?: continue
            recorder.diffDiplomacy(pre, post)
        }

        // Q6 — 개전 logs
        for (log in diplomacyResult.warStartLogs) {
            world.pushLog(LogEntryDraft(scope = "global", category = "history", text = log))
        }

        // Q7 — 종전 state updates
        for (u in diplomacyResult.q7StateUpdates) {
            val pre = world.getDiplomacy(u.me, u.you) ?: continue
            val post = world.updateDiplomacy(u.me, u.you, u.newState, u.newTerm, pre.dead) ?: continue
            recorder.diffDiplomacy(pre, post)
        }

        // Q7 — 종전 logs
        for (log in diplomacyResult.warStopLogs) {
            world.pushLog(LogEntryDraft(scope = "global", category = "history", text = log))
        }

        // Q9 — bulk final state/term/dead
        for (u in diplomacyResult.q9Updates) {
            val pre = world.getDiplomacy(u.me, u.you) ?: continue
            val post = world.updateDiplomacy(u.me, u.you, u.newState, u.newTerm, u.newDead) ?: continue
            recorder.diffDiplomacy(pre, post)
        }

        // Q10 — available_war_setting_cnt KV
        for ((nationId, cnt) in diplomacyResult.availableWarSettingCnt) {
            recorder.recordNationEnvKv(nationId, "available_war_setting_cnt", cnt)
        }

        // ---- POST3 (Q11-Q17): tail ----
        val state = world.getState()
        val year = state.currentYear
        val startYear = (state.meta["startYear"] as? Number)?.toInt() ?: 0
        val isUnited = (state.meta["isunited"] as? Int ?: 0) != 0

        postUpdateMonthlyTail(
            year = year,
            startYear = startYear,
            rng = monthlyRng,
            checkWander = { _ -> },         // no-op until checkWander is ported
            triggerTournament = { _ -> },   // no-op until tournament is ported
            registerAuction = { _ -> },     // no-op until auction is ported
            setNationFront = { emptyList() }, // no-op until SetNationFront is ported
            isUnited = isUnited,
        )
    }

    companion object {
        private val DEX_KEYS = listOf("dex1", "dex2", "dex3", "dex4", "dex5")
    }
}

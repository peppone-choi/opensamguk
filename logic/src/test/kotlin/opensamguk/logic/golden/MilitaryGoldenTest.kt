package opensamguk.logic.golden

import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME — Task GR2 military-family byte gate.
 *
 * 훈련/맹훈련/사기진작 (leadership-driven, crew present), 소집해제 (disband), 이동 (move),
 * 모병/징병 (recruit, gid 152). Drives the REAL resolver via [CommandRegistry] at the exact seed.
 *
 * The train/morale resolvers call `addDex(crewType, …)` which requires a crewType id ≥ 1000; the GR1
 * snapshot dumps crew but not crew_type_id (dex rides meta, not the dumped after-state). The captured
 * actor has crew > 0 → a real unit; we supply the default 보병 (1100), which does not perturb the log
 * or any dumped after-field. 모병/징병 read the recruit arg (crewType/amount) from the golden case.
 */
class MilitaryGoldenTest {

    private val registry = CommandRegistry(GeneralActionPipeline())

    private val commands = listOf("che_훈련", "cr_맹훈련", "che_사기진작", "che_소집해제", "che_이동", "che_모병", "che_징병")

    @Test
    fun `military family commands byte-match the PHP golden action log and post-state`() {
        for (command in commands) {
            val f = P2GoldenSupport.load(command)
            val def = registry.resolve(command)
            assertEquals(command, def.key, "$command registry key")
            for (c in f.cases) {
                var g = P2GoldenSupport.generalFrom(c.generalId, c.before.general,
                    extraMeta = linkedMapOf("leadership_exp" to 0.0))
                // train/morale need a valid crewType for addDex (crew>0 → a real unit; default 보병).
                if (g.crew > 0) g = g.copy(crewTypeId = 1100)
                val nation = P2GoldenSupport.nationFrom(c, gold = 100000, rice = 100000,
                    tech = P2GoldenSupport.techOf(c.before.general.int("nation")))
                val draft = GeneralActionDraft(g, P2GoldenSupport.cityFrom(c.cityId, c.before.city), nation)
                // 이동 needs the dest-city name; provide via candidate? CheIdong reads CityConst.byId.
                val ctx = P2GoldenSupport.ctxFor(f, c, draft, def.rawClassName)
                def.resolve(ctx)

                assertEquals(c.logLines, ctx.logs(), "[$command/${c.name}] action-log byte-match")
                assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[$command/${c.name}] broadcast byte-match")
                assertAfter(command, c, draft.general, draft.city)
            }
        }
    }

    private fun assertAfter(command: String, c: P2GoldenSupport.Case, g: General, city: City) {
        val ag = c.after.general
        val tag = "[$command/${c.name}]"
        assertEquals(ag.int("gold"), g.gold, "$tag general.gold")
        if (ag.has("rice")) assertEquals(ag.int("rice"), g.rice, "$tag general.rice")
        if (ag.has("crew")) assertEquals(ag.int("crew"), g.crew, "$tag general.crew")
        if (ag.has("train")) assertEquals(ag.double("train"), g.train, 1e-9, "$tag general.train")
        if (ag.has("atmos")) assertEquals(ag.double("atmos"), g.atmos, 1e-9, "$tag general.atmos")
        assertEquals(ag.int("experience"), phpRound(g.experience), "$tag general.experience")
        assertEquals(ag.int("dedication"), phpRound(g.dedication), "$tag general.dedication")
        assertEquals(ag.int("city"), g.cityId, "$tag general.city (이동)")
        // city pop (소집해제/모병/징병 mutate pop); the golden dumps it.
        if (c.after.city.has("pop")) assertEquals(c.after.city.int("pop"), city.population, "$tag city.pop")
    }
}

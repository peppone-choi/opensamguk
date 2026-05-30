package opensamguk.logic.golden

import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME — Task GR2 founding-family byte gate (che_거병).
 *
 * The CANONICAL created-set command (INSERTs a wandering nation + diplomacy pair per nation + 24
 * nation_turn rows, then JOINs the actor). Drives the REAL resolver at the exact seed; byte-matches the
 * acting log + the global broadcast + the actor JOIN after-state (nation 3 / officer_level 12 / exp+100 /
 * ded+100). The created-set (nation/diplomacy/nation_turn) is structurally asserted (the byte-comparable
 * row+jsonb flush is the GATE-SATELLITE seam).
 *
 * Adapter-preloaded inputs the resolver does not query (engine data): newNationId (the install assigns 3
 * — the golden actor lands at nation 3), existingNationIds {1,2}, existingNationNames {후한,황건적},
 * scenario 1010.
 */
class FoundingGoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `geobyeong byte-matches the PHP golden action log broadcast and join`() {
        val f = P2GoldenSupport.load("che_거병")
        val c = f.cases.first()
        val def = registry.resolve("che_거병")
        val g = P2GoldenSupport.generalFrom(c.generalId, c.before.general,
            extraMeta = linkedMapOf("dedlevel" to 0))
        // actor is neutral (nation 0); nationFrom yields a level-0 placeholder — 거병 ignores it (INSERTs new).
        val draft = GeneralActionDraft(g, P2GoldenSupport.cityFrom(c.cityId, c.before.city), null)
        val args = mapOf(
            "newNationId" to 3,
            "existingNationIds" to listOf(1, 2),
            "existingNationNames" to setOf("후한", "황건적"),
            "scenario" to 1010,
            "relYear" to 0,
        )
        val ctx = P2GoldenSupport.ctxFor(f, c, draft, def.rawClassName, args = args,
            generalName = P2GoldenSupport.nameOf(c.generalId))
        def.resolve(ctx)

        assertEquals(c.logLines, ctx.logs(), "[거병] action-log")
        assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[거병] broadcast")
        // JOIN after-state.
        assertEquals(c.after.general.int("nation"), draft.general.nationId, "[거병] joined nation")
        assertEquals(c.after.general.int("officer_level"), draft.general.officerLevel, "[거병] officer_level")
        assertEquals(c.after.general.int("experience"), phpRound(draft.general.experience), "[거병] exp")
        assertEquals(c.after.general.int("dedication"), phpRound(draft.general.dedication), "[거병] ded")
        // created-set (structural): 1 nation, 4 diplomacy (2 per other nation), 24 nation_turn.
        assertEquals(1, draft.createdNations.size, "[거병] created nation")
        assertEquals(4, draft.createdDiplomacy.size, "[거병] created diplomacy (2 per other nation)")
        assertEquals(24, draft.createdNationTurns.size, "[거병] created nation_turn rows")
    }
}

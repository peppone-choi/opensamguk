package opensamguk.logic.golden

import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME — Task GR2 personnel-family byte gate.
 *
 * che_등용 (SEND-only scout letter: send log + self exp100/ded200/gold cost) and che_하야 (resign:
 * actor MONTH log + global broadcast). Both drive the REAL resolver at the exact seed.
 */
class PersonnelGoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `scout-letter send byte-matches the PHP golden`() {
        val f = P2GoldenSupport.load("che_등용")
        val c = f.cases.first()
        val def = registry.resolve("che_등용")
        val g = P2GoldenSupport.generalFrom(c.generalId, c.before.general,
            extraMeta = linkedMapOf("leadership_exp" to 0.0, "dedlevel" to 0))
        val nation = P2GoldenSupport.nationFrom(c, gold = 100000, rice = 100000)
        val destId = (c.arg!!["destGeneralID"] as Number).toInt()
        val draft = GeneralActionDraft(g, P2GoldenSupport.cityFrom(c.cityId, c.before.city), nation).also {
            // dest general gid 11 (공손범, neutral) — only its meta name + exp/ded feed the cost.
            // 공손범 install exp/ded = age*100 (birth 158, year 181 → age 23 → 2300/2300); the cost
            // round(develCost + (exp+ded)/1000)*10 = round(20+4.6)*10 = 250 pins this.
            it.destGeneral = General(
                id = destId, nationId = 0, cityId = 32,
                leadership = 61, strength = 67, intel = 61, injury = 0,
                experience = 2300.0, dedication = 2300.0, officerLevel = 0,
                gold = 1000, rice = 1000, meta = linkedMapOf("name" to P2GoldenSupport.nameOf(destId)))
        }
        val ctx = P2GoldenSupport.ctxFor(f, c, draft, def.rawClassName)
        def.resolve(ctx)
        assertEquals(c.logLines, ctx.logs(), "[등용] action-log")
        assertEquals(c.after.general.int("gold"), draft.general.gold, "[등용] gold")
        assertEquals(c.after.general.int("experience"), phpRound(draft.general.experience), "[등용] exp")
        assertEquals(c.after.general.int("dedication"), phpRound(draft.general.dedication), "[등용] ded")
    }

    @Test
    fun `resign byte-matches the PHP golden action log and broadcast`() {
        val f = P2GoldenSupport.load("che_하야")
        val c = f.cases.first()
        val def = registry.resolve("che_하야")
        val g = P2GoldenSupport.generalFrom(c.generalId, c.before.general,
            extraMeta = linkedMapOf("betray" to 0, "dedlevel" to 0))
        // 하야 reads nation.name + nation.gennum (gennum drop); nation 2 황건적.
        val nation = P2GoldenSupport.nationFrom(c, gold = 10000, rice = 10000).copy(gennum = 50)
        val draft = GeneralActionDraft(g, P2GoldenSupport.cityFrom(c.cityId, c.before.city), nation)
        val ctx = P2GoldenSupport.ctxFor(f, c, draft, def.rawClassName, generalName = P2GoldenSupport.nameOf(c.generalId))
        def.resolve(ctx)
        assertEquals(c.logLines, ctx.logs(), "[하야] action-log")
        assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[하야] broadcast")
        // resign zeroes nation/officer; exp/ded unchanged (betray 0 → factor 1).
        assertEquals(0, draft.general.nationId, "[하야] nation reset")
        assertEquals(c.after.general.int("experience"), phpRound(draft.general.experience), "[하야] exp")
    }
}

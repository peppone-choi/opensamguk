package opensamguk.logic.golden

import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.develop.CheGunryangMaemae
import opensamguk.logic.actions.trade.CheJangbiMaemae
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME — Task GR2 trade-family byte gate (증여 dual log / 헌납 nation treasury / 장비매매
 * buy / 군량매매 buy-tax). Drives the REAL resolver at the exact seed; byte-matches acting log +
 * dest log (증여) + general/nation after-state.
 *
 * 군량매매/장비매매 take a PARSED arg via [CheGunryangMaemae.withArg]/[CheJangbiMaemae.withArg] (the
 * registry default leaves the arg unbound). city.trade is not in the GR1 city dump; the captured tax
 * (200→202 spent for amount 200) pins tradeRate 1.0 (city.trade 100).
 */
class TradeGoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `gift and tribute byte-match the PHP golden`() {
        for (command in listOf("che_증여", "che_헌납")) {
            val f = P2GoldenSupport.load(command)
            val def = registry.resolve(command)
            for (c in f.cases) {
                val g = P2GoldenSupport.generalFrom(c.generalId, c.before.general,
                    extraMeta = linkedMapOf("leadership_exp" to 0.0)).copy(
                    lastTurn = opensamguk.logic.domain.LastTurn(command = def.name, arg = c.arg))
                // nation treasury (헌납 credits it); large balance so no clamp.
                val nation = P2GoldenSupport.nationFrom(c, gold = 100000, rice = 100000)
                val draft = GeneralActionDraft(g, P2GoldenSupport.cityFrom(c.cityId, c.before.city), nation)
                // 증여 needs a dest general (gid 9 공도) with its meta name; mutate gold/rice.
                c.destGenerals.firstOrNull()?.let { dg ->
                    draft.destGeneral = General(
                        id = dg.generalId, nationId = c.before.general.int("nation"),
                        cityId = dg.after.general.int("city"),
                        leadership = P2GoldenSupport.RAW_STATS.getValue(dg.generalId).leadership,
                        strength = P2GoldenSupport.RAW_STATS.getValue(dg.generalId).strength,
                        intel = P2GoldenSupport.RAW_STATS.getValue(dg.generalId).intel,
                        injury = 0, experience = 0.0, dedication = 0.0, officerLevel = 1,
                        // dest before gold/rice = after minus the gift (200) — reconstruct from after.
                        gold = dg.after.general.int("gold") - (if (c.arg?.get("isGold") == true) 200 else 0),
                        rice = (dg.after.general.intOrNull("rice") ?: 0) - (if (c.arg?.get("isGold") == false) 200 else 0),
                        meta = linkedMapOf("name" to P2GoldenSupport.nameOf(dg.generalId)))
                }
                val ctx = P2GoldenSupport.ctxFor(f, c, draft, def.rawClassName)
                def.resolve(ctx)

                assertEquals(c.logLines, ctx.logs(), "[$command/${c.name}] action-log")
                assertEquals(c.after.general.int("gold"), draft.general.gold, "[$command/${c.name}] actor gold")
                assertEquals(phpRound(draft.general.experience), c.after.general.int("experience"), "[$command/${c.name}] exp")
                assertEquals(phpRound(draft.general.dedication), c.after.general.int("dedication"), "[$command/${c.name}] ded")
                // 증여 dest log (PLAIN, dest bucket) + dest gold/rice.
                c.destGenerals.firstOrNull()?.let { dg ->
                    assertEquals(dg.logLines, ctx.logsTo(dg.generalId), "[$command/${c.name}] dest log")
                    assertEquals(dg.after.general.int("gold"), draft.destGeneral!!.gold, "[$command/${c.name}] dest gold")
                }
            }
        }
    }

    @Test
    fun `gunryang maemae buy-tax byte-matches the PHP golden`() {
        val f = P2GoldenSupport.load("che_군량매매")
        val c = f.cases.first()
        @Suppress("UNCHECKED_CAST")
        val parsed = c.arg!! as Map<String, Any?>
        val def = (registry.resolve("che_군량매매") as CheGunryangMaemae).withArg(parsed)
        val g = P2GoldenSupport.generalFrom(c.generalId, c.before.general)
        // tradeRate 1.0 → city.trade = 100 (the captured 200→202 tax pins it).
        val city = P2GoldenSupport.cityFrom(c.cityId, c.before.city).copy(trade = 100)
        val nation = P2GoldenSupport.nationFrom(c, gold = 100000, rice = 100000)
        val draft = GeneralActionDraft(g, city, nation)
        val ctx = P2GoldenSupport.ctxFor(f, c, draft, def.rawClassName, args = parsed)
        def.resolveWith(ctx, parsed)
        assertEquals(c.logLines, ctx.logs(), "[군량매매] action-log")
        assertEquals(c.after.general.int("gold"), draft.general.gold, "[군량매매] gold")
        assertEquals(c.after.general.int("rice"), draft.general.rice, "[군량매매] rice")
        assertEquals(phpRound(draft.general.experience), c.after.general.int("experience"), "[군량매매] exp")
        assertEquals(phpRound(draft.general.dedication), c.after.general.int("dedication"), "[군량매매] ded")
    }

    @Test
    fun `jangbi maemae buy byte-matches the PHP golden`() {
        val f = P2GoldenSupport.load("che_장비매매")
        val c = f.cases.first()
        @Suppress("UNCHECKED_CAST")
        val parsed = c.arg!! as Map<String, Any?>
        val def = (registry.resolve("che_장비매매") as CheJangbiMaemae).withArg(parsed)
        val g = P2GoldenSupport.generalFrom(c.generalId, c.before.general)
        val nation = P2GoldenSupport.nationFrom(c, gold = 100000, rice = 100000)
        val draft = GeneralActionDraft(g, P2GoldenSupport.cityFrom(c.cityId, c.before.city), nation)
        val ctx = P2GoldenSupport.ctxFor(f, c, draft, def.rawClassName, args = parsed)
        def.resolveWith(ctx, parsed)
        assertEquals(c.logLines, ctx.logs(), "[장비매매] action-log")
        assertEquals(c.after.general.int("gold"), draft.general.gold, "[장비매매] gold")
        assertEquals(phpRound(draft.general.experience), c.after.general.int("experience"), "[장비매매] exp")
    }
}

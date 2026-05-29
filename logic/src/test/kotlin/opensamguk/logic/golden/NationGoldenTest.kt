package opensamguk.logic.golden

import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME — Task GR2 nation-internal-family byte gate (발령/포상/국호변경/국기변경/천도).
 *
 * These are `nationCommand`-scope commands (the seed 2nd token = "nationCommand"); the actor is gid 152
 * (하진, 후한 chief). 발령/포상 mutate a DEST general (gid 14 공융) + write a dest log; 천도 reads the
 * threaded cityDistance (golden exp +45 = 5*(distance*2+1) → distance 4). 국기변경 colorType 1 → #800000.
 */
class NationGoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `nation-internal commands byte-match the PHP golden`() {
        // command -> resolved cityDistance (천도 only) and dest-general id (발령/포상).
        val cityDistance = mapOf("che_천도" to 4)
        for (command in listOf("che_발령", "che_포상", "che_국호변경", "che_국기변경", "che_천도")) {
            val f = P2GoldenSupport.load(command)
            val def = registry.resolve(command)
            assertEquals(command, def.key, "$command registry key")
            for (c in f.cases) {
                val g = P2GoldenSupport.generalFrom(c.generalId, c.before.general,
                    extraMeta = linkedMapOf("leadership_exp" to 0.0, "dedlevel" to 0))
                // nation aux for the rename gates (resolve reads no aux gate, but set it anyway) + treasury.
                val nation = P2GoldenSupport.nationFrom(c, gold = 100000, rice = 100000)
                val draft = GeneralActionDraft(g, P2GoldenSupport.cityFrom(c.cityId, c.before.city), nation)
                var destName = ""
                c.destGenerals.firstOrNull()?.let { dg ->
                    val rs = P2GoldenSupport.RAW_STATS.getValue(dg.generalId)
                    draft.destGeneral = General(
                        id = dg.generalId, nationId = c.before.general.int("nation"),
                        cityId = dg.after.general.int("city"),
                        leadership = rs.leadership, strength = rs.strength, intel = rs.intel, injury = 0,
                        experience = dg.after.general.double("experience"),
                        dedication = dg.after.general.double("dedication"),
                        officerLevel = dg.after.general.int("officer_level"),
                        gold = dg.after.general.int("gold") - 200, rice = dg.after.general.intOrNull("rice") ?: 0,
                        meta = linkedMapOf("name" to P2GoldenSupport.nameOf(dg.generalId)))
                    destName = P2GoldenSupport.nameOf(dg.generalId)
                }
                val ctx = P2GoldenSupport.ctxFor(f, c, draft, def.rawClassName,
                    generalName = P2GoldenSupport.nameOf(c.generalId), destGeneralName = destName,
                    cityDistance = cityDistance[command])
                def.resolve(ctx)

                assertEquals(c.logLines, ctx.logs(), "[$command/${c.name}] action-log")
                assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[$command/${c.name}] broadcast")
                assertEquals(c.after.general.int("experience"), phpRound(draft.general.experience), "[$command/${c.name}] exp")
                assertEquals(c.after.general.int("dedication"), phpRound(draft.general.dedication), "[$command/${c.name}] ded")
                // dest general log. 발령 uses addLogTo (MONTH body, no prefix) → the DEST logger wraps it
                // with `<C>●</>{month}월:` at flush, so the golden dest line = prefix + body. 포상 uses
                // addPlainLogTo (already `<C>●</>` prefixed, no month).
                c.destGenerals.firstOrNull()?.let { dg ->
                    val monthDest = ctx.logsTo(dg.generalId).map { "<C>●</>${c.env.month}월:$it" }
                    val plainDest = ctx.plainLogsTo(dg.generalId)
                    assertEquals(dg.logLines, monthDest + plainDest, "[$command/${c.name}] dest log")
                }
            }
        }
    }
}

package opensamguk.engine.campaign

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.engine.run.MonthlyPostUpdateHook
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.Retainer
import opensamguk.logic.diplomacy.DiplomacyConst
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.domestic.*
import opensamguk.logic.input.*
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.*

class DeclareWarTransitionTest {
    @Test fun `hwiha declaration becomes bilateral war only at the policy month boundary`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val phase = Phase(200, 1, 1)
        val order = PlacementOrder("envoy", 501, 51, PlacementPost.ENVOY, PlacementTarget.Nation(2), phase)
        val ruler = fixture.person(501, 1, route.startCity, userId = "42")
        val envoy = fixture.person(502, 1, route.startCity, lord = false).let {
            it.copy(meta = it.meta + (PlacementState.META_KEY to
                PlacementState(ActivePlacement(order, phase, phase), null).toMetaValue()))
        }
        val world = fixture.world(listOf(ruler to route.start, envoy to route.start),
            nations = listOf(Nation(1, "아국", "#111111", level = 2),
                Nation(2, "상대국", "#222222", level = 2)),
            retainers = listOf(Retainer(51, 501, "TEST", 502, envoy.name, "lieutenant")))
        world.updateDiplomacy(1, 2, DiplomacyState.TRADE, 0)
        world.updateDiplomacy(2, 1, DiplomacyState.TRADE, 0)
        val recorder = ChangeRecorder()
        assertEquals(RuleProfile.HWIHA, world.ruleProfile)
        // Isolate the real monthly hook from catalog delivery, which the handler test covers.
        assertNull(CourtActionExecutor(world, recorder, DomesticContext())
            .execute(501, DiplomacyInput.DECLARE_WAR, """{"targetNationId":2}"""))
        val hook = MonthlyPostUpdateHook(world, recorder, GeneralActionPipeline())
        val term = DeclareWarPolicy.DECLARATION_TERM_MONTHS
        for (elapsed in 0..term) {
            for ((from, to) in listOf(1 to 2, 2 to 1)) {
                val relation = assertNotNull(world.getDiplomacy(from, to))
                assertEquals(if (elapsed < term) DiplomacyState.DECLARATION else DiplomacyState.WAR,
                    relation.state, "direction $from->$to, elapsed=$elapsed")
                assertEquals(if (elapsed < term) term - elapsed else DiplomacyConst.DEFAULT_WAR_TERM,
                    relation.term, "direction $from->$to, elapsed=$elapsed")
            }
            if (elapsed == term) break
            val next = elapsed + 1
            world.setCurrentDate(200 + next / 12, next % 12 + 1, 1)
            hook.run(RandUtil(LiteHashDrbg("declare-war-month-$next")))
        }
        assertEquals(setOf(1 to 2, 2 to 1),
            recorder.diplomacyUpdateDirty().map { it.fromNationId to it.toNationId }.toSet())
    }
}

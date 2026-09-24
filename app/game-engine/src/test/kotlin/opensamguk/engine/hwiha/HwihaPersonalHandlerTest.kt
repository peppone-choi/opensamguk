package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.input.*

class HwihaPersonalHandlerTest {
    private val fixture = HwihaCampaignWorldFixture()
    private val ready = HwihaPersonalDesign.CANON.copy(status = HwihaPersonalDesign.CONFIRMED)

    @Test fun `self training raises the selected stat and fatigue exactly once`() {
        val route = fixture.route()
        val actor = fixture.person(901, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(actor to route.start))
        val handler = HwihaPersonalHandler(world, ChangeRecorder(), HwihaDomesticContext(), ready)
        val args = """{"stat":"strength"}"""
        val applied = assertIs<HwihaTurnOutcome.Applied>(handler.handle(HwihaPersonalInput.SELF_TRAIN,
            actor.id, args, "train-901", 42))
        val after = world.getGeneralById(actor.id)!!
        assertEquals(actor.stats.strength + ready.trainingStatGain, after.stats.strength)
        assertEquals(actor.stats.leadership, after.stats.leadership)
        assertEquals(ready.trainingFatigueGain, HwihaPersonalTravelCondition.read(after.meta)!!.fatigue)
        assertEquals(ready.experiencePerAction, after.experience)
        assertEquals(applied, handler.handle(HwihaPersonalInput.SELF_TRAIN, actor.id, args, "train-901", 42))
        assertEquals(after, world.getGeneralById(actor.id))
    }

    @Test fun `recuperation reduces injury and fatigue and rejects an already healthy actor`() {
        val route = fixture.route()
        val actor = fixture.person(902, 1, route.startCity, userId = "42").copy(injury = 20,
            meta = fixture.person(902, 1, route.startCity, userId = "42").meta +
                (HwihaPersonalTravelCondition.META_KEY to HwihaPersonalTravelCondition(15, 60).toMetaValue()))
        val world = fixture.world(listOf(actor to route.start))
        val handler = HwihaPersonalHandler(world, ChangeRecorder(), HwihaDomesticContext(), ready)
        assertIs<HwihaTurnOutcome.Applied>(handler.handle(HwihaPersonalInput.RECUPERATE,
            actor.id, "{}", "heal-902", 42))
        val after = world.getGeneralById(actor.id)!!
        assertEquals(10, after.injury)
        assertEquals(5, HwihaPersonalTravelCondition.read(after.meta)!!.fatigue)
        assertEquals(60, HwihaPersonalTravelCondition.read(after.meta)!!.morale)
        val healthy = fixture.person(903, 1, route.startCity, userId = "42")
        val healthyWorld = fixture.world(listOf(healthy to route.start))
        assertEquals(HwihaPersonalFailure.ALREADY_HEALTHY.name,
            assertIs<HwihaTurnOutcome.Rejected>(HwihaPersonalHandler(healthyWorld, ChangeRecorder(),
                HwihaDomesticContext(), ready).handle(HwihaPersonalInput.RECUPERATE, healthy.id,
                "{}", "heal-903", 42)).code)
    }

    @Test fun `proposed numeric design blocks travel before mutation`() {
        val route = fixture.route()
        val actor = fixture.person(904, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(actor to route.start))
        val result = HwihaPersonalHandler(world, ChangeRecorder(), HwihaDomesticContext(),
            ready.copy(status = "PROPOSED")).handle(
            HwihaPersonalInput.TRAVEL, actor.id, "{}", "travel-904", 42)
        assertEquals(InputRejection.NOT_DELIVERED.name, assertIs<HwihaTurnOutcome.Rejected>(result).code)
        assertEquals(actor, world.getGeneralById(actor.id))
    }
}

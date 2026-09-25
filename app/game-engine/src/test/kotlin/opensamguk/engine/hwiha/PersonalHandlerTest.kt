package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.input.*

class PersonalHandlerTest {
    private val fixture = CampaignWorldFixture()
    private val ready = PersonalDesign.CANON.copy(status = PersonalDesign.CONFIRMED)

    @Test fun `self training raises the selected stat and fatigue exactly once`() {
        val route = fixture.route()
        val actor = fixture.person(901, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(actor to route.start))
        val handler = PersonalHandler(world, ChangeRecorder(), DomesticContext(), ready)
        val args = """{"stat":"strength"}"""
        val applied = assertIs<TurnOutcome.Applied>(handler.handle(PersonalInput.SELF_TRAIN,
            actor.id, args, "train-901", 42))
        val after = world.getGeneralById(actor.id)!!
        assertEquals(actor.stats.strength + ready.trainingStatGain, after.stats.strength)
        assertEquals(actor.stats.leadership, after.stats.leadership)
        assertEquals(ready.trainingFatigueGain, PersonalTravelCondition.read(after.meta)!!.fatigue)
        assertEquals(actor.experience, after.experience)
        assertEquals(actor.dedication, after.dedication)
        assertEquals(applied, handler.handle(PersonalInput.SELF_TRAIN, actor.id, args, "train-901", 42))
        assertEquals(after, world.getGeneralById(actor.id))
    }

    @Test fun `recuperation reduces injury and fatigue and rejects an already healthy actor`() {
        val route = fixture.route()
        val actor = fixture.person(902, 1, route.startCity, userId = "42").copy(injury = 20,
            meta = fixture.person(902, 1, route.startCity, userId = "42").meta +
                (PersonalTravelCondition.META_KEY to PersonalTravelCondition(15, 60).toMetaValue()))
        val world = fixture.world(listOf(actor to route.start))
        val handler = PersonalHandler(world, ChangeRecorder(), DomesticContext(), ready)
        assertIs<TurnOutcome.Applied>(handler.handle(PersonalInput.RECUPERATE,
            actor.id, "{}", "heal-902", 42))
        val after = world.getGeneralById(actor.id)!!
        assertEquals(10, after.injury)
        assertEquals(5, PersonalTravelCondition.read(after.meta)!!.fatigue)
        assertEquals(60, PersonalTravelCondition.read(after.meta)!!.morale)
        assertEquals(actor.experience, after.experience)
        assertEquals(actor.dedication, after.dedication)
        val healthy = fixture.person(903, 1, route.startCity, userId = "42")
        val healthyWorld = fixture.world(listOf(healthy to route.start))
        assertEquals(PersonalFailure.ALREADY_HEALTHY.name,
            assertIs<TurnOutcome.Rejected>(PersonalHandler(healthyWorld, ChangeRecorder(),
                DomesticContext(), ready).handle(PersonalInput.RECUPERATE, healthy.id,
                "{}", "heal-903", 42)).code)
    }

    @Test fun `proposed numeric design blocks travel before mutation`() {
        val route = fixture.route()
        val actor = fixture.person(904, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(actor to route.start))
        val result = PersonalHandler(world, ChangeRecorder(), DomesticContext(),
            ready.copy(status = "PROPOSED")).handle(
            PersonalInput.TRAVEL, actor.id, "{}", "travel-904", 42)
        assertEquals(InputRejection.NOT_DELIVERED.name, assertIs<TurnOutcome.Rejected>(result).code)
        assertEquals(actor, world.getGeneralById(actor.id))
    }
}

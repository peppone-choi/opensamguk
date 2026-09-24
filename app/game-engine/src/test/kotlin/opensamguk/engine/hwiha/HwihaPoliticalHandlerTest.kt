package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Retainer
import opensamguk.engine.turn.Nation
import opensamguk.logic.input.*

class HwihaPoliticalHandlerTest {
    private val fixture = HwihaCampaignWorldFixture()

    @Test fun `rise requires fifty renown and creates a county holding nation`() {
        val route = fixture.route()
        val base = fixture.person(1011, 0, route.startCity, userId = "42", lord = false)
        val policy = HwihaPersonPolicyState(50, true, "test", "1", base.id)
        val actor = base.copy(meta = base.meta + (HwihaPersonPolicyState.META_KEY to policy.toMetaValue()))
        val world = fixture.world(listOf(actor to route.start))
        val handler = HwihaPoliticalHandler(world, ChangeRecorder(), HwihaDomesticContext())
        val result = assertIs<HwihaTurnOutcome.Applied>(handler.handle(HwihaPoliticalInput.RISE,
            actor.id, "{}", "rise-1011", 42))
        val newNation = world.listNations().single { it.id !in setOf(1, 2) }
        assertEquals(newNation.id, world.getGeneralById(actor.id)!!.nationId)
        assertEquals(newNation.id, world.getCityById(route.startCity)!!.nationId)
        assertTrue(HwihaLordStatus.read(world.getGeneralById(actor.id)!!.meta))
        assertEquals(result, handler.handle(HwihaPoliticalInput.RISE, actor.id, "{}", "rise-1011", 42))
    }

    @Test fun `resignation takes the direct retinue out of its former nation`() {
        val route = fixture.route()
        val actor = fixture.person(1021, 1, route.startCity, userId = "42", lord = false)
        val follower = fixture.person(1022, 1, route.startCity, lord = false)
        val world = fixture.world(listOf(actor to route.start, follower to route.start),
            retainers = listOf(Retainer(31, actor.id, "EXISTING", follower.id, follower.name, "guest")))
        assertIs<HwihaTurnOutcome.Applied>(HwihaPoliticalHandler(world, ChangeRecorder(), HwihaDomesticContext())
            .handle(HwihaPoliticalInput.RESIGN, actor.id, "{}", "resign-1021", 42))
        assertEquals(0, world.getGeneralById(actor.id)!!.nationId)
        assertEquals(0, world.getGeneralById(follower.id)!!.nationId)
        assertEquals(actor.id, world.listRetainers().single().masterGeneralId)
    }

    @Test fun `dissolution clears nation and its counties`() {
        val route = fixture.route()
        val actor = fixture.person(1031, 1, route.startCity, userId = "42", lord = true)
        val world = fixture.world(listOf(actor to route.start),
            cityChanges = { city -> if (city.id == route.startCity) city.copy(nationId = 1) else city })
        assertIs<HwihaTurnOutcome.Applied>(HwihaPoliticalHandler(world, ChangeRecorder(), HwihaDomesticContext())
            .handle(HwihaPoliticalInput.DISSOLVE, actor.id, "{}", "dissolve-1031", 42))
        assertNull(world.getNationById(1))
        assertEquals(0, world.getCityById(route.startCity)!!.nationId)
        assertEquals(0, world.getGeneralById(actor.id)!!.nationId)
    }

    @Test fun `founding promotes an existing lord's unestablished nation once`() {
        val route = fixture.route()
        val actor = fixture.person(1041, 1, route.startCity, userId = "42", lord = true)
        val world = fixture.world(listOf(actor to route.start),
            nations = listOf(Nation(1, "N1", "#111111", capitalCityId = route.startCity), Nation(2, "N2", "#222222")))
        val handler = HwihaPoliticalHandler(world, ChangeRecorder(), HwihaDomesticContext())
        assertIs<HwihaTurnOutcome.Applied>(handler.handle(HwihaPoliticalInput.FOUND_STATE, actor.id, "{}", "found-1041", 42))
        assertEquals(1, world.getNationById(1)!!.level)
        assertEquals(HwihaPoliticalFailure.ALREADY_PROCESSED.name,
            assertIs<HwihaTurnOutcome.Rejected>(handler.handle(HwihaPoliticalInput.FOUND_STATE,
                actor.id, "{}", "found-again", 42)).code)
    }
}

package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Retainer
import opensamguk.engine.turn.Nation
import opensamguk.common.wire.TurnDaemonCommand
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

    @Test fun `successor directly accepts before abdication transfers the lord and nation chief`() {
        val route = fixture.route()
        val ruler = fixture.person(1051, 1, route.startCity, userId = "42", lord = true)
        val successor = fixture.person(1052, 1, route.startCity, userId = "43", lord = false)
        val world = fixture.world(listOf(ruler to route.start, successor to route.start),
            nations = listOf(Nation(1, "N1", "#111111", capitalCityId = route.startCity, chiefGeneralId = ruler.id),
                Nation(2, "N2", "#222222")))
        val political = HwihaPoliticalHandler(world, ChangeRecorder(), HwihaDomesticContext())
        val args = """{"targetGeneralId":${successor.id}}"""
        assertEquals(HwihaPoliticalFailure.CONSENT_REQUIRED.name,
            assertIs<HwihaTurnOutcome.Rejected>(political.handle(HwihaPoliticalInput.ABDICATE,
                ruler.id, args, "abdicate-denied", 42)).code)
        val reply = HwihaCourtHandler(world, ChangeRecorder()).handle(TurnDaemonCommand.HwihaCourtInput(
            "accept-1052", successor.id, 43, HwihaPoliticalConsent.COURT_INPUT_ID,
            """{"issuerGeneralId":${ruler.id},"inputId":"action.abdicate","accepted":true}"""))
        assertTrue(reply.ok)
        assertIs<HwihaTurnOutcome.Applied>(political.handle(HwihaPoliticalInput.ABDICATE,
            ruler.id, args, "abdicate-1051", 42))
        assertFalse(HwihaLordStatus.read(world.getGeneralById(ruler.id)!!.meta))
        assertTrue(HwihaLordStatus.read(world.getGeneralById(successor.id)!!.meta))
        assertEquals(successor.id, world.getNationById(1)!!.chiefGeneralId)
        assertNull(HwihaPoliticalConsent.read(world.getGeneralById(successor.id)!!.meta))
    }

    @Test fun `oath needs an explicit acceptance and stores a symmetric bond`() {
        val route = fixture.route()
        val actor = fixture.person(1061, 1, route.startCity, userId = "42", lord = false)
        val target = fixture.person(1062, 2, route.startCity, userId = "43", lord = false)
        val world = fixture.world(listOf(actor to route.start, target to route.start))
        val political = HwihaPoliticalHandler(world, ChangeRecorder(), HwihaDomesticContext())
        val args = """{"targetGeneralId":${target.id}}"""
        val refused = HwihaCourtHandler(world, ChangeRecorder()).handle(TurnDaemonCommand.HwihaCourtInput(
            "refuse-1062", target.id, 43, HwihaPoliticalConsent.COURT_INPUT_ID,
            """{"issuerGeneralId":${actor.id},"inputId":"action.oath","accepted":false}"""))
        assertTrue(refused.ok)
        assertEquals(HwihaPoliticalFailure.CONSENT_DECLINED.name,
            assertIs<HwihaTurnOutcome.Rejected>(political.handle(HwihaPoliticalInput.OATH,
                actor.id, args, "oath-refused", 42)).code)
        val accepted = HwihaCourtHandler(world, ChangeRecorder()).handle(TurnDaemonCommand.HwihaCourtInput(
            "accept-1062", target.id, 43, HwihaPoliticalConsent.COURT_INPUT_ID,
            """{"issuerGeneralId":${actor.id},"inputId":"action.oath","accepted":true}"""))
        assertTrue(accepted.ok)
        assertIs<HwihaTurnOutcome.Applied>(political.handle(HwihaPoliticalInput.OATH,
            actor.id, args, "oath-1061", 42))
        assertEquals(setOf(target.id), HwihaOathBonds.read(world.getGeneralById(actor.id)!!.meta))
        assertEquals(setOf(actor.id), HwihaOathBonds.read(world.getGeneralById(target.id)!!.meta))
    }
}

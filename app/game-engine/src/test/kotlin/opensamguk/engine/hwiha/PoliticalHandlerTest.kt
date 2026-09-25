package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Retainer
import opensamguk.engine.turn.Nation
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.logic.input.*

class PoliticalHandlerTest {
    private val fixture = CampaignWorldFixture()

    @Test fun `rise waits for a complete nation transition`() {
        val route = fixture.route()
        val base = fixture.person(1011, 0, route.startCity, userId = "42", lord = false)
        val policy = PersonPolicyState(50, true, "test", "1", base.id)
        val actor = base.copy(meta = base.meta + (PersonPolicyState.META_KEY to policy.toMetaValue()))
        val world = fixture.world(listOf(actor to route.start))
        val handler = PoliticalHandler(world, ChangeRecorder(), DomesticContext())
        val result = assertIs<TurnOutcome.Rejected>(handler.handle(PoliticalInput.RISE,
            actor.id, "{}", "rise-1011", 42))
        assertEquals(InputRejection.NOT_DELIVERED.name, result.code)
        assertEquals(actor, world.getGeneralById(actor.id))
        assertEquals(2, world.listNations().size)
    }

    @Test fun `resignation waits for military and appointment cleanup`() {
        val route = fixture.route()
        val actor = fixture.person(1021, 1, route.startCity, userId = "42", lord = false)
        val follower = fixture.person(1022, 1, route.startCity, lord = false)
        val world = fixture.world(listOf(actor to route.start, follower to route.start),
            retainers = listOf(Retainer(31, actor.id, "EXISTING", follower.id, follower.name, "guest")))
        assertEquals(InputRejection.NOT_DELIVERED.name,
            assertIs<TurnOutcome.Rejected>(PoliticalHandler(world, ChangeRecorder(), DomesticContext())
                .handle(PoliticalInput.RESIGN, actor.id, "{}", "resign-1021", 42)).code)
        assertEquals(1, world.getGeneralById(actor.id)!!.nationId)
        assertEquals(1, world.getGeneralById(follower.id)!!.nationId)
        assertEquals(actor.id, world.listRetainers().single().masterGeneralId)
    }

    @Test fun `dissolution waits for the shared nation deletion path`() {
        val route = fixture.route()
        val actor = fixture.person(1031, 1, route.startCity, userId = "42", lord = true)
        val world = fixture.world(listOf(actor to route.start),
            cityChanges = { city -> if (city.id == route.startCity) city.copy(nationId = 1) else city })
        assertEquals(InputRejection.NOT_DELIVERED.name,
            assertIs<TurnOutcome.Rejected>(PoliticalHandler(world, ChangeRecorder(), DomesticContext())
                .handle(PoliticalInput.DISSOLVE, actor.id, "{}", "dissolve-1031", 42)).code)
        assertNotNull(world.getNationById(1))
        assertEquals(1, world.getCityById(route.startCity)!!.nationId)
        assertEquals(1, world.getGeneralById(actor.id)!!.nationId)
    }

    @Test fun `founding promotes an existing lord's unestablished nation once`() {
        val route = fixture.route()
        val actor = fixture.person(1041, 1, route.startCity, userId = "42", lord = true)
        val world = fixture.world(listOf(actor to route.start),
            nations = listOf(Nation(1, "N1", "#111111", capitalCityId = route.startCity), Nation(2, "N2", "#222222")))
        val handler = PoliticalHandler(world, ChangeRecorder(), DomesticContext())
        assertIs<TurnOutcome.Applied>(handler.handle(PoliticalInput.FOUND_STATE, actor.id, "{}", "found-1041", 42))
        assertEquals(1, world.getNationById(1)!!.level)
        assertEquals(PoliticalFailure.ALREADY_PROCESSED.name,
            assertIs<TurnOutcome.Rejected>(handler.handle(PoliticalInput.FOUND_STATE,
                actor.id, "{}", "found-again", 42)).code)
    }

    @Test fun `successor directly accepts before abdication transfers the lord and nation chief`() {
        val route = fixture.route()
        val ruler = fixture.person(1051, 1, route.startCity, userId = "42", lord = true)
        val successor = fixture.person(1052, 1, route.startCity, userId = "43", lord = false)
        val world = fixture.world(listOf(ruler to route.start, successor to route.start),
            nations = listOf(Nation(1, "N1", "#111111", capitalCityId = route.startCity, chiefGeneralId = ruler.id),
                Nation(2, "N2", "#222222")))
        val political = PoliticalHandler(world, ChangeRecorder(), DomesticContext())
        val args = """{"targetGeneralId":${successor.id}}"""
        assertEquals(PoliticalFailure.CONSENT_REQUIRED.name,
            assertIs<TurnOutcome.Rejected>(political.handle(PoliticalInput.ABDICATE,
                ruler.id, args, "abdicate-denied", 42)).code)
        val reply = CourtHandler(world, ChangeRecorder()).handle(TurnDaemonCommand.ImmediateInput(
            "accept-1052", successor.id, 43, PoliticalConsent.COURT_INPUT_ID,
            """{"issuerGeneralId":${ruler.id},"inputId":"action.abdicate","accepted":true}"""))
        assertTrue(reply.ok)
        assertIs<TurnOutcome.Applied>(political.handle(PoliticalInput.ABDICATE,
            ruler.id, args, "abdicate-1051", 42))
        assertFalse(LordStatus.read(world.getGeneralById(ruler.id)!!.meta))
        assertTrue(LordStatus.read(world.getGeneralById(successor.id)!!.meta))
        assertEquals(successor.id, world.getNationById(1)!!.chiefGeneralId)
        assertNull(PoliticalConsent.read(world.getGeneralById(successor.id)!!.meta))
    }

    @Test fun `oath needs an explicit acceptance and stores a symmetric bond`() {
        val route = fixture.route()
        val actor = fixture.person(1061, 1, route.startCity, userId = "42", lord = false)
        val target = fixture.person(1062, 2, route.startCity, userId = "43", lord = false)
        val world = fixture.world(listOf(actor to route.start, target to route.start))
        val political = PoliticalHandler(world, ChangeRecorder(), DomesticContext())
        val args = """{"targetGeneralId":${target.id}}"""
        val refused = CourtHandler(world, ChangeRecorder()).handle(TurnDaemonCommand.ImmediateInput(
            "refuse-1062", target.id, 43, PoliticalConsent.COURT_INPUT_ID,
            """{"issuerGeneralId":${actor.id},"inputId":"action.oath","accepted":false}"""))
        assertTrue(refused.ok)
        assertEquals(PoliticalFailure.CONSENT_DECLINED.name,
            assertIs<TurnOutcome.Rejected>(political.handle(PoliticalInput.OATH,
                actor.id, args, "oath-refused", 42)).code)
        val accepted = CourtHandler(world, ChangeRecorder()).handle(TurnDaemonCommand.ImmediateInput(
            "accept-1062", target.id, 43, PoliticalConsent.COURT_INPUT_ID,
            """{"issuerGeneralId":${actor.id},"inputId":"action.oath","accepted":true}"""))
        assertTrue(accepted.ok)
        assertIs<TurnOutcome.Applied>(political.handle(PoliticalInput.OATH,
            actor.id, args, "oath-1061", 42))
        assertEquals(setOf(target.id), OathBonds.read(world.getGeneralById(actor.id)!!.meta))
        assertEquals(setOf(actor.id), OathBonds.read(world.getGeneralById(target.id)!!.meta))
    }
}

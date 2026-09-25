package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Retainer
import opensamguk.logic.input.*

class HwihaMusterHandlerTest {
    private val fixture = HwihaCampaignWorldFixture()

    @Test fun `muster redirects owned lieutenant corps to the owner's live province exactly once`() {
        val route = fixture.route()
        val owner = fixture.person(701, 1, route.startCity, userId = "42")
        val lieutenant = fixture.person(702, 1, route.destinationCounty, userId = null, lord = false)
        val card = Retainer(703, owner.id, "EXISTING", lieutenant.id, lieutenant.name, "lieutenant", hasOwnBugok = true)
        val world = fixture.world(listOf(owner to route.start, lieutenant to route.start),
            bugoks = listOf(fixture.unit(704, owner.id, 100).copy(commanderRetainerId = card.id)),
            retainers = listOf(card), wars = emptyList())
        val recorder = ChangeRecorder()
        val deployed = HwihaDeploymentExecutor(world, recorder, fixture.topology, fixture.metrics)
            .deploy("muster-corps", DeploymentRequest(owner.id, card.id, listOf(704)))
        assertIs<DeploymentExecution.Applied>(deployed)
        recorder.moveGeneral(world, lieutenant.id, route.destination)
        val handler = HwihaMusterHandler(world, recorder, fixture.topology, fixture.metrics,
            MilitaryDesign.CANON.copy(status = MilitaryDesign.CONFIRMED))
        val first = assertIs<HwihaTurnOutcome.Applied>(handler.handle(owner.id, "{}", "muster-request", 42))
        val order = CorpsOrder.read(world.getGeneralById(lieutenant.id)!!.meta, fixture.topology)
        assertEquals(route.start, order?.destination)
        assertEquals(10, world.getGeneralById(owner.id)!!.experience)
        assertEquals(first, handler.handle(owner.id, "{}", "muster-request", 42))
        assertEquals(10, world.getGeneralById(owner.id)!!.experience)
        val denied = assertIs<HwihaTurnOutcome.Rejected>(handler.handle(owner.id, "{}", "different-request", 42))
        assertEquals("ALREADY_PROCESSED", denied.code)
    }

    @Test fun `muster rejects when the owner commands no deployed corps`() {
        val route = fixture.route()
        val owner = fixture.person(705, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(owner to route.start))
        val result = HwihaMusterHandler(world, ChangeRecorder(), fixture.topology, fixture.metrics,
            MilitaryDesign.CANON.copy(status = MilitaryDesign.CONFIRMED))
            .handle(owner.id, "{}", "none", 42)
        assertEquals("NO_COMMANDED_CORPS", assertIs<HwihaTurnOutcome.Rejected>(result).code)
        assertEquals(0, world.getGeneralById(owner.id)!!.experience)
    }

    @Test fun `operational deploy path creates no muster target`() {
        val route = fixture.route()
        val owner = fixture.person(707, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(owner to route.start), bugoks = listOf(fixture.unit(708, owner.id, 1000)),
            cityChanges = { city -> city.copy(nationId = if (city.id == route.destinationCounty) 2 else 1) })
        val recorder = ChangeRecorder()
        val input = DeployInputs.canonicalJson(DeployInput(owner.id, listOf(708), route.destination))
        assertIs<HwihaTurnOutcome.Applied>(HwihaDeployHandler(world, recorder, fixture.topology, fixture.metrics)
            .handle(owner.id, input, "deploy-707", 42))
        val corps = DeploymentState.read(world.getGeneralById(owner.id)!!.meta)!!.corps.single()
        assertEquals(owner.id, corps.commanderGeneralId)
        val result = HwihaMusterHandler(world, recorder, fixture.topology, fixture.metrics)
            .handle(owner.id, "{}", "muster-707", 42)
        assertEquals("NO_GATHER_TARGET", assertIs<HwihaTurnOutcome.Rejected>(result).code)
    }

    @Test fun `proposed military rates cannot execute`() {
        val route = fixture.route()
        val owner = fixture.person(706, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(owner to route.start))
        val result = HwihaMusterHandler(world, ChangeRecorder(), fixture.topology, fixture.metrics,
            MilitaryDesign.CANON.copy(status = "PROPOSED"))
            .handle(owner.id, "{}", "unconfirmed", 42)
        assertEquals("NOT_DELIVERED", assertIs<HwihaTurnOutcome.Rejected>(result).code)
    }
}

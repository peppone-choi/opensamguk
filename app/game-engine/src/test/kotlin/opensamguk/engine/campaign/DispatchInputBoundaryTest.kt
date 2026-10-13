package opensamguk.engine.campaign

import opensamguk.common.wire.TurnDaemonCommand.ImmediateInput
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Retainer
import opensamguk.logic.input.*
import kotlin.test.*

/** Authenticated input boundaries; pending orders are produced by the real issuer turn. */
class DispatchInputBoundaryTest {
    private val fixture = CampaignWorldFixture()
    private val route = fixture.route()

    private fun world(): InMemoryTurnWorld {
        val issuer = fixture.person(1, 1, route.startCity, userId = "42").copy(npcState = 0)
        val target = fixture.person(2, 1, route.startCity, userId = "43", lord = false).copy(npcState = 0)
        val other = fixture.person(3, 1, route.startCity, userId = "44", lord = false).copy(npcState = 0)
        return fixture.world(listOf(issuer to route.start, target to route.first, other to route.start),
            retainers = listOf(Retainer(1, 1, "EXISTING", 2, target.name, "guest"),
                Retainer(2, 1, "EXISTING", 3, other.name, "guest")),
            cityChanges = { if (it.id in setOf(route.startCity, route.destinationCounty)) it.copy(nationId = 1) else it })
    }

    private fun dispatch(id: String = "dispatch-order", actor: Int = 1, owner: Int = 42) =
        ImmediateInput(id, actor, owner, "court.dispatch",
            DispatchInput.canonicalJson(DispatchRequest(actor, 2, route.destinationCounty)))

    private fun reply(id: String = "dispatch-reply", actor: Int = 2, owner: Int = 43) =
        ImmediateInput(id, actor, owner, "court.dispatchReply",
            DispatchReplyInput.canonicalJson(DispatchReplyRequest(actor, "dispatch-order", true)))

    // Full people/card/position snapshots cover dispatch, assignment, loyalty, policy and locations.
    private fun snapshot(world: InMemoryTurnWorld) = Triple(
        world.listGenerals().sortedBy { it.id }, world.listRetainers().sortedBy { it.id },
        (1..3).map { world.positionOf(it) })

    private fun pending(world: InMemoryTurnWorld, handler: CourtHandler) {
        val accepted = handler.handle(dispatch())
        assertTrue(accepted.ok)
        assertEquals("reservationAccepted", accepted.type)
        assertNull(DispatchState.read(world.getGeneralById(2)!!.meta))
        handler.onIssuerTurn(1)
        val execution = handler.takeExecutions().single()
        assertEquals("dispatch-order", execution.requestId)
        assertEquals(42, execution.ownerUserId)
        assertTrue(execution.result.ok)
        assertEquals("executionApplied", execution.result.type)
        assertNull(QueuedDispatch.read(world.getGeneralById(1)!!.meta))
        assertEquals(DispatchStatus.PENDING, DispatchState.read(world.getGeneralById(2)!!.meta)?.status)
        assertNull(CountyAssignment.read(world.getGeneralById(2)!!.meta))
    }

    @Test fun `dispatch and recipient acceptance apply assignment after issuer turn`() {
        val world = world()
        val handler = CourtHandler(world, ChangeRecorder())
        val queued = handler.handle(dispatch().copy(inputId = "court.dispatch"))
        assertTrue(queued.ok)
        assertEquals("reservationAccepted", queued.type)
        assertNotNull(QueuedDispatch.read(world.getGeneralById(1)!!.meta))
        assertNull(DispatchState.read(world.getGeneralById(2)!!.meta))
        assertNull(CountyAssignment.read(world.getGeneralById(2)!!.meta))
        handler.onIssuerTurn(1)
        assertTrue(handler.takeExecutions().single().result.ok)
        val before = snapshot(world)
        val applied = handler.handle(reply().copy(inputId = "court.dispatchReply"))
        assertTrue(applied.ok)
        assertNull(applied.code)
        assertEquals("executionApplied", applied.type)
        assertEquals(DispatchStatus.ACCEPTED, DispatchState.read(world.getGeneralById(2)!!.meta)?.status)
        assertEquals(CountyAssignment("dispatch-order", 1, 1, route.destinationCounty),
            CountyAssignment.read(world.getGeneralById(2)!!.meta))
        assertNull(QueuedDispatch.read(world.getGeneralById(1)!!.meta))
        assertEquals(before.second, world.listRetainers().sortedBy { it.id })
        assertEquals(before.third, (1..3).map { world.positionOf(it) })
    }

    @Test fun `dispatch reply from non owner is forbidden without mutation`() {
        val world = world()
        val handler = CourtHandler(world, ChangeRecorder())
        pending(world, handler)
        val before = snapshot(world)
        val rejected = handler.handle(reply(owner = 44).copy(inputId = "court.dispatchReply"))
        assertFalse(rejected.ok)
        assertEquals("FORBIDDEN", rejected.code)
        assertEquals(before, snapshot(world))
        assertTrue(handler.takeExecutions().isEmpty())
    }

    @Test fun `dispatch reply from another retainer has no dispatch and no effects`() {
        val world = world()
        val handler = CourtHandler(world, ChangeRecorder())
        pending(world, handler)
        val before = snapshot(world)
        val rejected = handler.handle(reply(actor = 3, owner = 44).copy(inputId = "court.dispatchReply"))
        assertFalse(rejected.ok)
        assertEquals(DispatchFailure.NO_DISPATCH.name, rejected.code)
        assertEquals(before, snapshot(world))
        assertTrue(handler.takeExecutions().isEmpty())
    }

    @Test fun `dispatch reply repeated acceptance is already resolved without mutation`() {
        val world = world()
        val handler = CourtHandler(world, ChangeRecorder())
        pending(world, handler)
        assertTrue(handler.handle(reply()).ok)
        val before = snapshot(world)
        val rejected = handler.handle(reply().copy(inputId = "court.dispatchReply"))
        assertFalse(rejected.ok)
        assertEquals(DispatchFailure.ALREADY_RESOLVED.name, rejected.code)
        assertEquals(before, snapshot(world))
        assertTrue(handler.takeExecutions().isEmpty())
    }

    @Test fun `dispatch non lord and wrong owner cannot create queue`() {
        for ((actor, owner, reason) in listOf(Triple(3, 44, DispatchFailure.NOT_LORD.name),
                Triple(1, 43, "FORBIDDEN"))) {
            val world = world()
            val handler = CourtHandler(world, ChangeRecorder())
            val before = snapshot(world)
            val rejected = handler.handle(dispatch(actor = actor, owner = owner).copy(inputId = "court.dispatch"))
            assertFalse(rejected.ok)
            assertEquals(reason, rejected.code)
            assertNull(QueuedDispatch.read(world.getGeneralById(actor)!!.meta))
            assertEquals(before, snapshot(world))
            handler.onIssuerTurn(actor)
            assertEquals(before, snapshot(world))
            assertTrue(handler.takeExecutions().isEmpty())
        }
    }

    @Test fun `dispatch second queued order preserves first queue`() {
        val world = world()
        val handler = CourtHandler(world, ChangeRecorder())
        assertTrue(handler.handle(dispatch()).ok)
        val before = snapshot(world)
        val rejected = handler.handle(dispatch(id = "second-order").copy(inputId = "court.dispatch"))
        assertFalse(rejected.ok)
        assertEquals(DispatchFailure.ALREADY_QUEUED.name, rejected.code)
        assertEquals("dispatch-order", QueuedDispatch.read(world.getGeneralById(1)!!.meta)?.requestId)
        assertEquals(before, snapshot(world))
        assertTrue(handler.takeExecutions().isEmpty())
    }
}

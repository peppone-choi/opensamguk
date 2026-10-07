package opensamguk.engine.campaign

import kotlin.test.*
import opensamguk.common.wire.TurnDaemonCommand.ImmediateInput
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.input.CaptiveReleaseInput
import opensamguk.logic.input.CaptiveState
import opensamguk.logic.input.PeopleFailure
import opensamguk.logic.input.Phase

class CaptiveReleaseHandlerTest {
    private val fixture = CampaignWorldFixture()
    private val route = fixture.route()

    private fun world() = fixture.world(listOf(
        fixture.person(901, 1, route.startCity, userId = "42") to route.start,
        fixture.person(902, 2, route.startCity, lord = false).let { captive ->
            captive.copy(meta = captive.meta + (CaptiveState.META_KEY to
                CaptiveState(901, route.start.id, Phase(200, 1, 1), "battle-901").toMetaValue()))
        } to route.start,
    ))

    private fun command(owner: Int = 42) = ImmediateInput("release-901", 901, owner,
        CaptiveReleaseInput.INPUT_ID, """{"targetGeneralId":902}""")

    @Test fun `captive release removes custody immediately without a turn or nation and position changes`() {
        assertEquals("court.releaseCaptive", CaptiveReleaseInput.INPUT_ID)
        val world = world()
        val beforeActor = world.getGeneralById(901)!!
        val beforePosition = world.positionOf(902)
        val result = CourtHandler(world, ChangeRecorder()).handle(command())
        assertTrue(result.ok)
        assertEquals("executionApplied", result.type)
        assertEquals(2, world.getGeneralById(902)!!.nationId)
        assertEquals(beforePosition, world.positionOf(902))
        assertNull(CaptiveState.read(world.getGeneralById(902)!!.meta))
        assertEquals(beforeActor.turnTime, world.getGeneralById(901)!!.turnTime)
    }

    @Test fun `execution denies changed location and owner without clearing custody`() {
        val moved = world()
        val captive = moved.getGeneralById(902)!!
        moved.applyGeneralDirtyFree(captive.copy(meta = captive.meta + (CaptiveState.META_KEY to
            CaptiveState(901, route.destination.id, Phase(200, 1, 1), "battle-901").toMetaValue())))
        val wrongPlace = CourtHandler(moved, ChangeRecorder()).handle(command())
        assertFalse(wrongPlace.ok)
        assertEquals(PeopleFailure.TARGET_UNAVAILABLE.name, wrongPlace.code)
        assertNotNull(CaptiveState.read(moved.getGeneralById(902)!!.meta))

        val stolen = world()
        val wrongOwner = CourtHandler(stolen, ChangeRecorder()).handle(command(43))
        assertFalse(wrongOwner.ok)
        assertEquals("FORBIDDEN", wrongOwner.code)
        assertNotNull(CaptiveState.read(stolen.getGeneralById(902)!!.meta))
    }
}

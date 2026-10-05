package opensamguk.engine.intake

import opensamguk.common.wire.CreateGeneral

import opensamguk.common.wire.CreationCustomChoice
import opensamguk.common.world.WorldId
import opensamguk.engine.campaign.DelegationPhase
import opensamguk.engine.campaign.OfflineDelegationLease
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.world.GeneralPositionSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreationStatsExecutionTest {
    @Test fun malformedCapRejectsWithoutCreatingOrRecordingAGeneral() {
        for (value in listOf<Any?>(null, "50", 50.5, 4_294_967_346L, 0, -1)) {
            val world = world(maxGeneral = value)
            val recorder = ChangeRecorder()
            val result = CreationHandler(world, recorder).handle(command(
                CreationCustomChoice("정원검증", 10, 60, 60, 60, 60, 60, "WANGDO", "DISCIPLINE",
                    role = "RETAINER")))
            assertFalse(result.ok, "maxgeneral=$value")
            assertEquals("CREATION_POLICY_UNAVAILABLE", result.errorCode, "maxgeneral=$value")
            assertTrue(world.listGenerals().isEmpty())
            assertTrue(world.consumeDirtyState().createdGenerals.isEmpty())
            assertTrue(recorder.dirtyGeneralIds().isEmpty())
            assertTrue(recorder.accessLogUpserts().isEmpty())
        }
    }

    @Test fun queuedCreationRechecksTheWorldCreationBlock() {
        val world = world(blockGeneralCreate = 1)
        val recorder = ChangeRecorder()
        val result = CreationHandler(world, recorder).handle(command(
            CreationCustomChoice("검증 중 차단", 10, 60, 60, 60, 60, 60, "WANGDO", "DISCIPLINE",
                role = "RETAINER")))
        assertFalse(result.ok)
        assertEquals("CREATION_POLICY_UNAVAILABLE", result.errorCode)
        assertTrue(world.listGenerals().isEmpty())
        assertTrue(recorder.dirtyGeneralIds().isEmpty())
    }

    @Test fun customCreationRequiresAnExplicitStartingRole() {
        val world = world()
        val result = CreationHandler(world, ChangeRecorder()).handle(command(
            CreationCustomChoice("역할 없는 장수", 10, 60, 60, 60, 60, 60, "WANGDO", "DISCIPLINE")))
        assertFalse(result.ok)
        assertEquals("INVALID_REQUEST", result.errorCode)
        assertTrue(world.listGenerals().isEmpty())
    }

    @Test fun exactTotalBoundaryFixtureCanCreateGeneral() {
        val world = world()
        val result = CreationHandler(world, ChangeRecorder()).handle(command(
            CreationCustomChoice("검증 장수", 10, 20, 85, 65, 65, 65, "WANGDO", "DISCIPLINE",
                role = "RETAINER")))
        assertTrue(result.ok)
        assertEquals(1, world.listGenerals().size)
        val created = world.listGenerals().single()
        assertEquals(
            OfflineDelegationLease(1, created.id, 7, DelegationPhase(200, 1, world.getState().currentPhase)),
            OfflineDelegationLease.read(created.meta),
        )
    }

    @Test fun malformedStatsCannotAllocateIdOrChangeWorldAndRecorder() {
        val base = CreationCustomChoice("검증 장수", 10, 60, 60, 60, 60, 60,
            "WANGDO", "DISCIPLINE", role = "RETAINER")
        val malformed = listOf(
            base.copy(leadership = 19, strength = 85, intel = 65, politics = 65, charm = 66),
            base.copy(leadership = 86, strength = 20, intel = 64, politics = 65, charm = 65),
            base.copy(charm = 59),
            base.copy(charm = 61),
        )
        for (choice in malformed) {
            val world = world()
            val recorder = ChangeRecorder()
            val before = world.getState()
            val result = CreationHandler(world, recorder).handle(command(choice))
            assertFalse(result.ok, "choice=$choice")
            assertEquals("INVALID_STATS", result.errorCode, "choice=$choice")
            assertEquals(before, world.getState(), "state/ID changed for choice=$choice")
            assertTrue(world.listGenerals().isEmpty(), "general created for choice=$choice")
            assertTrue(world.consumeDirtyState().createdGenerals.isEmpty(), "general queued for choice=$choice")
            assertTrue(recorder.dirtyGeneralIds().isEmpty(), "recorder changed for choice=$choice")
            assertTrue(recorder.accessLogUpserts().isEmpty(), "access log changed for choice=$choice")
        }
    }

    private fun command(choice: CreationCustomChoice) = CreateGeneral(
        accountId = 7, worldId = 1, clientRequestId = "92d9244b-6eb5-4f89-971d-d1b1247e0ff6",
        choiceKind = "CUSTOM", custom = choice)

    private fun world(blockGeneralCreate: Int = 0, maxGeneral: Any? = 50) = InMemoryTurnWorld(WorldSnapshot(
        state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600,
            lastTurnTime = Instant.parse("0200-01-01T00:00:00Z"),
            meta = mapOf("isunited" to 0), config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN",
                "mapName" to "han-world-v3", "block_general_create" to blockGeneralCreate) +
                (if (maxGeneral == null) emptyMap() else mapOf("maxgeneral" to maxGeneral))),
        worldId = WorldId(1),
        cities = listOf(City(10, "낙양", 0, level = 5)),
        generalPositionSnapshot = GeneralPositionSnapshot("fixture", "a".repeat(64), setOf("p"), emptySet()),
        cityLandProvinceById = mapOf(10 to "p"),
    ))
}

package opensamguk.engine.nationbulk

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot

class NationBulkLifecycleCoordinatorTest {
    private val now = Instant.parse("0200-01-01T00:00:00Z")

    @Test
    fun `below-floor user advances 93 to 100 only through a recorder-backed stage B patch`() {
        val world = worldWith(general(id = 41, killturn = 93))
        val recorder = ChangeRecorder()
        val coordinator = coordinator(world, recorder, child(actorGeneralId = 41, floor = 100))

        val ringCommitted = transitioned(coordinator.markRingCommitted(childIndex = 0, expectedStageVersion = 0))
        assertEquals(NationBulkChildStage.RING_COMMITTED, ringCommitted.stage)
        assertEquals(1L, ringCommitted.stageVersion)
        assertEquals(93, killturnOf(world, 41))
        assertTrueNoGeneralPatch(recorder)

        val applied = transitioned(coordinator.applyStageB(childIndex = 0, expectedStageVersion = 1))
        assertEquals(NationBulkChildStage.APPLIED, applied.stage)
        assertEquals(2L, applied.stageVersion)
        assertEquals(100, killturnOf(world, 41))
        assertEquals(100, recorder.generalPatches().single().meta["killturn"])
    }

    @Test
    fun `above-floor user reaches NOOP without a general patch`() {
        val world = worldWith(general(id = 41, killturn = 107))
        val recorder = ChangeRecorder()
        val coordinator = coordinator(world, recorder, child(actorGeneralId = 41, floor = 100))

        transitioned(coordinator.markRingCommitted(childIndex = 0, expectedStageVersion = 0))
        val noop = transitioned(coordinator.applyStageB(childIndex = 0, expectedStageVersion = 1))

        assertEquals(NationBulkChildStage.NOOP, noop.stage)
        assertEquals(107, killturnOf(world, 41))
        assertTrueNoGeneralPatch(recorder)
    }

    @Test
    fun `npc actor reaches NOOP without a general patch`() {
        val world = worldWith(general(id = 41, npcState = 2, killturn = 93))
        val recorder = ChangeRecorder()
        val coordinator = coordinator(world, recorder, child(actorGeneralId = 41, floor = 100))

        transitioned(coordinator.markRingCommitted(childIndex = 0, expectedStageVersion = 0))
        val noop = transitioned(coordinator.applyStageB(childIndex = 0, expectedStageVersion = 1))

        assertEquals(NationBulkChildStage.NOOP, noop.stage)
        assertEquals(93, killturnOf(world, 41))
        assertTrueNoGeneralPatch(recorder)
    }

    @Test
    fun `post-ring failure survives recovery retries only stage B and blocks later children`() {
        val world = worldWith(
            general(id = 41, killturn = 93),
            general(id = 42, killturn = 100),
        )
        val recorder = ChangeRecorder()
        val initial = coordinator(
            world,
            recorder,
            child(childIndex = 0, actorGeneralId = 41, floor = 100),
            child(childIndex = 1, actorGeneralId = 42, floor = 100),
        )

        val checkpoint = transitioned(initial.markRingCommitted(childIndex = 0, expectedStageVersion = 0))
        assertEquals(NationBulkChildStage.RING_COMMITTED, checkpoint.stage)
        assertEquals(93, killturnOf(world, 41))

        val flakyPort = FailOnceNationBulkGeneralPort(
            ChangeRecorderNationBulkGeneralPort(world, recorder),
        )
        val recovered = NationBulkLifecycleCoordinator(
            children = listOf(checkpoint, initial.child(1)),
            generalPort = flakyPort,
        )

        val failed = transitioned(recovered.applyStageB(childIndex = 0, expectedStageVersion = 1))
        assertEquals(NationBulkChildStage.FAILED_AFTER_RING, failed.stage)
        assertEquals(2L, failed.stageVersion)
        assertEquals(93, killturnOf(world, 41))
        assertTrueNoGeneralPatch(recorder)

        val blocked = rejected(recovered.markRingCommitted(childIndex = 1, expectedStageVersion = 0))
        assertEquals(NationBulkLifecycleFailure.EARLIER_CHILD_BLOCKS_PROGRESS, blocked.failure)
        assertEquals(NationBulkChildStage.PENDING, recovered.child(1).stage)

        val applied = transitioned(recovered.applyStageB(childIndex = 0, expectedStageVersion = 2))
        assertEquals(NationBulkChildStage.APPLIED, applied.stage)
        assertEquals(3L, applied.stageVersion)
        assertEquals(100, killturnOf(world, 41))
        assertEquals(100, recorder.generalPatches().single().meta["killturn"])

        val childOneRing = transitioned(recovered.markRingCommitted(childIndex = 1, expectedStageVersion = 0))
        assertEquals(NationBulkChildStage.RING_COMMITTED, childOneRing.stage)
    }

    @Test
    fun `stale and invalid transitions leave the child unchanged`() {
        val world = worldWith(general(id = 41, killturn = 93))
        val recorder = ChangeRecorder()
        val coordinator = coordinator(world, recorder, child(actorGeneralId = 41, floor = 100))

        val stale = rejected(coordinator.markRingCommitted(childIndex = 0, expectedStageVersion = 9))
        assertEquals(NationBulkLifecycleFailure.STALE_STAGE_VERSION, stale.failure)
        assertEquals(NationBulkChildStage.PENDING, coordinator.child(0).stage)

        val invalid = rejected(coordinator.applyStageB(childIndex = 0, expectedStageVersion = 0))
        assertEquals(NationBulkLifecycleFailure.INVALID_TRANSITION, invalid.failure)
        assertEquals(NationBulkChildStage.PENDING, coordinator.child(0).stage)
        assertEquals(93, killturnOf(world, 41))
        assertTrueNoGeneralPatch(recorder)
    }

    @Test
    fun `rejected-before-ring is terminal and never creates a general patch`() {
        val world = worldWith(general(id = 41, killturn = 93))
        val recorder = ChangeRecorder()
        val coordinator = coordinator(world, recorder, child(actorGeneralId = 41, floor = 100))

        val rejectedBeforeRing = transitioned(coordinator.rejectBeforeRing(childIndex = 0, expectedStageVersion = 0))
        assertEquals(NationBulkChildStage.REJECTED_BEFORE_RING, rejectedBeforeRing.stage)
        assertEquals(1L, rejectedBeforeRing.stageVersion)

        val invalid = rejected(coordinator.markRingCommitted(childIndex = 0, expectedStageVersion = 1))
        assertEquals(NationBulkLifecycleFailure.INVALID_TRANSITION, invalid.failure)
        assertEquals(93, killturnOf(world, 41))
        assertTrueNoGeneralPatch(recorder)
    }

    @Test
    fun `rejected-before-ring is terminal but never permits a suffix child`() {
        val world = worldWith(
            general(id = 41, killturn = 93),
            general(id = 42, killturn = 93),
        )
        val recorder = ChangeRecorder()
        val coordinator = coordinator(
            world,
            recorder,
            child(childIndex = 0, actorGeneralId = 41, floor = 100),
            child(childIndex = 1, actorGeneralId = 42, floor = 100),
        )

        val rejectedBeforeRing = transitioned(coordinator.rejectBeforeRing(childIndex = 0, expectedStageVersion = 0))
        assertTrue(rejectedBeforeRing.stage.isTerminal)
        assertFalse(rejectedBeforeRing.stage.allowsNextChild)

        val firstSuffixAttempt = rejected(coordinator.markRingCommitted(childIndex = 1, expectedStageVersion = 0))
        assertEquals(NationBulkLifecycleFailure.EARLIER_CHILD_BLOCKS_PROGRESS, firstSuffixAttempt.failure)

        val repeatedSuffixAttempt = rejected(coordinator.rejectBeforeRing(childIndex = 1, expectedStageVersion = 0))
        assertEquals(NationBulkLifecycleFailure.EARLIER_CHILD_BLOCKS_PROGRESS, repeatedSuffixAttempt.failure)
        assertEquals(NationBulkChildStage.PENDING, coordinator.child(1).stage)
        assertTrueNoGeneralPatch(recorder)
    }

    @Test
    fun `same expected version allows exactly one concurrent ring transition`() {
        val world = worldWith(general(id = 41, killturn = 93))
        val recorder = ChangeRecorder()
        val coordinator = coordinator(world, recorder, child(actorGeneralId = 41, floor = 100))
        val ready = CountDownLatch(2)
        val entered = CountDownLatch(2)
        val start = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val transitions = List(2) {
                executor.submit<NationBulkLifecycleResult> {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    entered.countDown()
                    try {
                        coordinator.markRingCommitted(childIndex = 0, expectedStageVersion = 0)
                    } finally {
                        completed.countDown()
                    }
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            synchronized(coordinator) {
                start.countDown()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertFalse(completed.await(200, TimeUnit.MILLISECONDS))
            }

            val results = transitions.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is NationBulkLifecycleResult.Transitioned })
            val stale = results.filterIsInstance<NationBulkLifecycleResult.Rejected>()
            assertEquals(1, stale.size)
            assertEquals(NationBulkLifecycleFailure.STALE_STAGE_VERSION, stale.single().failure)
            assertEquals(NationBulkChildStage.RING_COMMITTED, coordinator.child(0).stage)
            assertEquals(1L, coordinator.child(0).stageVersion)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun coordinator(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        vararg children: NationBulkChildLifecycle,
    ): NationBulkLifecycleCoordinator = NationBulkLifecycleCoordinator(
        children = children.toList(),
        generalPort = ChangeRecorderNationBulkGeneralPort(world, recorder),
    )

    private fun child(
        childIndex: Int = 0,
        actorGeneralId: Int,
        floor: Int,
    ): NationBulkChildLifecycle = NationBulkChildLifecycle(
        childIndex = childIndex,
        actorGeneralId = actorGeneralId,
        frozenKillturnFloor = floor,
    )

    private fun worldWith(vararg generals: TurnGeneral): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600, lastTurnTime = now),
            generals = generals.toList(),
        ),
    )

    private fun general(id: Int, npcState: Int = 0, killturn: Int): TurnGeneral = TurnGeneral(
        id = id,
        name = "g$id",
        nationId = 1,
        cityId = 1,
        troopId = 0,
        stats = GeneralStats(50, 50, 50),
        experience = 0,
        dedication = 0,
        officerLevel = 5,
        npcState = npcState,
        turnTime = now,
        meta = linkedMapOf("killturn" to killturn),
    )

    private fun killturnOf(world: InMemoryTurnWorld, generalId: Int): Int {
        val value = requireNotNull(world.getGeneralById(generalId)).meta["killturn"]
        return (value as Number).toInt()
    }

    private fun transitioned(result: NationBulkLifecycleResult): NationBulkChildLifecycle =
        assertIs<NationBulkLifecycleResult.Transitioned>(result).child

    private fun rejected(result: NationBulkLifecycleResult): NationBulkLifecycleResult.Rejected =
        assertIs(result)

    private fun assertTrueNoGeneralPatch(recorder: ChangeRecorder) {
        assertNull(recorder.generalPatches().singleOrNull())
    }

    private class FailOnceNationBulkGeneralPort(
        private val delegate: NationBulkGeneralPort,
    ) : NationBulkGeneralPort {
        private var shouldFail = true

        override fun actor(generalId: Int): NationBulkActor? = delegate.actor(generalId)

        override fun commitKillturn(patch: NationBulkKillturnPatch): NationBulkPatchCommitResult {
            if (shouldFail) {
                shouldFail = false
                return NationBulkPatchCommitResult.Failed("simulated post-ring failure")
            }
            return delegate.commitKillturn(patch)
        }
    }
}

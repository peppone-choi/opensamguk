package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.*
import opensamguk.logic.battle.realtime.*
import opensamguk.logic.battle.realtime.soldier.*
import opensamguk.logic.battle.realtime.space.*
import org.mockito.Mockito.*

class BattleV2CheckpointRecoveryTest {
    private val world = WorldId(1)
    private val battleId = "checkpoint-test"
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val factory = TacticalV2Battle.spaceGrid(TacticalRules.CANON)

    private fun state(seed: Long = 7): TacticalV2BattleState {
        val sources = BattleSide.entries.mapIndexed { index, side ->
            TacticalV2UnitSource(TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "${index + 1}"),
                side, index + 1, index + 1, 1, 1)
        }
        val cells = sources.mapIndexed { index, source -> source.key to TacticalV2Cell(10 + index * 40, 10) }.toMap()
        val deployment = TacticalV2Deployment(0, "a".repeat(64), sources, cells,
            sources.associate { it.side to setOf(cells.getValue(it.key)) })
        val facts = sources.map { source -> TacticalV2SoldierSourceFacts(source, 1, UnitKind.INFANTRY,
            GeneralStats(requireNotNull(source.commanderGeneralId), 50, 50, 50, 50, 50), 50, 100, 0, 100) }
        // Explicit fixture values never confirm the production parameter files.
        val context = TacticalV2BattleContext(seed, Battlefield(0, "FIELD", List(64) { "P".repeat(64) }),
            deployment, facts, TacticalRules.CANON, TacticalV2SoldierCombatParams(10, 4, "b".repeat(64)),
            TacticalV2SpaceParams(4, Fx(Fx.ONE / 8), "c".repeat(64)), BattleSide.entries.toSet())
        val initial = assertIs<TacticalV2BattleStart.Ready>(TacticalV2Battle.start(context, factory)).state
        val moving = TacticalV2Battle.command(initial,
            listOf(TacticalV2SoldierCommand(listOf(1L), TacticalV2SoldierOrder.Move(TacticalV2Cell(12, 12)))),
            setOf(sources.first().key))
        assertTrue(moving.accepted)
        return TacticalV2Battle.step(TacticalV2Battle.step(moving.state, factory), factory)
    }

    private fun head() = BattleSessionHead(world, battleId, BattleSessionPhase.RUNNING, 2, 2, 4, 1,
        "actor", now.plusSeconds(5), now.minusSeconds(60), now.plusSeconds(300))

    private fun checkpoint(state: TacticalV2BattleState) = BattleCheckpoint(world, battleId, 2, "actor",
        state.tick.toInt(), 4, state.stateSha256(), TacticalV2BattleCodec.encode(state))

    private fun store(head: BattleSessionHead? = head(), checkpoint: BattleCheckpoint? = checkpoint(state())) =
        mock(BattleSessionStore::class.java).also {
            `when`(it.head(world, battleId)).thenReturn(head)
            `when`(it.latestCheckpoint(world, battleId)).thenReturn(checkpoint)
            `when`(it.renewLease(world, battleId, "actor", 2, 5_000)).thenReturn(true)
        }

    private fun recovery(store: BattleSessionStore) =
        BattleV2CheckpointRecovery(store, world, battleId, "actor", 2, 5_000)

    @Test
    fun `exact checkpoint restores bytes cursor and deterministic continuation without tick writes`() {
        val original = state()
        val store = store(checkpoint = checkpoint(original))
        val restored = assertNotNull(recovery(store).restore(original.context))
        assertEquals(4L, restored.eventSeq)
        assertContentEquals(TacticalV2BattleCodec.encode(original), TacticalV2BattleCodec.encode(restored.state))
        assertEquals(TacticalV2Battle.step(original, factory).stateSha256(),
            TacticalV2Battle.step(restored.state, factory).stateSha256())
        verify(store, times(2)).head(world, battleId)
        verify(store).latestCheckpoint(world, battleId)
        verify(store).renewLease(world, battleId, "actor", 2, 5_000)
        verifyNoMoreInteractions(store)
    }

    @Test
    fun `new owner restores previous epoch checkpoint after a valid database lease renewal`() {
        val original = state()
        val store = store(checkpoint = checkpoint(original).copy(sessionEpoch = 1, leaseOwner = "old-actor"))
        assertEquals(original.stateSha256(), assertNotNull(recovery(store).restore(original.context)).state.stateSha256())
    }

    @Test
    fun `foreign checkpoint world or battle identity is rejected`() {
        val original = state()
        for (foreign in listOf(checkpoint(original).copy(worldId = WorldId(2)),
            checkpoint(original).copy(battleId = "other"))) {
            assertFailsWith<IllegalArgumentException> { recovery(store(checkpoint = foreign)).restore(original.context) }
        }
    }

    @Test
    fun `missing inactive foreign or superseded heads never renew or read checkpoints`() {
        val original = state()
        val heads = listOf(null, head().copy(worldId = WorldId(2)), head().copy(battleId = "other"),
            head().copy(sessionEpoch = 3), head().copy(leaseOwner = "other"), head().copy(leaseUntil = null),
            head().copy(phase = BattleSessionPhase.JOINING), head().copy(phase = BattleSessionPhase.APPLIED))
        for (head in heads) {
            val store = store(head = head)
            assertNull(recovery(store).restore(original.context))
            verify(store).head(world, battleId)
            verifyNoMoreInteractions(store)
        }
    }

    @Test
    fun `missing checkpoint or snapshot never falls back to initial state`() {
        assertNull(recovery(store(checkpoint = null)).restore(state().context))
        val store = store(head = head().copy(latestSnapshotSeq = 0))
        assertNull(recovery(store).restore(state().context))
        verify(store).head(world, battleId)
        verifyNoMoreInteractions(store)
    }

    @Test
    fun `older or future tick and event cursors require recovery beyond this adapter`() {
        val original = state()
        val checkpoint = checkpoint(original)
        for (mismatch in listOf(checkpoint.copy(tick = 1), checkpoint.copy(tick = 3),
            checkpoint.copy(eventSeq = 3), checkpoint.copy(eventSeq = 5))) {
            val store = store(checkpoint = mismatch)
            assertNull(recovery(store).restore(original.context))
            verify(store).head(world, battleId)
            verify(store).latestCheckpoint(world, battleId)
            verifyNoMoreInteractions(store)
        }
    }

    @Test
    fun `checkpoint from future epoch or different owner in current epoch is unavailable`() {
        val original = state()
        for (mismatch in listOf(checkpoint(original).copy(sessionEpoch = 3),
            checkpoint(original).copy(sessionEpoch = 0), checkpoint(original).copy(leaseOwner = "other"))) {
            assertNull(recovery(store(checkpoint = mismatch)).restore(original.context))
        }
    }

    @Test
    fun `valid hash with another committed context cannot restore`() {
        assertFailsWith<IllegalArgumentException> { recovery(store()).restore(state(seed = 8).context) }
    }

    @Test
    fun `damaged checksum and structurally corrupt bytes reject before lease renewal`() {
        val original = state()
        val checkpoint = checkpoint(original)
        val truncated = checkpoint.compressedState.copyOf(8)
        val truncatedHash = MessageDigest.getInstance("SHA-256").digest(truncated)
            .joinToString("") { "%02x".format(it) }
        for (bad in listOf(checkpoint.copy(stateHash = "0".repeat(64)),
            checkpoint.copy(compressedState = truncated, stateHash = truncatedHash))) {
            val store = store(checkpoint = bad)
            assertFailsWith<IllegalArgumentException> { recovery(store).restore(original.context) }
            verify(store).head(world, battleId)
            verify(store).latestCheckpoint(world, battleId)
            verifyNoMoreInteractions(store)
        }
    }

    @Test
    fun `decoded tick must match durable checkpoint metadata`() {
        val original = state()
        val store = store(head = head().copy(currentTick = 3), checkpoint = checkpoint(original).copy(tick = 3))
        assertFailsWith<IllegalArgumentException> { recovery(store).restore(original.context) }
    }

    @Test
    fun `database expired or stolen lease rejects an otherwise matching checkpoint`() {
        val store = store(head = head().copy(leaseUntil = now.minusSeconds(1)))
        `when`(store.renewLease(world, battleId, "actor", 2, 5_000)).thenReturn(false)
        assertNull(recovery(store).restore(state().context))
        verify(store).head(world, battleId)
        verify(store).latestCheckpoint(world, battleId)
        verify(store).renewLease(world, battleId, "actor", 2, 5_000)
        verifyNoMoreInteractions(store)
    }

    @Test
    fun `head changes during decode or lease renewal cannot escape as a current state`() {
        val head = head()
        val changed = listOf(null, head.copy(currentTick = 3), head.copy(latestEventSeq = 5),
            head.copy(latestSnapshotSeq = 2), head.copy(sessionEpoch = 3), head.copy(leaseOwner = "other"),
            head.copy(phase = BattleSessionPhase.RESULT_PENDING), head.copy(leaseUntil = null),
            head.copy(worldId = WorldId(2)), head.copy(battleId = "other"))
        for (latest in changed) {
            val store = store()
            `when`(store.head(world, battleId)).thenReturn(head, latest)
            assertNull(recovery(store).restore(state().context))
        }
    }

    @Test
    fun `lease extension alone and resolving phase preserve exact recovery`() {
        val head = head().copy(phase = BattleSessionPhase.RESOLVING)
        val store = store()
        `when`(store.head(world, battleId)).thenReturn(head, head.copy(leaseUntil = now.plusSeconds(10)))
        assertNotNull(recovery(store).restore(state().context))
    }

    @Test
    fun `source failures propagate instead of producing an initial or empty state`() {
        val store = store()
        `when`(store.latestCheckpoint(world, battleId)).thenThrow(IllegalStateException("source unavailable"))
        assertFailsWith<IllegalStateException> { recovery(store).restore(state().context) }
    }
}

package opensamguk.engine.campaign

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot

class OfflineDelegationPresenceTest {
    private fun world(): InMemoryTurnWorld = InMemoryTurnWorld(WorldSnapshot(
        worldId = WorldId(7),
        state = TurnWorldState(7, 190, 1, 3600, Instant.EPOCH, currentPhase = 1,
            config = mapOf("ruleProfile" to "HWIHA")),
        generals = listOf(TurnGeneral(12, "19", "player", 1, 1, 0,
            GeneralStats(70, 70, 70, 70, 70), 0, 0, 1, turnTime = Instant.EPOCH)),
    ))

    @Test
    fun `authenticated owner activity is recorded in meta and duplicate phase is unchanged`() {
        val world = world()
        val presence = OfflineDelegationPresence(world, ChangeRecorder())

        assertEquals(OfflineDelegationPresence.Result.REJECTED, presence.record(12, 20))
        assertNull(OfflineDelegationLease.read(world.getGeneralById(12)!!.meta))
        assertEquals(OfflineDelegationPresence.Result.RECORDED, presence.record(12, 19))
        assertEquals(OfflineDelegationLease(7, 12, 19, DelegationPhase(190, 1, 1)),
            OfflineDelegationLease.read(world.getGeneralById(12)!!.meta))
        assertEquals(OfflineDelegationPresence.Result.UNCHANGED, presence.record(12, 19))
    }

    @Test
    fun `ownership change invalidates the previous owners activity`() {
        val world = world()
        val presence = OfflineDelegationPresence(world, ChangeRecorder())
        assertEquals(OfflineDelegationPresence.Result.RECORDED, presence.record(12, 19))
        val current = world.getGeneralById(12)!!
        world.applyGeneralDirtyFree(current.copy(userId = "20"))

        assertEquals(OfflineDelegationPresence.Result.REJECTED, presence.record(12, 19))
        assertEquals(OfflineDelegationPresence.Result.RECORDED, presence.record(12, 20))
        assertEquals(OfflineDelegationLease(7, 12, 20, DelegationPhase(190, 1, 1)),
            OfflineDelegationLease.read(world.getGeneralById(12)!!.meta))
    }

    @Test
    fun `future persisted activity is rejected without moving the lease`() {
        val world = world()
        val future = OfflineDelegationLease(7, 12, 19, DelegationPhase(190, 1, 2))
        val general = world.getGeneralById(12)!!
        world.applyGeneralDirtyFree(general.copy(meta = general.meta +
            (OfflineDelegationLease.META_KEY to future.toMetaValue())))

        assertEquals(OfflineDelegationPresence.Result.REJECTED,
            OfflineDelegationPresence(world, ChangeRecorder()).record(12, 19))
        assertEquals(future, OfflineDelegationLease.read(world.getGeneralById(12)!!.meta))
    }
}

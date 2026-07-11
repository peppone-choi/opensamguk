package opensamguk.engine.intake

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.GeneralAccessLog
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminGeneralModerationHandlerTest {
    private val now = Instant.parse("0200-01-01T00:00:00Z")

    @Test
    fun `access actions update only access-log score through recorder`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(1, 200, 1, 3600, now),
                generals = listOf(general(1), general(2)),
                accessLogs = listOf(GeneralAccessLog(2, 77, now, refreshScore = 12, refreshScoreTotal = 99)),
            ),
        )
        val recorder = ChangeRecorder()
        val handler = AdminGeneralModerationHandler(world, recorder)

        val denied = handler.handle(
            TurnDaemonCommand.AdminGeneralModeration(actorGeneralId = 1, generalIds = listOf(2), action = "denyAccess"),
        )
        assertTrue(denied.ok)
        assertEquals(1000, world.getAccessLog(2)!!.refreshScore)
        assertEquals(99, world.getAccessLog(2)!!.refreshScoreTotal)

        val allowed = handler.handle(
            TurnDaemonCommand.AdminGeneralModeration(actorGeneralId = 1, generalIds = listOf(2), action = "allowAccess"),
        )
        assertTrue(allowed.ok)
        assertEquals(0, world.getAccessLog(2)!!.refreshScore)
        assertEquals(0, recorder.accessLogUpserts().single().refreshScore)
        assertTrue(recorder.dirtyGeneralIds().isEmpty())
    }

    @Test
    fun `block and dex actions mutate through recorder`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(1, 200, 1, 3600, now),
                generals = listOf(general(1), general(2)),
            ),
        )
        val recorder = ChangeRecorder()
        val handler = AdminGeneralModerationHandler(world, recorder)

        val blocked = handler.handle(
            TurnDaemonCommand.AdminGeneralModeration(
                actorGeneralId = 1,
                generalIds = listOf(2),
                action = "block2",
            ),
        )
        assertTrue(blocked.ok)
        assertEquals(0, world.getGeneralById(2)!!.gold)
        assertEquals(0, world.getGeneralById(2)!!.rice)
        assertEquals(2, world.getGeneralById(2)!!.meta["block"])
        assertEquals(24, world.getGeneralById(2)!!.meta["killturn"])

        handler.handle(
            TurnDaemonCommand.AdminGeneralModeration(
                actorGeneralId = 1,
                generalIds = listOf(2),
                action = "dex3",
            ),
        )
        assertEquals(10_000, world.getGeneralById(2)!!.meta["dex3"])
        assertTrue(2 in recorder.dirtyGeneralIds())
    }

    @Test
    fun `unsupported action fails without mutation`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(1, 200, 1, 3600, now),
                generals = listOf(general(1)),
            ),
        )
        val recorder = ChangeRecorder()
        val result = AdminGeneralModerationHandler(world, recorder).handle(
            TurnDaemonCommand.AdminGeneralModeration(
                actorGeneralId = 1,
                generalIds = listOf(1),
                action = "unknown",
            ),
        )
        assertFalse(result.ok)
        assertTrue(recorder.dirtyGeneralIds().isEmpty())
    }

    @Test
    fun `missing target fails before any target is mutated`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(1, 200, 1, 3600, now),
                generals = listOf(general(1), general(2)),
            ),
        )
        val recorder = ChangeRecorder()
        val result = AdminGeneralModerationHandler(world, recorder).handle(
            TurnDaemonCommand.AdminGeneralModeration(
                actorGeneralId = 1,
                generalIds = listOf(2, 999),
                action = "block1",
            ),
        )

        assertFalse(result.ok)
        assertEquals(500, world.getGeneralById(2)!!.gold)
        assertTrue(recorder.dirtyGeneralIds().isEmpty())
    }

    private fun general(id: Int): TurnGeneral = TurnGeneral(
        id = id,
        name = "g$id",
        nationId = 1,
        cityId = 1,
        troopId = 0,
        stats = GeneralStats(50, 50, 50),
        experience = 0,
        dedication = 0,
        officerLevel = 1,
        gold = 500,
        rice = 500,
        turnTime = now,
    )
}

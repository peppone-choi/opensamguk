package opensamguk.engine.tournament

import opensamguk.common.wire.GeneralBoolResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.KvKey
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TournamentAdminHandlerTest {

    private val t0 = Instant.parse("0200-03-01T00:00:00Z")

    private fun world(officerLevel: Int = 12): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(1, 200, 3, 3600, t0, meta = emptyMap()),
            generals = listOf(
                TurnGeneral(
                    id = 10,
                    name = "유비",
                    nationId = 1,
                    cityId = 1,
                    troopId = 0,
                    stats = GeneralStats(80, 70, 60),
                    experience = 0,
                    dedication = 0,
                    officerLevel = officerLevel,
                    gold = 1000,
                    turnTime = t0,
                    role = opensamguk.engine.turn.GeneralRole(),
                    meta = mapOf("tournament" to 1),
                ),
            ),
            nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", gold = 0)),
        ),
    )

    @Test
    fun `start and reset write tournament state through recorder`() {
        val world = world()
        val recorder = ChangeRecorder()
        val handler = TournamentAdminHandler(world, recorder, now = { t0 })

        val start = handler.handleStart(
            TurnDaemonCommand.TournamentStart(requestId = "r1", generalId = 10, tournamentType = 2),
        ) as GeneralBoolResult

        assertTrue(start.ok)
        assertEquals(1, recorder.kvDirty()[KvKey("game_env", "game_env", "tournament")])
        assertEquals(2, recorder.kvDirty()[KvKey("game_env", "game_env", "tnmt_type")])
        assertEquals(true, recorder.kvDirty()[KvKey("game_env", "game_env", "tnmt_auto")])
        assertEquals(0, world.getGeneralById(10)!!.meta["tournament"])

        val reset = handler.handleReset(TurnDaemonCommand.TournamentReset(requestId = "r2", generalId = 10)) as GeneralBoolResult

        assertTrue(reset.ok)
        assertEquals(0, recorder.kvDirty()[KvKey("game_env", "game_env", "tournament")])
        assertEquals(false, recorder.kvDirty()[KvKey("game_env", "game_env", "tnmt_auto")])
    }

    @Test
    fun `start denies non-chief officer with legacy permission message`() {
        val recorder = ChangeRecorder()
        val handler = TournamentAdminHandler(world(officerLevel = 2), recorder, now = { t0 })

        val start = handler.handleStart(
            TurnDaemonCommand.TournamentStart(requestId = "r1", generalId = 10, tournamentType = 2),
        ) as GeneralBoolResult

        assertEquals(false, start.ok)
        assertEquals("권한이 부족합니다. 수뇌부가 아닙니다.", start.reason)
        assertEquals(emptyMap(), recorder.kvDirty())
    }

    @Test
    fun `reset denies non-chief officer with legacy permission message`() {
        val recorder = ChangeRecorder()
        val handler = TournamentAdminHandler(world(officerLevel = 2), recorder, now = { t0 })

        val reset = handler.handleReset(
            TurnDaemonCommand.TournamentReset(requestId = "r2", generalId = 10),
        ) as GeneralBoolResult

        assertEquals(false, reset.ok)
        assertEquals("권한이 부족합니다. 수뇌부가 아닙니다.", reset.reason)
        assertEquals(emptyMap(), recorder.kvDirty())
    }
}

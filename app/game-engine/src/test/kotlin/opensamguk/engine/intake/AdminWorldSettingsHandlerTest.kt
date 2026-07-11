package opensamguk.engine.intake

import opensamguk.common.wire.AdminWorldSetting
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminWorldSettingsHandlerTest {
    @Test
    fun `settings update live world and record game-env message`() {
        val world = world()
        val recorder = ChangeRecorder()
        val result = AdminWorldSettingsHandler(world, recorder).handle(
            TurnDaemonCommand.AdminWorldSettings(
                status = "PRE_OPEN",
                settings = listOf(
                    AdminWorldSetting("npcmode", intValue = 2),
                    AdminWorldSetting("turnterm", intValue = 30),
                    AdminWorldSetting("msg", stringValue = "점검 중"),
                ),
            ),
        )

        assertTrue(result.ok)
        assertEquals("PRE_OPEN", world.getState().status)
        assertEquals(1_800, world.getState().tickSeconds)
        assertEquals(2, world.getState().config["npcmode"])
        assertEquals(30, world.getState().config["turnterm"])
        assertEquals("점검 중", world.getState().meta["msg"])
        assertEquals("점검 중", recorder.kvDirty().values.single())
    }

    @Test
    fun `invalid setting fails without state change`() {
        val world = world()
        val recorder = ChangeRecorder()
        val result = AdminWorldSettingsHandler(world, recorder).handle(
            TurnDaemonCommand.AdminWorldSettings(
                settings = listOf(AdminWorldSetting("unknown", intValue = 1)),
            ),
        )

        assertFalse(result.ok)
        assertEquals("OPEN", world.getState().status)
        assertTrue(world.getState().config.isEmpty())
        assertTrue(recorder.kvDirty().isEmpty())
    }

    private fun world() = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1,
                currentYear = 200,
                currentMonth = 1,
                tickSeconds = 3_600,
                lastTurnTime = Instant.parse("0200-01-01T00:00:00Z"),
            ),
        ),
    )
}

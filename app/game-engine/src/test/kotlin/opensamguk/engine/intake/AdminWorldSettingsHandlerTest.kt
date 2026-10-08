package opensamguk.engine.intake

import opensamguk.common.wire.AdminWorldSetting
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.KvKey
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

    @Test
    fun `disabling creation block records numeric game-env in the same write set`() {
        assertBlockTransition(1, 0)
    }

    @Test
    fun `enabling creation block records numeric game-env in the same write set`() {
        assertBlockTransition(0, 1)
    }

    @Test
    fun `unrelated admin setting leaves creation block and its KV untouched`() {
        val world = blockWorld(1)
        val recorder = ChangeRecorder()
        assertTrue(AdminWorldSettingsHandler(world, recorder).handle(
            TurnDaemonCommand.AdminWorldSettings(settings = listOf(AdminWorldSetting("npcmode", intValue = 2))),
        ).ok)
        assertEquals(1, world.getState().config["block_general_create"])
        assertEquals(1, world.getState().meta["block_general_create"])
        assertEquals(50, world.getState().config["maxgeneral"])
        assertEquals(2, world.getState().config["npcmode"])
        assertTrue(recorder.kvDirty().isEmpty())
    }

    @Test
    fun `invalid admin batch never partially changes creation block or KV`() {
        val invalidCommands = listOf(
            TurnDaemonCommand.AdminWorldSettings(status = "INVALID", settings = listOf(AdminWorldSetting("block_general_create", intValue = 0))),
            TurnDaemonCommand.AdminWorldSettings(settings = listOf(AdminWorldSetting("block_general_create", intValue = 0), AdminWorldSetting("unknown", intValue = 1))),
            TurnDaemonCommand.AdminWorldSettings(settings = listOf(AdminWorldSetting("block_general_create", intValue = 0), AdminWorldSetting("maxgeneral", intValue = 50, stringValue = "50"))),
            TurnDaemonCommand.AdminWorldSettings(settings = listOf(AdminWorldSetting("block_general_create", intValue = 0), AdminWorldSetting("maxgeneral"))),
        )
        for (command in invalidCommands) {
            val world = blockWorld(1)
            val before = world.getState()
            val recorder = ChangeRecorder()
            assertFalse(AdminWorldSettingsHandler(world, recorder).handle(command).ok)
            assertEquals(before, world.getState())
            assertTrue(recorder.kvDirty().isEmpty())
        }
    }

    private fun assertBlockTransition(initial: Int, next: Int) {
        val world = blockWorld(initial)
        val recorder = ChangeRecorder()
        val before = world.getState()
        assertTrue(AdminWorldSettingsHandler(world, recorder).handle(
            TurnDaemonCommand.AdminWorldSettings(settings = listOf(AdminWorldSetting("block_general_create", intValue = next))),
        ).ok)
        assertEquals(before.config + ("block_general_create" to next), world.getState().config)
        assertEquals(before.meta + ("block_general_create" to next), world.getState().meta)
        assertEquals(before.status, world.getState().status)
        assertEquals(before.tickSeconds, world.getState().tickSeconds)
        assertEquals(mapOf(KvKey("game_env", "game_env", "block_general_create") to next), recorder.kvDirty())
    }

    private fun blockWorld(initial: Int) = InMemoryTurnWorld(WorldSnapshot(
        state = TurnWorldState(
            id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3_600,
            lastTurnTime = Instant.parse("0200-01-01T00:00:00Z"),
            config = mapOf("block_general_create" to initial, "maxgeneral" to 50),
            meta = mapOf("block_general_create" to initial, "unchanged" to "preserved"),
        ),
        worldId = opensamguk.common.world.WorldId(1),
    ))

    private fun world() = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1,
                currentYear = 200,
                currentMonth = 1,
                tickSeconds = 3_600,
                lastTurnTime = Instant.parse("0200-01-01T00:00:00Z"),
            ),
            worldId = opensamguk.common.world.WorldId((TurnWorldState(
                id = 1,
                currentYear = 200,
                currentMonth = 1,
                tickSeconds = 3_600,
                lastTurnTime = Instant.parse("0200-01-01T00:00:00Z"),
            )).id),
        ),
    )
}

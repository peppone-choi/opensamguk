package opensamguk.engine.intake

import opensamguk.common.wire.GeneralBoolResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaimNpcHandlerTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun world(general: TurnGeneral = npc()): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0),
            generals = listOf(general),
            nations = listOf(Nation(id = 1, name = "촉", color = "#0f0")),
        ),
    )

    private fun npc(
        id: Int = 10,
        userId: String? = null,
        npcState: Int = 2,
        meta: Map<String, Any?> = emptyMap(),
    ) = TurnGeneral(
        id = id,
        userId = userId,
        name = "여포",
        nationId = 1,
        cityId = 5,
        troopId = 0,
        stats = GeneralStats(90, 100, 70),
        experience = 0,
        dedication = 0,
        officerLevel = 0,
        npcState = npcState,
        turnTime = t0,
        meta = meta,
    )

    @Test
    fun `claim npc mutates possession state into the daemon flush payload`() {
        val world = world()
        val recorder = ChangeRecorder()
        val result = ClaimNpcHandler(world, recorder).handle(
            TurnDaemonCommand.ClaimNpc(
                generalId = 10,
                userId = 7L,
                userNick = "peppone",
                userPenaltyJson = """{"NoChief":true,"SendPrivateMsgDelay":3}""",
            ),
        ) as GeneralBoolResult

        assertEquals(true, result.ok)

        val dirty = world.consumeDirtyState()
        val payload = DatabaseHooks.toFlushPayload(world, recorder, dirty)
        assertEquals(1, payload.updatedGenerals.size)
        val updated = payload.updatedGenerals.single()
        assertEquals("7", updated.userId)
        assertEquals(1, updated.npcType)
        assertEquals(2402, updated.meta["pickYearMonth"])
        assertEquals("peppone", updated.meta["owner_name"])
        assertEquals(6, updated.meta["killturn"])
        assertEquals(80, updated.meta["defence_train"])
        assertEquals("normal", updated.meta["permission"])
        assertEquals(true, updated.penalty["NoChief"])
        assertEquals(3, updated.penalty["SendPrivateMsgDelay"])
        assertEquals(2, payload.logEntries.size)
    }

    @Test
    fun `claim npc rejects non claimable rows`() {
        val world = world(npc(userId = "99", npcState = 1))
        val result = ClaimNpcHandler(world, ChangeRecorder()).handle(
            TurnDaemonCommand.ClaimNpc(generalId = 10, userId = 7L, userNick = "peppone"),
        ) as GeneralBoolResult

        assertEquals(false, result.ok)
        assertEquals("빙의 가능한 장수가 아닙니다.", result.reason)
    }
}

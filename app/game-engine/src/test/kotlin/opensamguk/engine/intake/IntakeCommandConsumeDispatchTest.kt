package opensamguk.engine.intake

import opensamguk.common.wire.BoardActionResult
import opensamguk.common.wire.AcceptDiplomaticMessageFail
import opensamguk.common.wire.DeclineDiplomaticMessageFail
import opensamguk.common.wire.NationSettingResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.WireJson
import opensamguk.engine.run.TurnDaemonCommandDispatcher
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * F-INTAKE consumer-side test (no container) — proves the engine half of the seam:
 *
 *   wire `payload` bytes (the EXACT shape game-api XADDs) → [WireJson] decode (the same codec
 *   [opensamguk.engine.redis.RedisCommandStream.parseEnvelope] uses) → [TurnDaemonCommandDispatcher]
 *   → handler → world mutate + [ChangeRecorder] delta.
 *
 * This closes the round-trip the publisher-side `CommandWireMapperTest` opens: that test proves the
 * `{code, argJson}` → typed command + encode; this proves decode → dispatch → recorder delta. The
 * Redis transport between them is the thin `XADD`/`XREAD` already covered by `CommandReserveServiceIT`
 * + `RedisCommandStreamIT` (Docker-gated). With both unit halves green, the only Docker-gated piece is
 * the literal Redis hop.
 */
class IntakeCommandConsumeDispatchTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun world(): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0),
            generals = listOf(
                TurnGeneral(
                    id = 10, name = "유비", nationId = 1, cityId = 5, troopId = 0,
                    stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
                    officerLevel = 12, gold = 1000, turnTime = t0,
                ),
            ),
            nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", gold = 1000)),
            worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0)).id),
        ),
    )

    /** Encode a command exactly as the publisher does, then decode it exactly as the consumer does. */
    private fun decodeAsConsumer(command: TurnDaemonCommand): TurnDaemonCommand {
        val payload = WireJson.encodeToString(
            TurnDaemonCommandEnvelope.serializer(),
            TurnDaemonCommandEnvelope(requestId = "req-1", sentAt = "0200-01-01T00:00:00Z", command = command),
        )
        return WireJson.decodeFromString(TurnDaemonCommandEnvelope.serializer(), payload).command
    }

    /** No-op repo proxy — the intake/betting handlers under test never call a repo method. */
    private inline fun <reified T> noopRepo(): T = java.lang.reflect.Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.util.List::class.java -> emptyList<Any>()
            java.lang.Boolean.TYPE -> false
            else -> null
        }
    } as T

    private fun dispatcher(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        gameKv: opensamguk.infra.read.GameKvRepository? = null,
    ) = TurnDaemonCommandDispatcher(
        world, recorder,
        noopRepo<opensamguk.infra.read.BoardPostRepository>(),
        gameKvRepository = gameKv,
    )

    @Test
    fun `setRate payload decodes and dispatches to NationFinanceSetterHandler writing the dirty nation`() {
        val world = world()
        val recorder = ChangeRecorder()

        val decoded = decodeAsConsumer(TurnDaemonCommand.SetRate(requestId = "req-rate", generalId = 10, amount = 25))
        val result = dispatcher(world, recorder).dispatch(decoded) as NationSettingResult

        assertTrue(result.ok)
        assertEquals("setRate", result.type)
        assertEquals(25, world.getNationById(1)!!.meta["rate"])
        assertEquals(setOf(1), recorder.dirtyNationIds())
    }

    @Test
    fun `newVote payload decodes and dispatches to VoteHandler producing the vote_poll delta`() {
        // 새 설문조사를 개설할 수 있는 vote-admin(userGrade>=5) 장수.
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0),
                generals = listOf(
                    TurnGeneral(
                        id = 10, name = "유비", nationId = 1, cityId = 5, troopId = 0,
                        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
                        officerLevel = 12, gold = 1000, turnTime = t0, meta = mapOf("userGrade" to 5),
                    ),
                ),
                nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", gold = 1000)),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0)).id),
            ),
        )
        val recorder = ChangeRecorder()

        val decoded = decodeAsConsumer(
            TurnDaemonCommand.NewVote(
                requestId = "req-vote", generalId = 10, title = "다음 천도지?",
                options = listOf("성도", "한중"), multipleOptions = 1,
            ),
        )
        val result = dispatcher(world, recorder).dispatch(decoded) as BoardActionResult

        assertTrue(result.ok)
        assertEquals("newVote", result.type)
        val rows = recorder.votePollInserts()
        assertEquals(1, rows.size)
        assertEquals("다음 천도지?", rows.single().columns["title"])
        assertEquals(1, world.getGeneralById(10)!!.meta["newvote"]) // 전 장수 newvote=1
    }

    @Test
    fun `diplomatic message variants never fall through the dispatcher`() {
        val world = world()
        val recorder = ChangeRecorder()
        val dispatcher = dispatcher(world, recorder)

        val accept = dispatcher.dispatch(
            decodeAsConsumer(TurnDaemonCommand.AcceptDiplomaticMessage(messageId = 77, generalId = 10)),
        )
        val decline = dispatcher.dispatch(
            decodeAsConsumer(TurnDaemonCommand.DeclineDiplomaticMessage(messageId = 77, generalId = 10)),
        )

        assertIs<AcceptDiplomaticMessageFail>(accept)
        assertEquals(77, accept.messageId)
        assertIs<DeclineDiplomaticMessageFail>(decline)
        assertEquals(77, decline.messageId)
    }
}

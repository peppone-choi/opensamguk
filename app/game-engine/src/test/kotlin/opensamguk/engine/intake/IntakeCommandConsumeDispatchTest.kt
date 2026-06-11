package opensamguk.engine.intake

import opensamguk.common.wire.BoardActionResult
import opensamguk.common.wire.NationSettingResult
import opensamguk.common.wire.PlaceBetOk
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
        noopRepo<opensamguk.infra.read.AuctionRepository>(),
        noopRepo<opensamguk.infra.read.AuctionBidRepository>(),
        noopRepo<opensamguk.infra.read.BoardPostRepository>(),
        gameKvRepository = gameKv,
    )

    /** game_kv(table='betting') 베팅 마스터를 공급하는 fake — P0-07 이후 PlaceBet은 마스터 검증이 선행된다. */
    private fun gameKvRepoWith(rows: List<opensamguk.infra.entity.GameKvEntity>): opensamguk.infra.read.GameKvRepository =
        java.lang.reflect.Proxy.newProxyInstance(
            opensamguk.infra.read.GameKvRepository::class.java.classLoader,
            arrayOf(opensamguk.infra.read.GameKvRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "findByTable" -> rows.filter { it.table == args!![0] }
                else -> when (method.returnType) {
                    java.util.List::class.java -> emptyList<Any>()
                    java.lang.Boolean.TYPE -> false
                    else -> null
                }
            }
        } as opensamguk.infra.read.GameKvRepository

    @Test
    fun `placeBet payload decodes and dispatches to PlaceBetHandler producing the ng_betting delta`() {
        val world = world()
        val recorder = ChangeRecorder()

        // game_kv 베팅 마스터 — P0-07 이후 bet()의 마스터/마감/후보 검증이 선행된다(Betting.php:100-131).
        // state(200,3) → ym 2402: open 2400 ≤ ym < close 2500, 후보 {1,3}, selectCnt 2.
        val gameKv = gameKvRepoWith(
            listOf(
                opensamguk.infra.entity.GameKvEntity(
                    table = "betting", namespace = "betting", key = "id_7",
                    value = """{"id":7,"type":"bettingNation","name":"천통 베팅","finished":false,""" +
                        """"selectCnt":2,"reqInheritancePoint":false,"openYearMonth":2400,""" +
                        """"closeYearMonth":2500,"candidates":{"1":{"title":"위"},"3":{"title":"오"}}}""",
                ),
            ),
        )

        // the wire command the publisher (CommandWireMapper) would build + XADD.
        val decoded = decodeAsConsumer(
            TurnDaemonCommand.PlaceBet(
                requestId = "req-bet", bettingId = 7, generalId = 10, bettingType = listOf(1, 3), amount = 500,
            ),
        )

        val result = dispatcher(world, recorder, gameKv = gameKv).dispatch(decoded)

        // handler ran → ok result + gold deducted + ng_betting INSERT recorded.
        val bet = result as PlaceBetOk
        assertEquals(7, bet.bettingId)
        assertEquals(10, bet.generalId)
        assertEquals(500, world.getGeneralById(10)!!.gold) // 1000 - 500 deducted
        val inserts = recorder.bettingInserts()
        assertEquals(1, inserts.size)
        assertEquals(7, inserts.single().columns["betting_id"])
        assertEquals(500, inserts.single().columns["amount"])
    }

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
    fun `tournamentEnroll payload decodes and dispatches writing the general tnmt`() {
        val world = world()
        val recorder = ChangeRecorder()

        val decoded = decodeAsConsumer(TurnDaemonCommand.TournamentEnroll(requestId = "r", generalId = 10, value = 1))
        val result = dispatcher(world, recorder).dispatch(decoded) as NationSettingResult

        assertTrue(result.ok)
        assertEquals(1, world.getGeneralById(10)!!.meta["tnmt"])
        assertEquals(setOf(10), recorder.dirtyGeneralIds())
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
}

package opensamguk.engine.tournament

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.common.rng.PhpMt19937
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.KvKey
import opensamguk.engine.turn.RankColumn
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.entity.GameKvEntity
import opensamguk.infra.read.GameKvRepository
import opensamguk.logic.tournament.TournamentBettingPort
import opensamguk.logic.tournament.TournamentEntry
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TournamentDaemonFightAdapterTest {
    private val mapper = ObjectMapper()
    private val now = Instant.parse("0200-03-01T00:00:00Z")

    @Test
    fun `daemon adapts fight logs entries and rank deltas into ChangeRecorder`() {
        val entries = listOf(
            entry(1, "관우", 90, 88, 85, 50, 0),
            entry(2, "하후돈", 60, 58, 55, 40, 1),
        )
        val rows = listOf(
            kv("tournament", "10"),
            kv("phase", "0"),
            kv("tnmt_type", "0"),
            kv("tnmt_auto", "true"),
            kv("tnmt_time", mapper.writeValueAsString(now.toString())),
            kv("turnterm", "5"),
            kv("last_tournament_betting_id", "7"),
            kv("tournament_entries", mapper.writeValueAsString(entries)),
            kv("tournament_logs", """{"49":["old"]}"""),
        )
        val paid = mutableListOf<Pair<Int, Int>>()
        val daemon = TournamentDaemon(
            gameKvRepository = repository(rows),
            bettingFactory = { _, _ ->
                object : TournamentBettingPort {
                    override fun open(type: Int, unitSeconds: Int, finalists: List<TournamentEntry>): Int = 0
                    override fun close(bettingId: Int) = Unit
                    override fun refund(bettingId: Int) = Unit
                    override fun payout(bettingId: Int, winnerId: Int) {
                        paid += bettingId to winnerId
                    }
                }
            },
            randomFactory = { PhpMt19937(1) },
        )
        val recorder = ChangeRecorder()

        val result = daemon.processTournament(world(), recorder, now)

        assertEquals(0, result.state.tournament)
        assertEquals(1, paid.size)
        assertNotNull(recorder.kvDirty()[KvKey("game_env", "game_env", "tournament_entries")])
        val logs = recorder.kvDirty()[KvKey("game_env", "game_env", "tournament_logs")] as Map<*, *>
        assertEquals(listOf("old"), logs[49])
        assertTrue((logs[50] as List<*>).isNotEmpty())
        assertTrue(
            recorder.rankDeltas(1).isNotEmpty() || recorder.rankDeltas(2).isNotEmpty(),
        )
        assertTrue(
            recorder.rankDeltas(1).keys.any { it in setOf(RankColumn.TTW, RankColumn.TTL, RankColumn.TTG) } ||
                recorder.rankDeltas(2).keys.any { it in setOf(RankColumn.TTW, RankColumn.TTL, RankColumn.TTG) },
        )
    }

    private fun world(): InMemoryTurnWorld =
        InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(1, 200, 3, 300, now),
                generals = listOf(
                    general(1, "관우", 90, 88, 85, 50, 5),
                    general(2, "하후돈", 60, 58, 55, 40, 0),
                ),
                worldId = WorldId(1),
            ),
        )

    private fun general(
        id: Int,
        name: String,
        leadership: Int,
        strength: Int,
        intelligence: Int,
        level: Int,
        rankGoal: Int,
    ): TurnGeneral =
        TurnGeneral(
            id = id,
            name = name,
            nationId = 0,
            cityId = 0,
            troopId = 0,
            stats = GeneralStats(leadership, strength, intelligence),
            experience = 0,
            dedication = 0,
            officerLevel = 0,
            gold = 0,
            turnTime = now,
            role = GeneralRole(),
            meta = mapOf("explevel" to level, "ttg" to rankGoal),
        )

    private fun entry(
        id: Int,
        name: String,
        leadership: Int,
        strength: Int,
        intelligence: Int,
        level: Int,
        groupNo: Int,
    ): TournamentEntry =
        TournamentEntry(
            id = id,
            npc = 0,
            name = name,
            leadership = leadership,
            strength = strength,
            intel = intelligence,
            level = level,
            group = 50,
            groupNo = groupNo,
            seq = id,
        )

    private fun kv(key: String, value: String): GameKvEntity =
        GameKvEntity(table = "game_env", namespace = "game_env", key = key, value = value)

    private fun repository(rows: List<GameKvEntity>): GameKvRepository =
        Proxy.newProxyInstance(
            GameKvRepository::class.java.classLoader,
            arrayOf(GameKvRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "findByTable" -> rows
                else -> null
            }
        } as GameKvRepository
}

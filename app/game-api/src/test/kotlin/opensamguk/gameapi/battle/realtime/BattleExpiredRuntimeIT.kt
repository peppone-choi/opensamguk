package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.BattlePacingMode
import opensamguk.infra.battle.realtime.BattleSessionPhase
import opensamguk.infra.battle.realtime.BattleSessionRef
import opensamguk.infra.battle.realtime.BattleTransition
import opensamguk.infra.battle.realtime.FrozenBattleParticipant
import opensamguk.infra.battle.realtime.FrozenBattleTicket
import opensamguk.infra.battle.realtime.JdbcBattleSessionDiscovery
import opensamguk.infra.battle.realtime.JdbcBattleSessionStore
import opensamguk.logic.battle.realtime.BattleDeployment
import opensamguk.logic.battle.realtime.BattleSide
import opensamguk.logic.battle.realtime.Battlefield
import opensamguk.logic.battle.realtime.GeneralStats
import opensamguk.logic.battle.realtime.Retinue
import opensamguk.logic.battle.realtime.TacticalBattle
import opensamguk.logic.battle.realtime.UnitKind
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer

/** Database discovery through the actor and result outbox, including a crash after timeout CAS. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BattleExpiredRuntimeIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var store: JdbcBattleSessionStore
    private lateinit var discovery: JdbcBattleSessionDiscovery
    private val world = WorldId(1)
    private val battleId = "battle-expired-runtime-it"

    @BeforeAll
    fun setup() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — PostgreSQL integration test skipped",
        )
        postgres.start()
        val dataSource: DataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl; username = postgres.username; password = postgres.password
        }
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = NamedParameterJdbcTemplate(dataSource)
        store = JdbcBattleSessionStore(jdbc, dataSource)
        discovery = JdbcBattleSessionDiscovery(jdbc)
        jdbc.update("""
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
            VALUES (1, 'battle-expired-runtime-it', 200, 1, 3600)
        """.trimIndent(), MapSqlParameterSource())
    }

    @AfterAll
    fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    @Test
    fun `expired realtime running session is rediscovered scored and published after another actor crash`() {
        val now = Instant.now()
        val ticket = FrozenBattleTicket(world, battleId, "{}", sha("{}"), "a".repeat(64),
            "b".repeat(64), "c".repeat(64), 17, 4, 2,
            now.minusSeconds(5), now.plusSeconds(300),
            listOf(FrozenBattleParticipant(1, 42, 1, "ATTACKER", 0)))
        assertTrue(store.create(ticket))
        val first = assertNotNull(store.claimEpoch(world, battleId, "actor-first", 30_000))
        assertTrue(store.startRun(world, battleId, "actor-first", first.sessionEpoch))
        val initial = initialState()
        val firstTick = assertIs<BattleTickAttempt.Advanced>(BattleSessionTickRunner(store,
            { initial }, world, battleId, "actor-first", first.sessionEpoch, BattlePacingMode.REALTIME).tick())
        assertEquals(1, firstTick.state.tick)
        val futureJoin = """{"schemaVersion":1,"side":"ATTACKER"}"""
        assertEquals(3L, store.appendTransition(BattleTransition(world, battleId,
            first.sessionEpoch, "actor-first", "future-join", "HUMAN_JOIN", 1,
            1, 2, futureJoin, sha(futureJoin))))
        expireDeadlineAndLease()
        val ref = BattleSessionRef(world, battleId)
        assertTrue(ref in discovery.claimable(10))
        assertFalse(store.advanceTick(world, battleId, "actor-first", first.sessionEpoch, 1, 3))

        val timer = ManualTimer()
        val cadence = BattleSessionCadence(store, timer)
        val firstResult = mutableListOf<BattleTickAttempt>()
        try {
            val bootstrap = BattleSessionBootstrap(discovery, store, cadence, { initial },
                "actor-recover-a", { _, attempt -> firstResult += attempt }, { _, failure -> throw failure })
            assertEquals(1, bootstrap.scan())
            timer.fire()
            val timedOut = assertIs<BattleTickAttempt.Resolved>(firstResult.single())
            assertEquals(BattleResolutionKind.TIMEOUT_SCORE, timedOut.resolution)
            assertEquals(1, timedOut.state.tick)
            assertEquals(3L, timedOut.eventSeq)
            assertEquals(TacticalBattle.timeoutOutcome(firstTick.state), timedOut.state.outcome)
            assertEquals(emptySet(), timedOut.state.humanSides)
            assertEquals(BattleSessionPhase.RESOLVING, store.head(world, battleId)?.phase)
            assertTrue(store.pendingResults(world, 10).none { it.battleId == battleId })
        } finally { cadence.close() }

        expireLease()
        val timerAfterCrash = ManualTimer()
        val cadenceAfterCrash = BattleSessionCadence(store, timerAfterCrash)
        try {
            val publisher = BattleSessionResultPublisher(store)
            val bootstrap = BattleSessionBootstrap(discovery, store, cadenceAfterCrash, { initial },
                "actor-recover-b", { key, attempt ->
                    if (attempt is BattleTickAttempt.Resolved) assertTrue(publisher.publish(key, attempt))
                }, { _, failure -> throw failure })
            assertEquals(1, bootstrap.scan())
            timerAfterCrash.fire()
            val result = store.pendingResults(world, 10).single { it.battleId == battleId }
            val json = Json.parseToJsonElement(result.resultJson).jsonObject
            assertEquals("DEFENDER", json.getValue("outcome").jsonPrimitive.content)
            assertEquals("TIMEOUT_SCORE", json.getValue("resolution").jsonPrimitive.content)
            assertEquals("1", json.getValue("tick").jsonPrimitive.content)
            assertEquals(BattleSessionPhase.RESULT_PENDING, store.head(world, battleId)?.phase)
            assertFalse(store.resolveTimeout(world, battleId, "actor-recover-a",
                first.sessionEpoch + 1, 1, 3))
            assertEquals(1, store.pendingResults(world, 10).count { it.battleId == battleId })
        } finally { cadenceAfterCrash.close() }
    }

    private fun initialState() = TacticalBattle.start(17,
        Battlefield(192, "FIELD", List(64) { "P".repeat(64) }),
        BattleDeployment.default(BattleSide.ATTACKER, 1, listOf(retinue(1))),
        BattleDeployment.default(BattleSide.DEFENDER, 2, listOf(retinue(2))))

    private fun retinue(id: Int) = Retinue(id, GeneralStats(id, 70, 70, 70, 70, 70), 100,
        UnitKind.INFANTRY, 50, 90, 0, 100, true)

    private fun expireDeadlineAndLease() {
        jdbc.update("""
            UPDATE battle_session SET deadline_at = clock_timestamp() - interval '1 second',
                                      lease_until = clock_timestamp() - interval '1 second'
             WHERE world_id = 1 AND battle_id = :battle_id
        """.trimIndent(), MapSqlParameterSource().addValue("battle_id", battleId))
    }

    private fun expireLease() {
        jdbc.update("""
            UPDATE battle_session SET lease_until = clock_timestamp() - interval '1 second'
             WHERE world_id = 1 AND battle_id = :battle_id
        """.trimIndent(), MapSqlParameterSource().addValue("battle_id", battleId))
    }

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private class ManualTimer : BattleIntervalTimer {
        private lateinit var task: () -> Unit
        override fun schedule(periodMillis: Long, task: () -> Unit): AutoCloseable {
            assertEquals(100L, periodMillis)
            this.task = task
            return AutoCloseable { }
        }
        fun fire() = task()
    }
}

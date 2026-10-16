package opensamguk.infra.battle.realtime

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.logic.battle.realtime.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer

/** Real PostgreSQL transactions, a new store instance for restart replay, and concurrent admissions. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcBattleV2DeploymentMoveIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var db: NamedParameterJdbcTemplate
    private lateinit var ds: DriverManagerDataSource
    private lateinit var store: JdbcBattleSessionStore
    private val world = WorldId(1)
    private val own = TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "701")
    private val ally = TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "702")
    private val ownTwo = TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "703")
    private val initial = TacticalV2Deployment(0, "c".repeat(64), listOf(
        TacticalV2UnitSource(own, BattleSide.ATTACKER, 7, 7, 100, 0),
        TacticalV2UnitSource(ally, BattleSide.ATTACKER, 8, 8, 100, 0),
        TacticalV2UnitSource(ownTwo, BattleSide.ATTACKER, 7, 7, 100, 0)),
        mapOf(own to TacticalV2Cell(0, 0), ally to TacticalV2Cell(0, 1), ownTwo to TacticalV2Cell(0, 2)),
        mapOf(BattleSide.ATTACKER to (0..5).map { TacticalV2Cell(0, it) }.toSet(),
            BattleSide.DEFENDER to setOf(TacticalV2Cell(63, 63))))

    @BeforeAll
    fun setup() {
        org.junit.jupiter.api.Assumptions.assumeTrue(runCatching {
            org.testcontainers.DockerClientFactory.instance().isDockerAvailable
        }.getOrDefault(false), "Docker unavailable — PostgreSQL integration test skipped")
        postgres = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
        ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        db = NamedParameterJdbcTemplate(ds)
        store = JdbcBattleSessionStore(db, ds)
        db.update("""INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
            VALUES (1, 'v2-deployment-it', 200, 1, 3600)""", MapSqlParameterSource())
    }

    @AfterAll
    fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    private fun prepare(id: String): Long {
        val now = Instant.now()
        val payload = "{}"
        store.create(FrozenBattleTicket(world, id, payload, sha(payload), "a".repeat(64),
            "b".repeat(64), "c".repeat(64), 1, 1, 1, now.plusSeconds(120), now.plusSeconds(300),
            listOf(FrozenBattleParticipant(1, 42, 7, "ATTACKER", 3),
                FrozenBattleParticipant(2, 43, 8, "ATTACKER", 4))))
        return assertNotNull(store.claimEpoch(world, id, "actor", 60_000)).sessionEpoch
    }

    private fun command(battle: String, epoch: Long, id: String = "move", source: TacticalV2SourceKey = own,
                        target: TacticalV2Cell = TacticalV2Cell(0, 3), revision: Long = 0,
                        authority: Long = 3): BattleCommandRecord {
        val intent = "$id|$source|$target|$revision|$epoch|$authority"
        return BattleCommandRecord(world, battle, 1, id, sha(intent), epoch, authority, 0,
            "ATTACKER", intent, BattleDeploymentMove(initial, source, target, revision, setOf(own, ownTwo)))
    }
    private fun receipt(command: BattleCommandRecord) =
        (store.admit(command) as CommandAdmission.Receipt).value
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `accepted move and first receipt survive restart and replay even after phase closes`() {
        val battle = "restart"
        val cmd = command(battle, prepare(battle))
        val beforeHead = store.head(world, battle)
        val accepted = receipt(cmd)
        assertEquals(BattleCommandVerdict.ACCEPTED, accepted.verdict)
        assertEquals(1L, accepted.deploymentRevisionAfter)
        assertEquals(TacticalV2Cell(0, 3), store.deployment(world, battle, initial).cells[own])
        assertEquals(beforeHead, store.head(world, battle))
        store = JdbcBattleSessionStore(db, ds)
        db.update("UPDATE battle_session SET phase = 'RUNNING' WHERE battle_id = :id",
            MapSqlParameterSource("id", battle))
        assertEquals(accepted.copy(replayed = true), receipt(cmd))
        assertEquals(CommandAdmission.IdempotencyConflict,
            store.admit(command(battle, cmd.expectedEpoch, target = TacticalV2Cell(0, 4))))
        assertEquals(1L, store.deployment(world, battle, initial).revision)
    }

    @Test
    fun `every rejected move preserves head deployment and event tail and replays durably`() {
        val battle = "rejections"
        val epoch = prepare(battle)
        val cases = listOf(
            "UNAUTHORIZED" to command(battle, epoch, "foreign", source = ally),
            "UNAUTHORIZED" to command(battle, epoch, "missing", source =
                TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "999")),
            "UNAUTHORIZED" to command(battle, epoch, "garrison", source =
                TacticalV2SourceKey(TacticalV2SourceKind.CITY_GARRISON_BUGOK, "1", 9)),
            "STALE_DEPLOYMENT" to command(battle, epoch, "revision", revision = 1),
            "STALE_EPOCH" to command(battle, epoch + 1, "epoch"),
            "STALE_AUTHORITY" to command(battle, epoch, "authority", authority = 4),
            "INVALID_SPAWN" to command(battle, epoch, "outside", target = TacticalV2Cell(1, 1)),
            "INVALID_SPAWN" to command(battle, epoch, "occupied-foreign", target = TacticalV2Cell(0, 1)),
            "INVALID_SPAWN" to command(battle, epoch, "occupied-own", target = TacticalV2Cell(0, 2)))
        val head = store.head(world, battle)
        for ((reason, cmd) in cases) {
            val rejected = receipt(cmd)
            assertEquals(BattleCommandVerdict.REJECTED, rejected.verdict)
            assertEquals(reason, rejected.reasonCode)
            assertNull(rejected.deploymentRevisionAfter)
            assertEquals(initial, store.deployment(world, battle, initial))
            assertEquals(head, store.head(world, battle))
            assertTrue(store.eventsAfter(world, battle, 0).isEmpty())
            store = JdbcBattleSessionStore(db, ds)
            assertEquals(rejected.copy(replayed = true), receipt(cmd))
        }
        assertEquals(0, db.queryForObject("SELECT count(*) FROM battle_v2_deployment WHERE battle_id = :id",
            MapSqlParameterSource("id", battle), Int::class.java))
    }

    @Test
    fun `joining phase deadline and lease close admission without moving a unit`() {
        for (mutation in listOf("phase = 'RUNNING'", "join_deadline_at = clock_timestamp() - interval '1 second'",
            "lease_until = clock_timestamp() - interval '1 second'")) {
            val battle = "closed-${mutation.substringBefore(' ')}"
            val epoch = prepare(battle)
            db.update("UPDATE battle_session SET $mutation WHERE battle_id = :id", MapSqlParameterSource("id", battle))
            assertEquals("SESSION_CLOSED", receipt(command(battle, epoch)).reasonCode)
            assertEquals(initial, store.deployment(world, battle, initial))
        }
    }

    @Test
    fun `concurrent same revision moves commit exactly one placement and duplicate IDs replay once`() {
        val battle = "concurrent"
        val epoch = prepare(battle)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val candidates = listOf(command(battle, epoch, "a"),
                command(battle, epoch, "b", target = TacticalV2Cell(0, 4)))
            val results = pool.invokeAll(candidates.map { cmd -> Callable { receipt(cmd) } }).map { it.get() }
            assertEquals(1, results.count { it.verdict == BattleCommandVerdict.ACCEPTED })
            assertEquals(1, results.count { it.reasonCode == "STALE_DEPLOYMENT" })
            val winner = candidates[results.indexOfFirst { it.verdict == BattleCommandVerdict.ACCEPTED }]
            val replays = pool.invokeAll(List(2) { Callable { receipt(winner) } }).map { it.get() }
            assertTrue(replays.all { it.replayed && it.deploymentRevisionAfter == 1L })
            assertEquals(1L, store.deployment(world, battle, initial).revision)
        } finally { pool.shutdownNow() }
    }
    @Test
    fun `simultaneous duplicate IDs preserve the first receipt exactly once`() {
        val battle = "duplicate-race"
        val cmd = command(battle, prepare(battle))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(List(2) { Callable { receipt(cmd) } }).map { it.get() }
            assertEquals(1, results.count { !it.replayed })
            assertEquals(results.first().copy(replayed = true), results.last().copy(replayed = true))
            assertEquals(1L, store.deployment(world, battle, initial).revision)
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `v1 accepted receipt still requires its real combat event and cannot borrow a placement revision`() {
        val battle = "receipt-invariants"
        prepare(battle)
        for ((id, revision, effective) in listOf(Triple("missing-event", null, null),
            Triple("mixed-event-placement", 1L, 1), Triple("invalid-deployment-tick", 1L, null))) {
            assertFailsWith<org.springframework.dao.DataIntegrityViolationException> {
                db.update("""INSERT INTO battle_command_receipt (world_id, battle_id, participant_id,
                    client_command_id, intent_sha256, verdict, reason_code, server_tick,
                    effective_tick, event_seq, authority_revision, deployment_revision_after)
                    VALUES (1, :id, 1, :client_id, :sha, 'ACCEPTED', NULL, :tick, :effective, NULL, 3, :revision)""",
                    MapSqlParameterSource("id", battle).addValue("client_id", id)
                        .addValue("sha", "a".repeat(64)).addValue("revision", revision)
                        .addValue("effective", effective).addValue("tick", if (id == "invalid-deployment-tick") 1 else 0))
            }
        }
        assertEquals(initial, store.deployment(world, battle, initial))
    }

}

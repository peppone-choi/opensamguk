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
class JdbcBattleV2CommandIT {
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
            VALUES (1, 'v2-command-it', 200, 1, 3600)""", MapSqlParameterSource())
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

    private fun command(battle: String, epoch: Long, id: String = "cmd", tick: Int = 100,
                        authority: Long = 3, keys: List<TacticalV2SourceKey>? = listOf(own),
                        order: String = "ATTACK", rally: String = "CENTER",
                        controlled: Set<TacticalV2SourceKey> = setOf(own, ownTwo)): BattleCommandRecord {
        val intent = "$id|$keys|$order|$rally|$epoch|$authority|$tick"
        return BattleCommandRecord(world, battle, 1, id, sha(intent), epoch, authority, tick,
            "ATTACKER", intent, scopedCommand = BattleV2CommandAdmission(initial, controlled, keys, order, rally))
    }
    private fun running(battle: String): Long = prepare(battle).also {
        db.update("UPDATE battle_session SET phase = 'RUNNING', current_tick = 100 WHERE battle_id = :id",
            MapSqlParameterSource("id", battle))
    }
    private fun receipt(command: BattleCommandRecord) =
        (store.admit(command) as CommandAdmission.Receipt).value
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `v2 COMMAND receipt is accepted without a v1 event or combat state change`() {
        val battle = "v2-command-red"
        val epoch = prepare(battle)
        db.update("UPDATE battle_session SET phase = 'RUNNING', current_tick = 10 WHERE battle_id = :id",
            MapSqlParameterSource("id", battle))
        val before = store.head(world, battle)
        val intent = "v2-command-red-intent"
        val record = BattleCommandRecord(world, battle, 1, "cmd", sha(intent), epoch, 3, 10,
            "ATTACKER", intent, scopedCommand = BattleV2CommandAdmission(initial,
                setOf(own, ownTwo), listOf(own), "ATTACK", "CENTER"))
        val accepted = receipt(record)
        assertEquals(BattleCommandVerdict.ACCEPTED, accepted.verdict)
        assertNull(accepted.eventSeq)
        assertNull(accepted.effectiveTick)
        assertNull(accepted.deploymentRevisionAfter)
        assertEquals(before, store.head(world, battle))
        assertTrue(store.eventsAfter(world, battle, 0).isEmpty())
        store = JdbcBattleSessionStore(db, ds)
        assertEquals(accepted.copy(replayed = true), receipt(record))
    }
    @Test
    fun `all approved intents and rally values accept explicit and allMine scopes without effects`() {
        val battle = "v2-approved"
        val epoch = running(battle)
        val before = store.head(world, battle)
        for (order in listOf("CHARGE", "ATTACK", "FORMATION", "DEFEND", "WALL", "RETREAT")) {
            for (rally in listOf("HOME", "CENTER", "ENEMY")) {
                for (keys in listOf(listOf(own, ownTwo), null)) {
                    val cmd = command(battle, epoch, "$order-$rally-${keys == null}", keys = keys, order = order, rally = rally)
                    val accepted = receipt(cmd)
                    assertEquals(BattleCommandVerdict.ACCEPTED, accepted.verdict)
                    assertNull(accepted.effectiveTick)
                    assertNull(accepted.eventSeq)
                    assertNull(accepted.deploymentRevisionAfter)
                    store = JdbcBattleSessionStore(db, ds)
                    assertEquals(accepted.copy(replayed = true), receipt(cmd))
                }
            }
        }
        assertEquals(before, store.head(world, battle))
        assertEquals(initial, store.deployment(world, battle, initial))
        assertTrue(store.eventsAfter(world, battle, 0).isEmpty())
    }

    @Test
    fun `every stale scope and unsupported rejection is durable and all or nothing`() {
        val battle = "v2-invalid"
        val epoch = running(battle)
        val before = store.head(world, battle)
        val cases = listOf(
            "STALE_EPOCH" to command(battle, epoch + 1, "epoch"),
            "STALE_AUTHORITY" to command(battle, epoch, "authority", authority = 4),
            "STALE_TICK" to command(battle, epoch, "future", tick = 101),
            "STALE_TICK" to command(battle, epoch, "old", tick = 100 - TacticalRules.CANON.commandIssuedTickMaxLag - 1),
            "INVALID_SCOPE" to command(battle, epoch, "empty", keys = emptyList()),
            "INVALID_SCOPE" to command(battle, epoch, "duplicate", keys = listOf(own, own)),
            "INVALID_SCOPE" to command(battle, epoch, "ally", keys = listOf(own, ally)),
            "INVALID_SCOPE" to command(battle, epoch, "unknown", keys = listOf(TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "999"))),
            "INVALID_SCOPE" to command(battle, epoch, "city", keys = listOf(TacticalV2SourceKey(TacticalV2SourceKind.CITY_GARRISON_BUGOK, "1", 9))),
            "INVALID_SCOPE" to command(battle, epoch, "empty-allMine", keys = null, controlled = emptySet()),
            "UNSUPPORTED_INTENT" to command(battle, epoch, "intent", order = "TELEPORT"),
            "UNSUPPORTED_INTENT" to command(battle, epoch, "rally", rally = "NOWHERE"))
        for ((reason, cmd) in cases) {
            val rejected = receipt(cmd)
            assertEquals(BattleCommandVerdict.REJECTED, rejected.verdict)
            assertEquals(reason, rejected.reasonCode)
            store = JdbcBattleSessionStore(db, ds)
            assertEquals(rejected.copy(replayed = true), receipt(cmd))
            assertEquals(before, store.head(world, battle))
            assertEquals(initial, store.deployment(world, battle, initial))
            assertTrue(store.eventsAfter(world, battle, 0).isEmpty())
        }
        val boundary = command(battle, epoch, "lag-boundary", tick = 100 - TacticalRules.CANON.commandIssuedTickMaxLag)
        assertEquals(BattleCommandVerdict.ACCEPTED, receipt(boundary).verdict)
        assertEquals(CommandAdmission.IdempotencyConflict,
            store.admit(command(battle, epoch, "empty", keys = listOf(own))))
    }

    @Test
    fun `closed phase expired lease and deadline reject but first receipts survive epoch and authority changes`() {
        val battle = "v2-restart"
        val epoch = running(battle)
        val cmd = command(battle, epoch)
        val accepted = receipt(cmd)
        for ((index, change) in listOf("phase = 'JOINING'", "phase = 'APPLIED'",
            "lease_until = clock_timestamp() - interval '1 second'",
            "deadline_at = clock_timestamp() - interval '1 second'").withIndex()) {
            val closed = "v2-closed-$index"
            val closedEpoch = running(closed)
            db.update("UPDATE battle_session SET $change WHERE battle_id = :id", MapSqlParameterSource("id", closed))
            assertEquals("SESSION_CLOSED", receipt(command(closed, closedEpoch)).reasonCode)
        }
        db.update("UPDATE battle_session SET phase = 'APPLIED', session_epoch = session_epoch + 1 WHERE battle_id = :id",
            MapSqlParameterSource("id", battle))
        db.update("UPDATE battle_participant SET authority_revision = 9 WHERE battle_id = :id", MapSqlParameterSource("id", battle))
        store = JdbcBattleSessionStore(db, ds)
        assertEquals(accepted.copy(replayed = true), receipt(cmd))
        assertEquals(CommandAdmission.IdempotencyConflict, store.admit(cmd.copy(intentJson = "changed", intentSha256 = sha("changed"))))
    }

    @Test
    fun `concurrent retransmission commits one receipt and different content conflicts`() {
        val battle = "v2-race"
        val cmd = command(battle, running(battle))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val result = pool.invokeAll(List(2) { Callable { receipt(cmd) } }).map { it.get() }
            assertEquals(1, result.count { !it.replayed })
            assertEquals(result.first().copy(replayed = true), result.last().copy(replayed = true))
            assertEquals(CommandAdmission.IdempotencyConflict, store.admit(command(battle, cmd.expectedEpoch, rally = "HOME")))
            assertTrue(store.eventsAfter(world, battle, 0).isEmpty())
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `receipt insertion failure rolls back without acceptance or partial state`() {
        val battle = "v2-rollback"
        val cmd = command(battle, running(battle))
        val before = store.head(world, battle)
        db.jdbcTemplate.execute("""CREATE FUNCTION reject_v2_receipt() RETURNS trigger LANGUAGE plpgsql AS '
            BEGIN IF NEW.battle_id = ''v2-rollback'' THEN RAISE EXCEPTION ''receipt failure''; END IF; RETURN NEW; END'""")
        db.jdbcTemplate.execute("CREATE TRIGGER reject_v2 BEFORE INSERT ON battle_command_receipt FOR EACH ROW EXECUTE FUNCTION reject_v2_receipt()")
        try {
            assertFailsWith<org.springframework.dao.DataAccessException> { receipt(cmd) }
            assertEquals(0, db.queryForObject("SELECT count(*) FROM battle_command_receipt WHERE battle_id = :id", MapSqlParameterSource("id", battle), Int::class.java))
            assertEquals(before, store.head(world, battle))
            assertEquals(initial, store.deployment(world, battle, initial))
        } finally {
            db.jdbcTemplate.execute("DROP TRIGGER reject_v2 ON battle_command_receipt")
            db.jdbcTemplate.execute("DROP FUNCTION reject_v2_receipt()")
        }
        assertFalse(receipt(cmd).replayed)
    }

    @Test
    fun `committed accepted and rejected receipts survive an actual PostgreSQL container restart`() {
        val battle = "v2-postgres-restart"
        val epoch = running(battle)
        val acceptedCmd = command(battle, epoch, "accepted")
        val rejectedCmd = command(battle, epoch, "rejected", keys = listOf(ally))
        val accepted = receipt(acceptedCmd)
        val rejected = receipt(rejectedCmd)
        val before = store.head(world, battle)
        val startedBefore = db.jdbcTemplate.queryForObject("SELECT pg_postmaster_start_time()::text", String::class.java)
        val docker = org.testcontainers.DockerClientFactory.instance().client()
        docker.restartContainerCmd(postgres.containerId).exec()
        // Docker may reassign a random published port; GenericContainer retains the original inspect snapshot.
        val restarted = docker.inspectContainerCmd(postgres.containerId).exec()
        val port = requireNotNull(restarted.networkSettings.ports.bindings[
            com.github.dockerjava.api.model.ExposedPort.tcp(5432)]?.singleOrNull()?.hostPortSpec)
        ds = DriverManagerDataSource("jdbc:postgresql://${postgres.host}:$port/${postgres.databaseName}",
            postgres.username, postgres.password)
        db = NamedParameterJdbcTemplate(ds)
        var ready = false
        repeat(100) {
            if (!ready) {
                ready = runCatching { db.jdbcTemplate.queryForObject("SELECT 1", Int::class.java) == 1 }.getOrDefault(false)
                if (!ready) Thread.sleep(100)
            }
        }
        assertTrue(ready, "PostgreSQL did not return after restart")
        val startedAfter = db.jdbcTemplate.queryForObject("SELECT pg_postmaster_start_time()::text", String::class.java)
        assertNotEquals(startedBefore, startedAfter, "the PostgreSQL process must actually restart")
        println("C3B_POSTGRES_RESTART before=$startedBefore after=$startedAfter")
        store = JdbcBattleSessionStore(db, ds)
        assertEquals(accepted.copy(replayed = true), receipt(acceptedCmd))
        assertEquals(rejected.copy(replayed = true), receipt(rejectedCmd))
        assertEquals(before, store.head(world, battle))
        assertEquals(initial, store.deployment(world, battle, initial))
        assertTrue(store.eventsAfter(world, battle, 0).isEmpty())
    }

    @Test
    fun `v2 receipt marker cannot borrow an effective tick event or deployment revision`() {
        val battle = "v2-receipt-constraint"
        running(battle)
        for ((index, values) in listOf(101 to null, null to 1L).withIndex()) {
            assertFailsWith<org.springframework.dao.DataIntegrityViolationException> {
                db.update("""INSERT INTO battle_command_receipt (world_id, battle_id, participant_id,
                    client_command_id, intent_sha256, verdict, reason_code, server_tick,
                    effective_tick, event_seq, authority_revision, deployment_revision_after, v2_command)
                    VALUES (1, :battle, 1, :client, :sha, 'ACCEPTED', NULL, 100, :effective, NULL, 3, :revision, true)""",
                    MapSqlParameterSource("battle", battle).addValue("client", "invalid-$index")
                        .addValue("sha", "a".repeat(64)).addValue("effective", values.first)
                        .addValue("revision", values.second))
            }
        }
    }

}

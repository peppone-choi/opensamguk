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
    fun `v2 COMMAND receipt is accepted without a v1 event or combat state change`() {
        val battle = "v2-command-red"
        val epoch = prepare(battle)
        db.update("UPDATE battle_session SET phase = 'RUNNING', current_tick = 10 WHERE battle_id = :id",
            MapSqlParameterSource("id", battle))
        val before = store.head(world, battle)
        val intent = "v2-command-red-intent"
        val record = BattleCommandRecord(world, battle, 1, "cmd", sha(intent), epoch, 3, 10,
            "ATTACKER", intent, v2Command = BattleV2CommandAdmission(initial,
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
}

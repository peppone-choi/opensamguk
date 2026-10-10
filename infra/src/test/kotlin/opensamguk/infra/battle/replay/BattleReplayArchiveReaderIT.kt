package opensamguk.infra.battle.replay

import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.BattleCheckpoint
import opensamguk.infra.battle.realtime.BattleResultRecord
import opensamguk.infra.battle.realtime.BattleSessionPhase
import opensamguk.infra.battle.realtime.FrozenBattleTicket
import opensamguk.infra.battle.realtime.JdbcBattleSessionStore
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.containers.PostgreSQLContainer

/** Synthetic storage fixture. Calling markApplied here is NOT evidence of a production campaign ACK. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BattleReplayArchiveReaderIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DriverManagerDataSource
    private lateinit var db: NamedParameterJdbcTemplate
    private lateinit var store: JdbcBattleSessionStore
    private val world = WorldId(1)

    @BeforeAll
    fun setup() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — PostgreSQL archive readback not verified",
        )
        postgres = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
        dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        db = NamedParameterJdbcTemplate(dataSource)
        store = JdbcBattleSessionStore(db, dataSource)
        db.jdbcTemplate.update("""
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
            VALUES (1, 'replay-read-it', 200, 1, 3600), (2, 'replay-read-it-other', 200, 1, 3600)
        """.trimIndent())
    }

    @AfterAll
    fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    @Test
    fun `new reader preserves exact archived bytes and synthetic application markers across restart`() {
        val id = "archive-readback"
        val ticket = ticket(id)
        assertTrue(store.create(ticket))
        val claimed = assertNotNull(store.claimEpoch(world, id, "fixture-writer", 30_000))
        assertTrue(store.startRun(world, id, "fixture-writer", claimed.sessionEpoch))
        val resultJson = "{  \"schemaVersion\": 1, \"outcome\": \"ATTACKER\" }\n"
        val result = BattleResultRecord(world, id, claimed.sessionEpoch, "fixture-writer", 1,
            resultJson, sha(resultJson), "d".repeat(64), 4, 2, ticket.pacingMode)
        assertTrue(store.publishResult(result))
        val before = assertNotNull(reader().read(world, id))
        assertEquals(BattleSessionPhase.RESULT_PENDING, before.head.phase)
        assertEquals("PENDING", before.results.single().status)
        assertNull(before.results.single().appliedAt)
        assertEquals(resultJson, before.events.last().payloadJson)
        assertEquals("BATTLE_RESOLVED", before.events.last().type)
        assertTrue(store.markApplied(world, id, 1)) // Synthetic status setup, not a campaign flush.
        val first = assertNotNull(reader().read(world, id))
        val restarted = assertNotNull(reader().read(world, id))
        assertEquals(first, restarted)
        assertEquals(ticket.payloadJson, restarted.ticket.payloadJson)
        assertEquals(resultJson, restarted.results.single().record.resultJson)
        assertEquals(result, restarted.results.single().record)
        assertEquals(BattleSessionPhase.APPLIED, restarted.head.phase)
        assertNotNull(restarted.results.single().appliedAt)
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    }

    @Test
    fun `same battle key is isolated by world and reads never manufacture application`() {
        val id = "world-key"
        assertTrue(store.create(ticket(id)))
        assertTrue(store.create(ticket(id).copy(worldId = WorldId(2))))
        val first = assertNotNull(reader().read(world, id))
        val other = assertNotNull(reader().read(WorldId(2), id))
        assertEquals(world, first.ticket.worldId)
        assertEquals(WorldId(2), other.ticket.worldId)
        assertEquals(BattleSessionPhase.READY, first.head.phase)
        assertTrue(first.results.isEmpty() && first.events.isEmpty())
        assertEquals(first, reader().read(world, id))
        assertNull(reader().read(world, "absent"))
    }

    @Test
    fun `checkpoint storage bytes and cursor survive a new reader without decoding or normalization`() {
        val id = "checkpoint-readback"
        assertTrue(store.create(ticket(id)))
        val claimed = assertNotNull(store.claimEpoch(world, id, "checkpoint-fixture", 30_000))
        assertTrue(store.startRun(world, id, "checkpoint-fixture", claimed.sessionEpoch))
        // Opaque storage fixture only: these bytes are not asserted to be a valid tactical state.
        val checkpoint = BattleCheckpoint(world, id, claimed.sessionEpoch, "checkpoint-fixture", 0, 1,
            "e".repeat(64), byteArrayOf(0, 1, -1, 10))
        assertTrue(store.checkpoint(checkpoint))
        val loaded = assertNotNull(assertNotNull(reader().read(world, id)).checkpoint)
        val reloaded = assertNotNull(assertNotNull(reader().read(world, id)).checkpoint)
        assertTrue(checkpoint.compressedState.contentEquals(loaded.compressedState))
        assertTrue(loaded.compressedState.contentEquals(reloaded.compressedState))
        assertEquals(checkpoint.stateHash, reloaded.stateHash)
        assertEquals(checkpoint.eventSeq, reloaded.eventSeq)
        assertEquals(checkpoint.tick, reloaded.tick)
    }

    @Test
    fun `archive SELECT executes inside a database read only repeatable read transaction`() {
        val id = "read-only"
        assertTrue(store.create(ticket(id)))
        var observed = false
        val inspectingDb = object : NamedParameterJdbcTemplate(dataSource) {
            override fun <T : Any?> query(sql: String, paramSource: org.springframework.jdbc.core.namedparam.SqlParameterSource,
                                         rowMapper: org.springframework.jdbc.core.RowMapper<T>): List<T> {
                assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                assertEquals("on", jdbcTemplate.queryForObject("SHOW transaction_read_only", String::class.java))
                assertEquals("repeatable read", jdbcTemplate.queryForObject("SHOW transaction_isolation", String::class.java))
                observed = true
                return super.query(sql, paramSource, rowMapper)
            }
        }
        assertNotNull(JdbcBattleReplayArchiveReader(inspectingDb, dataSource).read(world, id))
        assertTrue(observed)
    }

    private fun reader() = JdbcBattleReplayArchiveReader(db, dataSource)

    private fun ticket(id: String): FrozenBattleTicket {
        val body = "{ \"schemaVersion\": 1, \"battleId\": \"$id\" }\n"
        val now = Instant.now()
        return FrozenBattleTicket(world, id, body, sha(body), "a".repeat(64), "b".repeat(64),
            "c".repeat(64), 17, 4, 2, now.minusSeconds(10), now.plusSeconds(300), emptyList())
    }

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

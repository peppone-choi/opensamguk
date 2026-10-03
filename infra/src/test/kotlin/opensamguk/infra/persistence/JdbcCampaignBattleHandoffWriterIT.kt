package opensamguk.infra.persistence

import java.security.MessageDigest
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.FrozenBattleTicket
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcCampaignBattleHandoffWriterIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var db: NamedParameterJdbcTemplate
    private lateinit var tx: TransactionTemplate
    private lateinit var writer: JdbcCampaignBattleHandoffWriter
    private val world = WorldId(1)

    @BeforeAll
    fun setup() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — PostgreSQL integration test skipped",
        )
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val source: DataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl; username = postgres.username; password = postgres.password
        }
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        db = NamedParameterJdbcTemplate(source)
        tx = TransactionTemplate(DataSourceTransactionManager(source))
        writer = JdbcCampaignBattleHandoffWriter(db)
        db.update("""
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
            VALUES (1, 'campaign-handoff-it', 200, 1, 3600)
        """.trimIndent(), MapSqlParameterSource())
    }

    @AfterAll
    fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    @Test
    fun `handoff and locks commit once with exact retry identity`() {
        val row = handoff("battle-a", "cause-a", "general:7")
        assertFailsWith<IllegalStateException> { writer.writeWithinFlush(row) }
        assertTrue(tx.execute { writer.writeWithinFlush(row) }!!)
        assertFalse(tx.execute { writer.writeWithinFlush(row) }!!)
        assertEquals(1, count("campaign_battle_handoff"))
        assertEquals(1, count("campaign_battle_lock"))
        val changedPayload = row.ticket.payloadJson.replace("\"seed\":17", "\"seed\":99")
        assertFailsWith<IllegalStateException> {
            tx.execute { writer.writeWithinFlush(row.copy(ticket = row.ticket.copy(seed = 99,
                payloadJson = changedPayload, payloadSha256 = sha(changedPayload)))) }
        }
        assertEquals(1, count("campaign_battle_handoff"))
    }

    @Test
    fun `column and payload mismatch is rejected before any lock is written`() {
        val row = handoff("battle-mismatch", "cause-mismatch", "general:11")
        assertFailsWith<IllegalArgumentException> {
            tx.execute { writer.writeWithinFlush(row.copy(ticket = row.ticket.copy(seed = 99))) }
        }
        assertEquals(0, countFor("campaign_battle_handoff", "battle-mismatch"))
        assertEquals(0, countFor("campaign_battle_lock", "battle-mismatch"))
    }

    @Test
    fun `aborted flush leaves neither a handoff nor a lock`() {
        val row = handoff("battle-rollback", "cause-rollback", "general:8")
        assertFailsWith<IllegalStateException> {
            tx.execute {
                writer.writeWithinFlush(row)
                error("abort world flush")
            }
        }
        assertEquals(0, countFor("campaign_battle_handoff", "battle-rollback"))
        assertEquals(0, countFor("campaign_battle_lock", "battle-rollback"))
    }

    @Test
    fun `same cause or active entity cannot begin a second battle`() {
        val first = handoff("battle-exclusive", "cause-exclusive", "general:9")
        assertTrue(tx.execute { writer.writeWithinFlush(first) }!!)
        assertFailsWith<IllegalStateException> {
            tx.execute { writer.writeWithinFlush(handoff("battle-other", "cause-exclusive", "general:10")) }
        }
        assertFailsWith<DataIntegrityViolationException> {
            tx.execute { writer.writeWithinFlush(handoff("battle-locked", "cause-locked", "general:9")) }
        }
        assertEquals(0, countFor("campaign_battle_handoff", "battle-other"))
        assertEquals(0, countFor("campaign_battle_handoff", "battle-locked"))
        assertEquals(1, countFor("campaign_battle_lock", "battle-exclusive"))
    }

    private fun handoff(battleId: String, causeId: String, entityKey: String): CampaignBattleHandoffRow {
        val start = Instant.parse("2026-09-30T12:01:00Z")
        val payload = """{"schemaVersion":1,"worldId":1,"battleId":"$battleId","causeEventId":"$causeId","ruleSha256":"${"a".repeat(64)}","catalogSha256":"${"b".repeat(64)}","terrainSha256":"${"c".repeat(64)}","seed":17,"lockGeneration":1,"lockSetRevision":1,"joinDeadlineAt":"$start","deadlineAt":"${start.plusSeconds(300)}","entityRevisions":{"$entityKey":3},"pacingMode":"ACCELERATED_NPC","participants":[]}"""
        val ticket = FrozenBattleTicket(world, battleId, payload, sha(payload), "a".repeat(64),
            "b".repeat(64), "c".repeat(64), 17, 1, 1, start, start.plusSeconds(300), emptyList())
        return CampaignBattleHandoffRow(causeId, ticket, listOf(CampaignBattleLockRow(entityKey, 3)))
    }

    private fun count(table: String) = db.queryForObject("SELECT count(*) FROM $table",
        MapSqlParameterSource(), Int::class.java)!!

    private fun countFor(table: String, battleId: String) = db.queryForObject(
        "SELECT count(*) FROM $table WHERE world_id = :world_id AND battle_id = :battle_id",
        MapSqlParameterSource().addValue("world_id", world.value).addValue("battle_id", battleId),
        Int::class.java)!!

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

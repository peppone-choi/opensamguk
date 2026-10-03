package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.FrozenBattleTicket
import opensamguk.infra.battle.realtime.JdbcBattleSessionStore
import opensamguk.infra.persistence.CampaignBattleHandoffRow
import opensamguk.infra.persistence.CampaignBattleLockRow
import opensamguk.infra.persistence.JdbcCampaignBattleHandoffWriter
import opensamguk.logic.battle.realtime.WaryongBoardCatalogResource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcBattleHandoffIntakeIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var db: NamedParameterJdbcTemplate
    private lateinit var source: DataSource
    private lateinit var tx: TransactionTemplate
    private lateinit var writer: JdbcCampaignBattleHandoffWriter
    private lateinit var store: JdbcBattleSessionStore
    private val world = WorldId(1)

    @BeforeAll
    fun setup() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — PostgreSQL integration test skipped",
        )
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        source = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl; username = postgres.username; password = postgres.password
        }
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        db = NamedParameterJdbcTemplate(source)
        tx = TransactionTemplate(DataSourceTransactionManager(source))
        writer = JdbcCampaignBattleHandoffWriter(db)
        store = JdbcBattleSessionStore(db, source)
        db.update("""
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
            VALUES (1, 'handoff-intake-it', 200, 1, 3600)
        """.trimIndent(), MapSqlParameterSource())
    }

    @AfterAll
    fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    @Test
    fun `invalid committed row is rejected while a later valid row creates one session`() {
        val malformed = "{}"
        val invalid = ticket("intake-invalid", "cause-invalid", malformed)
        val params = MapSqlParameterSource().addValue("world_id", world.value)
            .addValue("battle_id", invalid.battleId).addValue("cause_event_id", "cause-invalid")
            .addValue("payload", malformed).addValue("payload_sha", invalid.payloadSha256)
            .addValue("rule_sha", invalid.ruleSha256).addValue("catalog_sha", invalid.catalogSha256)
            .addValue("terrain_sha", invalid.terrainSha256).addValue("seed", invalid.seed)
            .addValue("generation", invalid.lockGeneration).addValue("revision", invalid.lockSetRevision)
            .addValue("join_deadline", Timestamp.from(invalid.joinDeadlineAt))
            .addValue("deadline", Timestamp.from(invalid.deadlineAt))
        db.update("""
            INSERT INTO campaign_battle_handoff (world_id, battle_id, cause_event_id, payload_text,
                payload_sha256, rule_sha256, catalog_sha256, terrain_sha256, seed,
                lock_generation, lock_set_revision, join_deadline_at, deadline_at, created_at)
            VALUES (:world_id, :battle_id, :cause_event_id, :payload, :payload_sha,
                :rule_sha, :catalog_sha, :terrain_sha, :seed, :generation, :revision,
                :join_deadline, :deadline, clock_timestamp() - interval '1 day')
        """.trimIndent(), params)
        val valid = ticket("intake-valid", "cause-valid")
        assertTrue(tx.execute { writer.writeWithinFlush(CampaignBattleHandoffRow("cause-valid", valid,
            listOf(CampaignBattleLockRow("general:7", 3)))) }!!)
        val intake = intake()
        assertEquals(BattleHandoffIntakeResult(2, 1, 0, 1), intake.scan(world))
        assertEquals(1, count("battle_ticket", valid.battleId))
        assertEquals(1, count("battle_session", valid.battleId))
        assertEquals(0, count("battle_ticket", invalid.battleId))
        assertEquals(1, count("battle_handoff_rejection", invalid.battleId))
        assertEquals(BattleHandoffIntakeResult(0, 0, 0, 0), intake.scan(world))
    }

    @Test
    fun `bad pinned battlefield rows never create a ticket session or result`() {
        val original = ticket("intake-bad-board", "cause-bad-board", entityKey = "general:15")
        val poisonedPayload = original.payloadJson.replace(
            Regex("\"terrainRowsSha256\":\"[0-9a-f]{64}\""),
            "\"terrainRowsSha256\":\"${"0".repeat(64)}\"")
        require(poisonedPayload != original.payloadJson)
        val poisoned = original.copy(payloadJson = poisonedPayload,
            payloadSha256 = sha(poisonedPayload.toByteArray(Charsets.UTF_8)))
        assertTrue(tx.execute { writer.writeWithinFlush(CampaignBattleHandoffRow("cause-bad-board",
            poisoned, listOf(CampaignBattleLockRow("general:15", 3)))) }!!)

        intake().scan(world)

        assertEquals(0, count("battle_ticket", poisoned.battleId))
        assertEquals(0, count("battle_session", poisoned.battleId))
        assertEquals(0, count("battle_result_outbox", poisoned.battleId))
    }

    @Test
    fun `two collectors and restart converge on one ticket and one session`() {
        val row = ticket("intake-race", "cause-race", entityKey = "general:8")
        assertTrue(tx.execute { writer.writeWithinFlush(CampaignBattleHandoffRow("cause-race", row,
            listOf(CampaignBattleLockRow("general:8", 3)))) }!!)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { pool.submit<BattleHandoffIntakeResult> {
                start.await(10, TimeUnit.SECONDS)
                intake().scan(world)
            } }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, count("battle_ticket", row.battleId))
        assertEquals(1, count("battle_session", row.battleId))
        assertFalse(JdbcCommittedBattleHandoffReader(db).withoutTicket(world, 100)
            .any { it.battleId == row.battleId })
        assertEquals(BattleHandoffIntakeResult(0, 0, 0, 0), intake().scan(world))
    }

    private fun intake() = BattleHandoffIntake(JdbcCommittedBattleHandoffReader(db),
        BattleSessionCoordinator(store), JdbcBattleHandoffRejectionWriter(db)) { ticket ->
            val frozen = BattleFrozenInputCodec(WaryongBoardCatalogResource.load())
            if (!frozen.hasInstalledPins(ticket)) false
            else {
                frozen.initialState(ticket)
                true
            }
        }

    private fun count(table: String, battleId: String) = db.queryForObject(
        "SELECT count(*) FROM $table WHERE world_id = :world_id AND battle_id = :battle_id",
        MapSqlParameterSource().addValue("world_id", world.value).addValue("battle_id", battleId),
        Int::class.java)!!

    private fun ticket(battleId: String, causeId: String, overridePayload: String? = null,
                       entityKey: String = "general:7"): FrozenBattleTicket {
        val catalog = WaryongBoardCatalogResource.load()
        val board = catalog.boards.single { it.battlefield.id == 192 }
        val ruleBytes = checkNotNull(javaClass.classLoader.getResourceAsStream("battle/waryong-tactical-rules-v1.json"))
            .use { it.readBytes() }
        val start = Instant.parse("2026-09-30T12:01:00Z")
        val payload = overridePayload ?: """{"schemaVersion":1,"worldId":1,"battleId":"$battleId","causeEventId":"$causeId","kind":"ENCOUNTER","battlefieldId":192,"ruleSha256":"${sha(ruleBytes)}","catalogSha256":"${catalog.catalogSha256}","terrainSha256":"${"c".repeat(64)}","seed":17,"lockGeneration":1,"lockSetRevision":1,"pacingMode":"ACCELERATED_NPC","joinDeadlineAt":"$start","deadlineAt":"${start.plusSeconds(300)}","entityRevisions":{"$entityKey":3},"participants":[],"tacticalInput":{"schemaVersion":1,"board":{"id":192,"tileset":${board.tileset},"terrainRowsSha256":"${sha(board.battlefield.rows.joinToString("").toByteArray(Charsets.US_ASCII))}"},"attacker":{"commanderGeneralId":7,"retinues":[${retinue(7)}]},"defender":{"commanderGeneralId":8,"retinues":[${retinue(8)}]},"gate":null}}"""
        return FrozenBattleTicket(world, battleId, payload, sha(payload.toByteArray(Charsets.UTF_8)),
            sha(ruleBytes), catalog.catalogSha256, "c".repeat(64), 17, 1, 1,
            start, start.plusSeconds(300), emptyList())
    }

    private fun retinue(id: Int) = """{"id":$id,"generalId":$id,"leadership":80,"strength":70,"intelligence":60,"politics":50,"charisma":40,"troops":123,"kind":"INFANTRY","training":50,"morale":90,"fatigue":10,"supply":80,"accompaniesCorps":true}"""

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

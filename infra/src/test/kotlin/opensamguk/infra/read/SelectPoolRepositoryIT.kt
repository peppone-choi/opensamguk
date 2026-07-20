package opensamguk.infra.read

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.SelectPoolMutation
import opensamguk.infra.persistence.SelectPoolMutationType
import opensamguk.infra.persistence.SelectPoolCandidate
import opensamguk.infra.persistence.testFlushPayload
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SelectPoolRepositoryIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repo: SelectPoolRepository
    private lateinit var executor: JdbcFlushExecutor

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres.start()
        dataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .migrate()
        jdbc = NamedParameterJdbcTemplate(dataSource)
        repo = SelectPoolRepository(jdbc)
        executor = JdbcFlushExecutor(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)))
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'fixture', 200, 1, 3600)",
            MapSqlParameterSource(),
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @AfterEach
    fun clearPool() {
        if (this::jdbc.isInitialized) {
            jdbc.update("DELETE FROM select_pool", MapSqlParameterSource())
        }
    }

    @Test
    fun `findPoolEntry requires owner and includes the reserved_until boundary`() {
        val now = Instant.parse("2026-07-10T03:00:00Z")
        insertPool("boundary", owner = 77, generalId = null, reservedUntil = now, info = """{"generalName":"마초","leadership":70}""")
        insertPool("expired", owner = 77, generalId = null, reservedUntil = now.minusSeconds(1), info = """{"generalName":"장합"}""")

        val row = repo.findPoolEntry("boundary", ownerUserId = 77, now = now)

        assertNotNull(row)
        assertEquals("boundary", row.uniqueName)
        assertEquals(now, row.reservedUntil)
        assertEquals("마초", row.info["generalName"])
        assertNull(repo.findPoolEntry("boundary", ownerUserId = 88, now = now))
        assertNull(repo.findPoolEntry("expired", ownerUserId = 77, now = now))
        assertEquals(listOf("boundary"), repo.listForUser(ownerUserId = 77, now = now).map { it.uniqueName })
    }

    @Test
    fun `occupyPickedGeneralName claims only a live owner token`() {
        val now = Instant.parse("2026-07-10T03:00:00Z")
        insertPool("pick-me", owner = 77, generalId = null, reservedUntil = now, info = """{"generalName":"마초"}""")

        executor.flush(payload(SelectPoolMutation(SelectPoolMutationType.PICK, "pick-me", 77, 300, now)))
        assertPool("pick-me", owner = null, generalId = 300, reservedUntilNull = true)

        assertFailsWith<IllegalStateException> {
            executor.flush(payload(SelectPoolMutation(SelectPoolMutationType.PICK, "pick-me", 77, 301, now)))
        }
        assertPool("pick-me", owner = null, generalId = 300, reservedUntilNull = true)
    }

    @Test
    fun `claimUpdatedGeneralName performs mark then swap and clears previous ownership`() {
        val now = Instant.parse("2026-07-10T03:00:00Z")
        insertPool("old-name", owner = null, generalId = 30, reservedUntil = null, info = """{"generalName":"구명"}""")
        insertPool("new-name", owner = 77, generalId = null, reservedUntil = now, info = """{"generalName":"신명"}""")

        executor.flush(payload(SelectPoolMutation(SelectPoolMutationType.UPDATE, "new-name", 77, 30, now)))
        assertPool("new-name", owner = null, generalId = 30, reservedUntilNull = true)
        assertPool("old-name", owner = null, generalId = null, reservedUntilNull = true)
    }

    @Test
    fun `refresh inserts owner candidates through the flush transaction`() {
        val now = Instant.parse("2026-07-10T03:00:00Z")
        val candidates = listOf("가람", "강현").map { name ->
            SelectPoolCandidate(name, linkedMapOf("uniqueName" to name, "generalName" to name, "imgsvr" to 0))
        }

        executor.flush(
            payload(
                SelectPoolMutation(
                    type = SelectPoolMutationType.REFRESH,
                    uniqueName = "",
                    ownerUserId = 88,
                    generalId = 0,
                    now = now,
                    reservedUntil = now.plusSeconds(30),
                    candidates = candidates,
                ),
            ),
        )

        assertEquals(listOf("가람", "강현"), repo.listForUser(88, now).map { it.uniqueName })
    }

    private fun insertPool(uniqueName: String, owner: Int?, generalId: Int?, reservedUntil: Instant?, info: String) {
        jdbc.update(
            """
            INSERT INTO select_pool (unique_name, owner, general_id, reserved_until, info)
            VALUES (:unique_name, :owner, :general_id, :reserved_until, :info)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("unique_name", uniqueName)
                .addValue("owner", owner)
                .addValue("general_id", generalId)
                .addValue("reserved_until", reservedUntil?.let { java.sql.Timestamp.from(it) })
                .addValue("info", info),
        )
    }

    private fun assertPool(uniqueName: String, owner: Int?, generalId: Int?, reservedUntilNull: Boolean) {
        val row = jdbc.queryForMap(
            "SELECT owner, general_id, reserved_until FROM select_pool WHERE unique_name = :unique_name",
            MapSqlParameterSource("unique_name", uniqueName),
        )
        assertEquals(owner, row["owner"])
        assertEquals(generalId, row["general_id"])
        assertEquals(reservedUntilNull, row["reserved_until"] == null)
    }

    private fun payload(mutation: SelectPoolMutation) = testFlushPayload(
        worldId = opensamguk.common.world.WorldId(1),
        worldStateUpdate = linkedMapOf(
            "id" to 1,
            "current_year" to 200,
            "current_month" to 1,
            "current_phase" to 1,
            "last_turn_time" to mutation.now.toString(),
            "isunited" to 0,
            "max_nation_id" to 0,
            "max_general_id" to 0,
        ),
        selectPoolMutations = listOf(mutation),
    )
}

package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * Testcontainers IT for the FD0a `V2__p2_brief` Flyway migration.
 *
 * Brings up `postgres:16-alpine`, applies the FULL Flyway chain (V1 baseline → V2), and asserts the
 * V2 migration added a `brief text NOT NULL DEFAULT ''` column to BOTH `general_turn` AND
 * `nation_turn` — the column PHP writes on every nation_turn row (`che_거병.php:151` inserts
 * `'brief'=>'휴식'` on all 24 rows; research OQ11). V1 (frozen baseline) is untouched.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V2BriefMigrationTest {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate

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
            .load()
            .migrate()

        jdbc = NamedParameterJdbcTemplate(dataSource)
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `V2 applied -- general_turn and nation_turn have brief text NOT NULL DEFAULT empty`() {
        assertColumn("general_turn")
        assertColumn("nation_turn")
    }

    @Test
    fun `inserting a row without brief defaults to empty string`() {
        jdbc.update(
            """
            INSERT INTO general_turn (general_id, turn_idx, action_code)
            VALUES (1, 0, 'che_농지개간')
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        val brief = jdbc.queryForObject(
            "SELECT brief FROM general_turn WHERE general_id = 1 AND turn_idx = 0",
            MapSqlParameterSource(),
            String::class.java,
        )
        assertEquals("", brief, "general_turn.brief defaults to ''")

        jdbc.update(
            """
            INSERT INTO nation_turn (nation_id, officer_level, turn_idx, action_code)
            VALUES (1, 12, 0, 'che_거병')
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        val nBrief = jdbc.queryForObject(
            "SELECT brief FROM nation_turn WHERE nation_id = 1 AND officer_level = 12 AND turn_idx = 0",
            MapSqlParameterSource(),
            String::class.java,
        )
        assertEquals("", nBrief, "nation_turn.brief defaults to ''")
    }

    @Test
    fun `brief accepts the PHP-written 휴식 value`() {
        jdbc.update(
            """
            INSERT INTO nation_turn (nation_id, officer_level, turn_idx, action_code, brief)
            VALUES (2, 12, 5, 'che_거병', '휴식')
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        val brief = jdbc.queryForObject(
            "SELECT brief FROM nation_turn WHERE nation_id = 2 AND officer_level = 12 AND turn_idx = 5",
            MapSqlParameterSource(),
            String::class.java,
        )
        assertEquals("휴식", brief)
    }

    private fun assertColumn(table: String) {
        val row = jdbc.queryForMap(
            """
            SELECT data_type, is_nullable, column_default
              FROM information_schema.columns
             WHERE table_name = :t AND column_name = 'brief'
            """.trimIndent(),
            MapSqlParameterSource().addValue("t", table),
        )
        assertEquals("text", row["data_type"], "$table.brief must be text")
        assertEquals("NO", row["is_nullable"], "$table.brief must be NOT NULL")
        assertEquals("''::text", row["column_default"], "$table.brief default must be ''")
    }
}

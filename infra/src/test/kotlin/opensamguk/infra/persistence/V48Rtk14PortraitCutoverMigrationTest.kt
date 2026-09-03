package opensamguk.infra.persistence

import opensamguk.infra.persistence.MetaJson
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V48Rtk14PortraitCutoverMigrationTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres.start()
        jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun resetDatabase() {
        jdbc.execute("DROP SCHEMA public CASCADE")
        jdbc.execute("CREATE SCHEMA public")
        migrateTo("47")
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `V48 rewrites active and deferred RTK14 portraits from the effective scenario`(@TempDir scenarioDir: Path) {
        Files.writeString(scenarioDir.resolve("scenario_portrait.json"), scenarioJson(1000))
        seedWorld("scenario_portrait")
        seedGeneral(sourceNumber = 1, picture = "1001.jpg", imageServer = 1)
        seedDeferred(sourceNumber = 2, picture = null)

        migrateTo("48", scenarioDir.toString())

        assertEquals("10001.png", generalPicture())
        assertEquals(0, generalImageServer())
        assertEquals("10002.png", deferredPicture())
        assertEquals(1, successfulMigrationCount("48"))
    }

    @Test
    fun `V48 rejects a partial RTK14 portrait mapping without changing current data`(@TempDir scenarioDir: Path) {
        Files.writeString(scenarioDir.resolve("scenario_partial.json"), scenarioJson(999))
        seedWorld("scenario_partial")
        seedGeneral(sourceNumber = 1, picture = "1001.jpg", imageServer = 0)

        assertFailsWith<FlywayException> { migrateTo("48", scenarioDir.toString()) }

        assertEquals("1001.jpg", generalPicture())
        assertEquals(0, successfulMigrationCount("48"))
    }

    @Test
    fun `V48 leaves a world without RTK14 source metadata unchanged`(@TempDir scenarioDir: Path) {
        Files.writeString(scenarioDir.resolve("scenario_legacy.json"), legacyScenarioJson())
        seedWorld("scenario_legacy")
        seedGeneral(sourceNumber = null, picture = "1001.jpg", imageServer = 0)

        migrateTo("48", scenarioDir.toString())

        assertEquals("1001.jpg", generalPicture())
        assertEquals(1, successfulMigrationCount("48"))
    }

    private fun migrateTo(target: String, scenarioDir: String = "") {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .placeholders(mapOf("scenario_dir" to scenarioDir))
            .target(MigrationVersion.fromVersion(target))
            .load()
            .migrate()
    }

    private fun seedWorld(scenarioCode: String) {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, ?, 190, 1, 3600)",
            scenarioCode,
        )
    }

    private fun seedGeneral(sourceNumber: Int?, picture: String, imageServer: Int) {
        val meta = if (sourceNumber == null) "{}" else "{\"rtk14_officer_number\":$sourceNumber}"
        jdbc.update(
            """
            INSERT INTO general
                (world_id, id, name, nation_id, city_id, npc_state, affinity, born_year, dead_year, picture,
                 image_server, leadership, strength, intel, officer_level, turn_time, age, personal_code,
                 special_code, special2_code, meta)
            VALUES (1, 2001, '장수1', 0, 1, 2, 1, 170, 240, ?, ?, 70, 71, 72, 0, now(), 20,
                    'che_유지', 'None', 'None', CAST(? AS jsonb))
            """.trimIndent(),
            picture,
            imageServer,
            meta,
        )
    }

    private fun seedDeferred(sourceNumber: Int, picture: String?) {
        val tuple = listOf(
            "RegNPC", 1, "장수$sourceNumber", picture, 0, null, 70, 71, 72, 0, 170, 240,
            "유지", null, null, 73, 74, 200, sourceNumber, "남", 71, 41, 360, "왕도", true, false,
        )
        jdbc.update(
            "INSERT INTO event (world_id, target_code, priority, condition, action) VALUES (1, 'Month', 1000, CAST(? AS jsonb), CAST(? AS jsonb))",
            MetaJson.encode(listOf("Date", ">=", 200, "1")),
            MetaJson.encode(listOf(tuple, listOf("DeleteEvent"))),
        )
    }

    private fun scenarioJson(count: Int): String {
        val generals = (1..count).joinToString(",") { sourceNumber ->
            val stableId = 10000 + sourceNumber
            "[0,\"장수$sourceNumber\",\"$stableId.png\",0,null,70,71,72,0,170,240,null,null,null,73,74,200,$sourceNumber,\"남\",71,41,360,\"왕도\",true,false]"
        }
        return """{"title":"portrait","startYear":190,"map":{},"const":{},"nation":[],"general":[$generals],"general_ex":[],"general_neutral":[],"diplomacy":[]}"""
    }

    private fun legacyScenarioJson(): String =
        """{"title":"legacy","startYear":190,"map":{},"const":{},"nation":[],"general":[[0,"장수1",1001,0,null,70,71,72,0,170,240,null,null]],"general_ex":[],"general_neutral":[],"diplomacy":[]}"""

    private fun generalPicture(): String = jdbc.queryForObject(
        "SELECT picture FROM general WHERE world_id = 1 AND id = 2001",
        String::class.java,
    )!!

    private fun generalImageServer(): Int = jdbc.queryForObject(
        "SELECT image_server FROM general WHERE world_id = 1 AND id = 2001",
        Int::class.java,
    )!!

    private fun deferredPicture(): String = jdbc.queryForObject(
        "SELECT action #>> '{0,3}' FROM event WHERE world_id = 1",
        String::class.java,
    )!!

    private fun successfulMigrationCount(version: String): Int = jdbc.queryForObject(
        "SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success",
        Int::class.java,
        version,
    )!!
}

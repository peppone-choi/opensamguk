package opensamguk.engine.boot

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScenarioMapSeedIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private var dockerAvailable = false

    @BeforeAll
    fun setUpClass() {
        dockerAvailable = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        if (!dockerAvailable) return

        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val dataSource: DataSource = DriverManagerDataSource().apply {
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
        jdbc = JdbcTemplate(dataSource)
    }

    @AfterAll
    fun tearDownClass() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `scenario_2 seed uses miniche_b city catalog`() {
        assumeTrue(dockerAvailable, "Docker unavailable - scenario map seed IT skipped (not failed)")

        val bootstrap = SeedBootstrap(scenarioCode = "scenario_2")

        assertTrue(bootstrap.ensureSeeded(jdbc), "fresh scenario_2 world is seeded")
        assertEquals(78, count("city"))
        assertEquals(0, count("nation"))
        assertEquals(0, count("general"))

        val city = jdbc.queryForMap("SELECT name, level, pop_max, agri_max, comm_max FROM city WHERE id = 1")
        assertEquals("낙양", city["name"].toString())
        assertEquals(8, (city["level"] as Number).toInt())
        assertEquals(668600, (city["pop_max"] as Number).toInt())
        assertEquals(7800, (city["agri_max"] as Number).toInt())
        assertEquals(8000, (city["comm_max"] as Number).toInt())

        val config = jdbc.queryForObject("SELECT config::text FROM world_state WHERE id = 1", String::class.java)!!
        assertTrue(config.contains("\"mapName\":\"miniche_b\"") || config.contains("\"mapName\": \"miniche_b\""), config)
    }

    private fun count(table: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java) ?: 0
}

package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 사료 재검(OPENSAM-105) 전 che 1010 시나리오가 여전히 시드되는지 지키는 회귀 가드.
 * `scenario_1010.json` 이 han(774城) 으로 갈아끼워진 뒤 che 시드 경로가 조용히 죽지 않았음을
 * 원본 94도시 사본(`scenario/scenario_1010_che.json`, che.json 맵)으로 증명한다.
 *
 * [ScenarioSeedCoordinator.ensureSeeded]는 world_state 테이블에 오직 configured world id 하나만
 * 허용한다(다른 id 가 있으면 예외) — 그래서 [ScenarioBootIT]와 world 를 공유하지 않고 자기 컨테이너를 쓴다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheScenarioBootIT {

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
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .migrate()
        jdbc = JdbcTemplate(dataSource)
    }

    @AfterAll
    fun tearDownClass() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `che scenario_1010 still seeds with the original 94-city map`() {
        assumeTrue(dockerAvailable, "Docker unavailable — che scenario boot IT skipped (not failed)")

        val cheDir = Files.createTempDirectory("scenario-che")
        Files.writeString(cheDir.resolve("scenario_1010.json"), readResource("scenario/scenario_1010_che.json"), StandardCharsets.UTF_8)

        val worldId = WorldId(1)
        val bootstrap = SeedBootstrap(scenarioCode = "scenario_1010", scenarioDir = cheDir.toString(), worldId = worldId)
        val loader = WorldSnapshotLoader(jdbc, bootstrap, worldId)

        assertTrue(bootstrap.ensureSeeded(jdbc), "first ensureSeeded seeds the fresh che world")
        // che 풀맵: 점유 24 + 공백지 70 = 94 (cities_1010.json)
        assertEquals(94, jdbc.queryForObject("SELECT count(*) FROM city WHERE world_id = 1", Int::class.java))

        val snapshot = loader.buildSnapshot()
        assertEquals(94, snapshot.cities.size) // che 풀맵: 점유 24 + 공백지 70 = 94
    }

    private fun readResource(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: error("resource not found: $path")
        return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }
}

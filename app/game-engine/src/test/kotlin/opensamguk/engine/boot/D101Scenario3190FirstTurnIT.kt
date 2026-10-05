package opensamguk.engine.boot

import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import opensamguk.engine.config.EngineProcessWorld
import opensamguk.engine.run.TurnRunService
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.seed.WorldArtifactsResolver
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** A production 3190 seed, zero accounts, and one real engine boundary. Never substitutes a Yuzhou fixture. */
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.main.allow-bean-definition-overriding=true",
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
            "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration," +
            "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
    ],
)
class D101Scenario3190FirstTurnIT {
    @Autowired lateinit var world: InMemoryTurnWorld
    @Autowired lateinit var service: TurnRunService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var snapshotLoader: WorldSnapshotLoader

    @Test
    fun `fresh 3190 NPC world runs its first boundary immediately while keeping sixty minute cadence`() {
        // This IT explicitly seeds from its classpath fixture. These bytes are not an operational selected-source receipt.
        val scenarioBytes = requireNotNull(javaClass.classLoader.getResourceAsStream("scenario/scenario_3190.json"))
            .use { it.readBytes() }
        val scenarioSha = MessageDigest.getInstance("SHA-256").digest(scenarioBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val expectation = D101SelectedRosterExpectation()
        val expected = expectation.calculate(scenarioBytes, scenarioSha, scenarioBytes.size.toLong(), 1)
        val initial = world.getState()
        assertEquals(1, initial.id)
        assertEquals(190, initial.currentYear)
        assertEquals(1, initial.currentMonth)
        assertEquals(1, initial.currentPhase)
        assertEquals(3600, initial.tickSeconds)
        assertEquals(expected.activeGeneralRows, world.listGenerals().size)
        assertEquals(21, world.listNations().size)
        val settings = jdbc.queryForMap(
            "SELECT (config ->> 'maxgeneral')::int AS maxgeneral, " +
                "(config ->> 'block_general_create')::int AS block_general_create, " +
                "config ->> 'firstTurnPolicy' AS first_turn FROM world_state WHERE id=1",
        )
        assertEquals(50, (settings.getValue("maxgeneral") as Number).toInt())
        assertEquals(1, (settings.getValue("block_general_create") as Number).toInt())
        assertEquals("immediate", settings["first_turn"])
        assertEquals("50", jdbc.queryForObject(
            "SELECT value::text FROM game_kv WHERE world_id=1 AND \"table\"='game_env' AND key='maxgeneral'",
            String::class.java,
        ))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM general_owner WHERE world_id=1", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM general WHERE world_id=1 AND user_id IS NOT NULL", Int::class.java))

        val firstBoundary = service.nextRunTime()
        assertTrue(!firstBoundary.isAfter(Instant.now()), "the first world boundary must already be due")
        val result = service.runTick(firstBoundary)
        assertTrue(result.handled.isNotEmpty(), "the first boundary must execute NPC general turns")

        val after = world.getState()
        assertEquals(firstBoundary, after.lastTurnTime)
        assertEquals(3600, after.tickSeconds)
        assertEquals(firstBoundary.plusSeconds(3600), service.nextRunTime())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM general_owner WHERE world_id=1", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM general WHERE world_id=1 AND user_id IS NOT NULL", Int::class.java))
        val persisted = requireNotNull(jdbc.queryForObject(
            "SELECT meta ->> 'lastTurnTime' FROM world_state WHERE id=1", String::class.java,
        ))
        assertEquals(firstBoundary, Instant.parse(persisted))
        // Capture an actual read-only PostgreSQL projection after the committed first flush.
        // Its expected source pins and process identity are supplied by the later custody harness.
        val projection = D101ProjectionSnapshotReader(requireNotNull(jdbc.dataSource))
            .capture(firstBoundary, typedGeneration = "0", effectiveResetExtend = 1)
        assertEquals("scenario_3190", projection.world["scenarioCode"])
        assertEquals(firstBoundary, projection.rawLastTurnTime)
        expectation.requireDatabaseMatch(expected, projection)
        assertEquals(expected.activeGeneralRows, projection.generals.size)
        assertEquals(expected.activeRetainerRows, projection.retainers.size)
        assertTrue(projection.positions.isNotEmpty())
        // The other pins are synthetic here; serialization alone cannot promote this to an actual source proof.
        val candidatePins = D101ProjectionCanonicalizer.SourcePins(
            "a".repeat(40), scenarioSha, "b".repeat(64), "c".repeat(64),
        )
        assertTrue(D101ProjectionCanonicalizer().canonicalBytes(projection, candidatePins).isNotEmpty())
        assertTrue(projection.generals.none { it[5] == true }, "the isolated initial projection must have no human owner")
        // A fresh snapshot read must see the committed boundary before another daemon is started.
        val reloaded = snapshotLoader.buildSnapshot().state
        assertEquals(1, reloaded.id)
        assertEquals(firstBoundary, reloaded.lastTurnTime)
        assertEquals(3600, reloaded.tickSeconds)
    }

    @TestConfiguration
    class ArtifactsConfig {
        @Bean
        fun worldSnapshotLoader(
            jdbc: JdbcTemplate,
            seedBootstrap: SeedBootstrap,
            processWorld: EngineProcessWorld,
        ): WorldSnapshotLoader {
            val artifacts = WorldArtifactsResolver(PassChainSupport.repoRoot())
            return WorldSnapshotLoader(
                jdbc, seedBootstrap, processWorld.worldId,
                waterTopologyLoader = { artifacts.artifacts(it).projection.topology },
                mapVariantSelector = { ids, pins -> artifacts.resolve(ids, pins).variant },
                administrativeCountyIdsLoader = { artifacts.artifacts(it).projection.administrativeCountyIds },
                cityLandProvinceLoader = { variant -> artifacts.artifacts(variant).projection.bindingsByCityId
                    .mapNotNull { (city, binding) -> binding.landProvinceId?.let { city to it } }.toMap() },
            )
        }
    }

    companion object {
        private fun repoRoot() = PassChainSupport.repoRoot()

        @JvmStatic
        @AfterAll
        fun clearArtifactsRoot() {
            System.clearProperty("opensamguk.artifacts.root")
        }

        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
        @Container @JvmStatic val redis: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            System.setProperty("opensamguk.artifacts.root", repoRoot().toString())
            val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
            registry.add("management.health.redis.enabled") { "false" }
            registry.add("OPENSAMGUK_WORLD_ID") { "1" }
            registry.add("SCENARIO_CODE") { "scenario_3190" }
            registry.add("SCENARIO_DIR") { "" }
            registry.add("SCENARIO_SEED_ENABLED") { "true" }
            registry.add("RESET_TURNTERM") { "60" }
            registry.add("RESET_MAXGENERAL") { "50" }
            registry.add("RESET_FIRST_TURN") { "immediate" }
            registry.add("RESET_BLOCK_GENERAL_CREATE") { "1" }
            registry.add("RESET_EXTEND") { "1" }
            registry.add("opensamguk.daemon.enabled") { "false" }
        }
    }
}

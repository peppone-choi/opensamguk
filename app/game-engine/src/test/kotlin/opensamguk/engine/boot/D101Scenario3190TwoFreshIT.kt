package opensamguk.engine.boot

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.engine.GameEngineApplication
import opensamguk.engine.config.EngineProcessWorld
import opensamguk.engine.run.TurnRunService
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.seed.WorldArtifactsResolver
import opensamguk.infra.seed.WorldTopologyPin
import opensamguk.logic.world.WorldMapVariant
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.env.MapPropertySource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Two independent classpath-source installations. This does not issue FINAL_SELECTED custody. */
class D101Scenario3190TwoFreshIT {
    private data class SeedRows(
        val generals: List<List<Any?>>,
        val retainers: List<List<Any?>>,
        val nations: List<List<Any?>>,
        val cities: List<List<Any?>>,
        val positions: List<List<Any?>>,
    )

    private data class RunResult(
        val postgresId: String,
        val redisId: String,
        val seedRows: SeedRows,
        val seedRowsSha256: String,
        val handled: Int,
    )

    @Test
    fun `two fresh 3190 worlds agree before independently flushing their first NPC boundary`() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker unavailable; two-fresh PostgreSQL/Redis IT skipped")
        val scenarioBytes = requireNotNull(javaClass.classLoader.getResourceAsStream("scenario/scenario_3190.json"))
            .use { it.readBytes() }
        val scenarioSha = sha256(scenarioBytes)
        val expectation = D101SelectedRosterExpectation()
        val selectedRoster = expectation.calculate(scenarioBytes, scenarioSha, scenarioBytes.size.toLong(), 1)

        val first = runFresh(1, expectation, selectedRoster)
        val second = runFresh(2, expectation, selectedRoster)
        assertNotEquals(first.postgresId, second.postgresId, "both runs must use different PostgreSQL containers")
        assertNotEquals(first.redisId, second.redisId, "both runs must use different Redis containers")
        assertEquals(first.seedRows, second.seedRows, "all five persisted seed membership tables must agree")
        assertEquals(first.seedRowsSha256, second.seedRowsSha256)
        assertTrue(first.handled > 0 && second.handled > 0, "each real first NPC boundary must handle turns")
        println("D101 two-fresh CI fixture scenarioRawSha256=$scenarioSha seedRowsSha256=${first.seedRowsSha256}")
    }

    private fun runFresh(
        run: Int,
        expectation: D101SelectedRosterExpectation,
        selectedRoster: D101SelectedRosterExpectation.Counts,
    ): RunResult {
        val postgres = PostgreSQLContainer("postgres:16-alpine")
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)
        postgres.start()
        try {
            redis.start()
            try {
                val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
                System.setProperty("opensamguk.artifacts.root", PassChainSupport.repoRoot().toString())
                val properties = mapOf<String, Any>(
                    "spring.autoconfigure.exclude" to listOf(
                        "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
                        "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
                        "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration",
                        "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
                    ).joinToString(","),
                    "spring.datasource.url" to postgres.jdbcUrl,
                    "spring.datasource.username" to postgres.username,
                    "spring.datasource.password" to postgres.password,
                    "spring.data.redis.host" to redis.host,
                    "spring.data.redis.port" to redis.getMappedPort(6379),
                    "management.health.redis.enabled" to "false",
                    "OPENSAMGUK_WORLD_ID" to "1",
                    "SCENARIO_CODE" to "scenario_3190",
                    "SCENARIO_DIR" to "",
                    "SCENARIO_SEED_ENABLED" to "true",
                    "RESET_TURNTERM" to "60",
                    "RESET_MAXGENERAL" to "50",
                    "RESET_FIRST_TURN" to "immediate",
                    "RESET_BLOCK_GENERAL_CREATE" to "1",
                    "RESET_EXTEND" to "1",
                    "opensamguk.daemon.enabled" to "false",
                )
                val context = SpringApplicationBuilder(GameEngineApplication::class.java, ArtifactsConfig::class.java)
                    .web(WebApplicationType.NONE)
                    // Boot consumes this before context initializers register property sources.
                    .properties("spring.main.allow-bean-definition-overriding=true")
                    .initializers({ application ->
                        application.environment.propertySources.addFirst(MapPropertySource("d101-two-fresh-$run", properties))
                    })
                    .run()
                try {
                    val jdbc = context.getBean(JdbcTemplate::class.java)
                    val redisTemplate = context.getBean(StringRedisTemplate::class.java)
                    assertEquals(null, redisTemplate.opsForValue().get("d101-two-fresh-probe"), "Redis must be fresh")
                    redisTemplate.opsForValue().set("d101-two-fresh-probe", run.toString())
                    assertEquals(run.toString(), redisTemplate.opsForValue().get("d101-two-fresh-probe"))

                    val world = context.getBean(InMemoryTurnWorld::class.java)
                    val service = context.getBean(TurnRunService::class.java)
                    val loader = context.getBean(WorldSnapshotLoader::class.java)
                    val initial = world.getState()
                    assertEquals(listOf(1, 190, 1, 1, 3600),
                        listOf(initial.id, initial.currentYear, initial.currentMonth, initial.currentPhase, initial.tickSeconds))
                    val reader = D101ProjectionSnapshotReader(requireNotNull(jdbc.dataSource))
                    val membership = reader.captureSeedMembership(initial.lastTurnTime, effectiveResetExtend = 1)
                    expectation.requireDatabaseMatch(selectedRoster, membership)
                    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM general_owner WHERE world_id=1", Int::class.java))
                    val seedRows = SeedRows(
                        membership.generals,
                        membership.retainers,
                        rows(jdbc, "SELECT id, name, capital_city_id, type_code FROM nation WHERE world_id=1 ORDER BY id"),
                        rows(jdbc, "SELECT id, name, nation_id FROM city WHERE world_id=1 ORDER BY id"),
                        rows(jdbc, "SELECT general_id, topology_revision, topology_hash, node_kind, node_id, revision FROM general_spatial_position WHERE world_id=1 ORDER BY general_id"),
                    )
                    assertTrue(seedRows.positions.isNotEmpty())
                    val storedPins = seedRows.positions.map { row ->
                        WorldTopologyPin("general_spatial_position", row[1]?.toString(), row[2]?.toString())
                    }
                    val storedCityIds = seedRows.cities.map { row -> (row[0] as Number).toInt() }
                    val selectedVariant = WorldArtifactsResolver(PassChainSupport.repoRoot())
                        .resolve(storedCityIds, storedPins).variant
                    assertEquals(WorldMapVariant.PROVINCE_WORLD, selectedVariant,
                        "the actual CI seed spatial rows must select the neutral 1428 release")
                    val seedRowsSha = sha256(ObjectMapper().writeValueAsBytes(listOf(
                        seedRows.generals, seedRows.retainers, seedRows.nations, seedRows.cities, seedRows.positions,
                    )))

                    val boundary = service.nextRunTime()
                    assertTrue(!boundary.isAfter(Instant.now()), "first boundary must be due immediately")
                    val tick = service.runTick(boundary)
                    assertTrue(tick.handled.isNotEmpty())
                    val persisted = requireNotNull(jdbc.queryForObject(
                        "SELECT meta ->> 'lastTurnTime' FROM world_state WHERE id=1", String::class.java))
                    assertEquals(boundary, Instant.parse(persisted), "first tick must flush its clock")
                    val after = reader.capture(boundary, typedGeneration = "0", effectiveResetExtend = 1)
                    assertEquals(world.listGenerals().size, after.generals.size)
                    assertEquals(world.listRetainers().size, after.retainers.size)
                    val cold = loader.buildSnapshot().state
                    assertEquals(boundary, cold.lastTurnTime, "a new DB snapshot must reload the committed boundary")
                    assertEquals(3600, cold.tickSeconds)
                    return RunResult(requireNotNull(postgres.containerId), requireNotNull(redis.containerId),
                        seedRows, seedRowsSha, tick.handled.size)
                } finally {
                    context.close()
                }
            } finally {
                redis.stop()
            }
        } finally {
            System.clearProperty("opensamguk.artifacts.root")
            postgres.stop()
        }
    }

    private fun rows(jdbc: JdbcTemplate, sql: String): List<List<Any?>> =
        jdbc.queryForList(sql).map { row -> row.values.toList() }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

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
}

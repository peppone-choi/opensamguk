package opensamguk.engine.boot

import opensamguk.common.wire.CreateGeneral
import opensamguk.common.wire.CreateGeneralResult
import opensamguk.common.wire.CreationCustomChoice
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.TurnDaemonEvent
import opensamguk.common.wire.TurnDaemonEventEnvelope
import opensamguk.common.wire.WireJson
import opensamguk.common.wire.encodeCommandPayload
import opensamguk.common.world.WorldId
import opensamguk.engine.config.EngineProcessWorld
import opensamguk.engine.run.TurnRunService
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.infra.persistence.CommandResultRepository
import opensamguk.infra.seed.WorldArtifactsResolver
import opensamguk.logic.creation.CreationRequestFingerprint
import opensamguk.logic.input.RuleProfile
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
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** CI engine/DB evidence on classpath 3190; G07 actual1 also requires selected bytes and authenticated API intake. */
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
class G07Scenario3190CreationPersistenceIT {
    @Autowired lateinit var world: InMemoryTurnWorld
    @Autowired lateinit var service: TurnRunService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var inbox: CommandInboxRepository
    @Autowired lateinit var results: CommandResultRepository

    @Test
    fun `3190 isolated creation command commits exactly one account linked general`() {
        val source = requireNotNull(javaClass.classLoader.getResourceAsStream("scenario/scenario_3190.json"))
            .use { it.readBytes() }
        val sourceSha = MessageDigest.getInstance("SHA-256").digest(source)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val expected = D101SelectedRosterExpectation().calculate(source, sourceSha, source.size.toLong(), 1)
        assertEquals("scenario_3190", jdbc.queryForObject(
            "SELECT scenario_code FROM world_state WHERE id = 1", String::class.java))
        assertEquals(expected.activeGeneralRows, world.listGenerals().size)
        assertEquals(1, world.worldId.value)
        assertEquals(RuleProfile.HWIHA, world.getState().ruleProfile)
        assertEquals("OPEN", world.getState().status)
        assertEquals(0, (world.getState().meta["isunited"] as Number).toInt())
        assertEquals(0, (world.getState().config["block_general_create"] as Number).toInt())
        assertEquals(50, (world.getState().config["maxgeneral"] as Number).toInt())
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM general WHERE world_id = 1 AND npc_state < 2 AND user_id IS NOT NULL",
            Int::class.java))

        val accountId = 77
        val clientId = "b54d9be6-b72c-4857-9917-d3745260e385"
        val internalId = CreationRequestFingerprint.commandRequestId(accountId.toLong(), 1, clientId)
        val county = world.administrativeCountyIds.first { world.landNodeOfCity(it) != null }
        val command = CreateGeneral(
            accountId = accountId, worldId = 1, clientRequestId = clientId,
            choiceKind = "CUSTOM", custom = CreationCustomChoice("새길", county,
                60, 60, 60, 60, 60, "WANGDO", "DISCIPLINE", role = "RETAINER"),
        )
        val envelope = TurnDaemonCommandEnvelope(internalId, "2026-10-06T00:00:00Z", command)
        assertEquals(CommandInboxRepository.InsertResult.Inserted, inbox.insertAccepted(
            CommandInboxRepository.AcceptedCommand(WorldId(1), internalId,
                commandKind = CommandInboxRepository.CommandKind.IMMEDIATE,
                intentFingerprint = "g07-engine-db-3190", generalId = null, turnIdx = 0,
                actionCode = "createGeneral", payloadJson = encodeCommandPayload(envelope),
                ownerUserId = accountId)))
        assertEquals(1, service.runIntakeCommands(1))

        val resultPayload = assertNotNull(results.findResultPayload(WorldId(1), internalId))
        val terminal = WireJson.decodeFromString(TurnDaemonEventEnvelope.serializer(), resultPayload)
        assertEquals(internalId, terminal.requestId)
        assertNotNull(terminal.committedWorldVersion)
        val result = (terminal.event as TurnDaemonEvent.CommandResult).result as CreateGeneralResult
        assertTrue(result.ok, "engine must commit creation: ${result.errorCode}")
        val generalId = assertNotNull(result.generalId)
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM general WHERE world_id = 1 AND id = ? AND user_id = ? " +
                "AND npc_state < 2 AND name = '새길'",
            Int::class.java, generalId, accountId.toString()))
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM general_access_log WHERE world_id = 1 AND general_id = ? AND user_id = ?",
            Int::class.java, generalId, accountId.toLong()))
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM general WHERE world_id = 1 AND npc_state < 2 AND user_id IS NOT NULL",
            Int::class.java))
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM command_result WHERE world_id = 1 AND request_id = ? " +
                "AND terminal_status = 'APPLIED' AND ok = true",
            Int::class.java, internalId))
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
            System.setProperty("opensamguk.artifacts.root", PassChainSupport.repoRoot().toString())
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
            registry.add("RESET_BLOCK_GENERAL_CREATE") { "0" }
            registry.add("RESET_EXTEND") { "1" }
            registry.add("opensamguk.daemon.enabled") { "false" }
        }
    }
}

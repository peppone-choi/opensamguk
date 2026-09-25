package opensamguk.engine.boot

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.hwiha.HwihaTurnOutcome
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.gameapi.GameApiApplication
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.ReservedTurnRepository
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** Real HTTP-controller/JPA intake, durable reservation, engine, reload, and owned result read. */
@org.springframework.context.annotation.Import(HwihaEnlistmentApiIT.Artifacts::class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = [GameApiApplication::class], properties = [
    "opensamguk.profile=che:scenario_2", "opensamguk.world-id=1",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
    "spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration",
    "spring.flyway.postgresql.transactional-lock=false", "jwt.public-key=",
    "jwt.legacy-secret=dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktdGVzdC1zZWNyZXQ=",
    "jwt.legacy-accept-until=2099-01-01T00:00:00Z",
])
class HwihaEnlistmentApiIT {
    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var json: ObjectMapper

    @AfterEach fun clearIdentity() = SecurityContextHolder.clearContext()

    @Test fun `owned HTTP enlistment reservation executes once and returns its durable result after cold reload`() {
        val source = checkNotNull(jdbc.dataSource)
        val fixture = HwihaEnlistmentFixture(jdbc, JdbcFlushExecutor(NamedParameterJdbcTemplate(source),
            TransactionTemplate(DataSourceTransactionManager(source))))
        fixture.seed(1)
        jdbc.update("UPDATE world_state SET config=config || '{\"startYear\":200,\"unitSet\":\"che\"}'::jsonb WHERE id=1")
        jdbc.update("UPDATE general SET user_id='42' WHERE world_id=1 AND id=1")
        jdbc.update("UPDATE general SET turn_time='0200-01-02T00:00:00Z' WHERE world_id=1 AND id<>1")
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(42L, null, emptyList())
        val mvc = MockMvcBuilders.webAppContextSetup(context).build()
        SecurityContextHolder.clearContext()
        mvc.perform(get("/api/commands/enlistment-options").param("generalId", "1"))
            .andExpect(status().isUnauthorized)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(43L, null, emptyList())
        mvc.perform(get("/api/commands/enlistment-options").param("generalId", "1"))
            .andExpect(status().isForbidden)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(42L, null, emptyList())
        mvc.perform(get("/api/commands/enlistment-options").param("generalId", "1"))
            .andExpect(status().isOk).andExpect(jsonPath("$.inputId").value("action.enlist"))
            .andExpect(jsonPath("$.maxReservedTurns").value(12))
            .andExpect(jsonPath("$.options[0].mode").value("RANDOM"))
            .andExpect(jsonPath("$.options[0].targetId").doesNotExist())
            .andExpect(jsonPath("$.options[0].availability.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.options[1].mode").value("NATION"))
            .andExpect(jsonPath("$.options[1].targetId").value(1))
            .andExpect(jsonPath("$.options[2].mode").value("GENERAL"))
            .andExpect(jsonPath("$.options[2].targetId").value(2))
            .andExpect(jsonPath("$.options[2].availability.code").value("TARGET_NOT_LORD"))
            .andExpect(jsonPath("$.options[3].targetId").value(10))
            .andExpect(jsonPath("$.options[2].masterRenownCost").doesNotExist())
        mvc.perform(post("/api/command/action.enlist").param("generalId", "1")
            .contentType("application/json").content("""{"mode":"NATION","mode":"NATION","targetId":1}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM command_inbox", Int::class.java))
        mvc.perform(post("/api/command/che_농지개간").param("generalId", "1").param("turnIdx", "29"))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("BLOCKED"))
        for (table in listOf("command_inbox", "general_turn", "command_result", "command_outbox")) {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java), table)
        }
        val response = mvc.perform(post("/api/command/action.enlist").param("generalId", "1").param("turnIdx", "0")
            .contentType("application/json").content("""{ "targetId": 1, "mode": "NATION" }"""))
            .andExpect(status().isAccepted).andReturn().response.contentAsString
        val requestId = json.readTree(response).get("requestId").asText()
        val reservations = ReservedTurnRepository(NamedParameterJdbcTemplate(jdbc))
        assertEquals(requestId, reservations.readReserved(WorldId(1), 1, 0).requestId)
        assertEquals("action.enlist", reservations.readReserved(WorldId(1), 1, 0).actionCode)
        assertEquals(42, jdbc.queryForObject("SELECT owner_user_id FROM command_inbox WHERE request_id=?", Int::class.java, requestId))
        val before = fixture.load(1)
        val world = InMemoryTurnWorld(before)
        val published = mutableListOf<String>()
        val late = Instant.parse("0200-01-01T03:00:01Z")
        val handled = fixture.service(WorldId(1), world, published).runDueGeneralTurns(late).handled.single()
        assertIs<HwihaTurnOutcome.Applied>(handled.hwihaOutcome)
        val after = fixture.load(1)
        assertEquals(1, after.generals.single { it.id == 1 }.nationId)
        assertEquals(10, after.retainers.single { it.generalId == 1 }.masterGeneralId)
        assertEquals(before.bugoks, after.bugoks)
        assertTrue(fixture.service(WorldId(1), InMemoryTurnWorld(after), published).runDueGeneralTurns(late).handled.isEmpty())
        // Admission and execution each publish one result; neither is published again after restart.
        assertEquals(listOf(requestId, requestId), published)
        assertEquals(listOf("reservationAccepted", "executionApplied"), jdbc.queryForList(
            "SELECT result_type FROM command_result WHERE world_id=1 AND request_id=? ORDER BY result_seq",
            String::class.java, requestId))
        mvc.perform(get("/api/command/result/{requestId}", requestId))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("RESOLVED"))
            .andExpect(jsonPath("$.type").value("executionApplied"))
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.result.actionCode").value("action.enlist"))
        mvc.perform(get("/api/commands/enlistment-options").param("generalId", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.options[0].availability.code").value("ALREADY_SERVING"))
        // Explicit synthetic passage/reaction authority, using the actual pinned map.
        val bundle = opensamguk.infra.seed.HanWorldArtifactsResolver(java.nio.file.Path.of("../.."))
            .artifacts(opensamguk.logic.world.HanWorldVariant.V3_1133)
        val topology = bundle.projection.topology
        val authority = mapOf(
            opensamguk.logic.input.LandPassageState.META_KEY to opensamguk.logic.input.LandPassageState.initialMetaValue(topology),
            opensamguk.logic.input.HwihaMarchReactions.META_KEY to opensamguk.logic.input.HwihaMarchReactions.Empty.toMetaValue(),
        )
        jdbc.update("UPDATE world_state SET current_phase=2, meta=meta || ?::jsonb WHERE id=1", json.writeValueAsString(authority))
        jdbc.update("UPDATE general_bugok SET commander_retainer_id=NULL WHERE world_id=1 AND id=7")
        val departure = InMemoryTurnWorld(fixture.load(1))
        val sourceNode = checkNotNull(departure.positionOf(1))
        val target = departure.administrativeCountyIds.sorted().firstNotNullOf { city ->
            val node = departure.landNodeOfCity(city) as? opensamguk.logic.world.StrategicNodeRef.LandProvince ?: return@firstNotNullOf null
            val path = opensamguk.logic.world.StrategicPathResolver.resolveLandMarch(topology,
                opensamguk.logic.world.StrategicPathRequest(sourceNode,node,1),
                opensamguk.logic.input.LandPassageState.read(departure.getState().meta,topology)!!,
                bundle.landMarchMetrics) as? opensamguk.logic.world.LandMarchPathResult.Resolved
            node.takeIf { path != null && path.path.totalCostMm in 30_000_001L..120_000_000L }
        }
        mvc.perform(get("/api/hwiha/deploy/options").param("generalId","1"))
            .andExpect(status().isOk).andExpect(jsonPath("$.available").value(true))
            .andExpect(jsonPath("$.bugoks[0].id").value(7))
        val deployResponse = mvc.perform(post("/api/command/action.deploy").param("generalId","1").param("turnIdx","0")
            .contentType("application/json").content(json.writeValueAsString(mapOf("bugokIds" to listOf(7),"destinationProvinceId" to target.id))))
            .andExpect(status().isAccepted).andReturn().response.contentAsString
        val deployId = json.readTree(deployResponse).get("requestId").asText()
        assertEquals(42, reservations.readReserved(WorldId(1),1,0).reservationOwnerUserId)
        val deploymentPublished = mutableListOf<String>()
        val deployDue = Instant.parse("0200-01-01T05:00:00Z")
        val deployWorld = InMemoryTurnWorld(fixture.load(1))
        val deployed = fixture.service(WorldId(1),deployWorld,deploymentPublished,movement=true).runDueGeneralTurns(deployDue)
        assertIs<HwihaTurnOutcome.Applied>(deployed.handled.single().hwihaOutcome)
        val deployedCold = InMemoryTurnWorld(fixture.load(1))
        val order = opensamguk.logic.input.HwihaCorpsOrder.read(deployedCold.getGeneralById(1)!!.meta,topology)
        assertEquals(deployId,order?.orderId); assertEquals(target,order?.destination)
        val march = assertNotNull(opensamguk.logic.input.HwihaCorpsMarchState.read(deployedCold.getGeneralById(1)!!.meta,topology,bundle.landMarchMetrics))
        assertTrue(march.checkpoint.cursor.edgeIndex > 0 || march.checkpoint.cursor.paidMm > 0,
            "the admitted deployment must make actual march progress")
        assertEquals(listOf("reservationAccepted","executionApplied"),jdbc.queryForList(
            "SELECT result_type FROM command_result WHERE world_id=1 AND request_id=? ORDER BY result_seq",String::class.java,deployId))
        mvc.perform(get("/api/command/result/{requestId}",deployId)).andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("executionApplied"))
            .andExpect(jsonPath("$.result.actionCode").value("action.deploy"))
        mvc.perform(get("/api/hwiha/deploy/options").param("generalId","1"))
            .andExpect(status().isOk).andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.order.orderId").value(deployId))
        assertTrue(fixture.service(WorldId(1),deployedCold,deploymentPublished,movement=true).runDueGeneralTurns(deployDue).handled.isEmpty())
        assertEquals(listOf(deployId,deployId),deploymentPublished)
        jdbc.update("UPDATE general SET user_id='43' WHERE world_id=1 AND id=1")
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(43L, null, emptyList())
        mvc.perform(get("/api/command/result/{requestId}", requestId))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("PENDING"))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(42L, null, emptyList())
        mvc.perform(get("/api/command/result/{requestId}", requestId))
            .andExpect(status().isOk).andExpect(jsonPath("$.type").value("executionApplied"))
    }

    @org.springframework.boot.test.context.TestConfiguration
    class Artifacts {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        fun testWorldArtifacts(
            worlds: opensamguk.gameapi.read.WorldStateReadRepository,
            cities: opensamguk.gameapi.read.CityReadRepository,
            pins: opensamguk.gameapi.read.WorldArtifactIdentityReadRepository,
        ) = opensamguk.gameapi.read.ActiveWorldArtifactResolver(worlds, cities, pins,
            opensamguk.infra.seed.HanWorldArtifactsResolver(java.nio.file.Path.of("../..")))
    }

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
        @Container @JvmStatic val redis: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        }
    }
}

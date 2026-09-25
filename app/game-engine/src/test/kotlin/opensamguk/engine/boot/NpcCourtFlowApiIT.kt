package opensamguk.engine.boot

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.hwiha.TurnOutcome
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

/** Synthetic people, actual HTTP inputs and NPC turns. This does not prove a playable Zhou scenario. */
@org.springframework.context.annotation.Import(NpcCourtFlowApiIT.Artifacts::class)
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
class NpcCourtFlowApiIT {
    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var json: ObjectMapper

    @AfterEach fun clearIdentity() = SecurityContextHolder.clearContext()

    @Test fun `HTTP enlistment reaches NPC first dispatch and owned acceptance without forced domain transitions`() {
        val dataSource = checkNotNull(jdbc.dataSource)
        val fixture = EnlistmentFixture(jdbc, JdbcFlushExecutor(NamedParameterJdbcTemplate(dataSource),
            TransactionTemplate(DataSourceTransactionManager(dataSource))))
        fixture.seed(1)
        jdbc.update("UPDATE world_state SET config=config || '{\"startYear\":200,\"unitSet\":\"che\"}'::jsonb WHERE id=1")
        jdbc.update("UPDATE general SET user_id='42' WHERE world_id=1 AND id=1")
        jdbc.update("UPDATE general SET turn_time='0200-01-02T00:00:00Z' WHERE world_id=1 AND id NOT IN (1,10)")
        val county = fixture.load(1).administrativeCountyIds.min()
        jdbc.update("UPDATE city SET nation_id=1 WHERE world_id=1 AND id=?", county)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(42L,null,emptyList())
        val mvc = MockMvcBuilders.webAppContextSetup(context).build()
        val response = mvc.perform(post("/api/command/action.enlist").param("generalId","1").param("turnIdx","0")
            .contentType("application/json").content("""{"mode":"NATION","targetId":1}"""))
            .andExpect(status().isAccepted).andReturn().response.contentAsString
        val enlistRequest = json.readTree(response)["requestId"].asText()
        val published = mutableListOf<String>()
        val late = Instant.parse("0200-01-01T03:00:01Z")
        val result = fixture.service(WorldId(1),InMemoryTurnWorld(fixture.load(1)),published).runDueGeneralTurns(late)
        assertEquals(listOf(1,10),result.handled.map { it.generalId })
        assertIs<TurnOutcome.Applied>(result.handled.first().hwihaOutcome)
        val issued = fixture.load(1)
        val actor = issued.generals.single { it.id==1 }
        val dispatch = assertNotNull(opensamguk.logic.input.DispatchState.read(actor.meta))
        assertEquals(10,dispatch.issuerId)
        assertEquals(county,dispatch.countyId)
        assertEquals(opensamguk.logic.input.DispatchStatus.PENDING,dispatch.status)
        assertTrue(dispatch.dispatchId.startsWith("npc-dispatch:"))
        assertEquals(listOf(enlistRequest,enlistRequest),published)
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM command_inbox",Int::class.java))
        mvc.perform(get("/api/commands/dispatches").param("generalId","1"))
            .andExpect(status().isOk).andExpect(jsonPath("$.dispatches[0].dispatchId").value(dispatch.dispatchId))
        val reply = json.readTree(mvc.perform(post("/api/commands/court/dispatchReply").param("generalId","1")
            .contentType("application/json").content("""{"dispatchId":"${dispatch.dispatchId}","accept":true}"""))
            .andExpect(status().isAccepted).andReturn().response.contentAsString)["requestId"].asText()
        val cold = fixture.service(WorldId(1),InMemoryTurnWorld(issued),published,intake=true)
        assertEquals(1,cold.runIntakeCommands())
        val accepted = fixture.load(1)
        val after = accepted.generals.single { it.id==1 }
        assertEquals(actor,after.copy(meta=actor.meta))
        assertEquals(issued.generalPositionSnapshot!!.statesByGeneralId,accepted.generalPositionSnapshot!!.statesByGeneralId)
        assertEquals(issued.retainers,accepted.retainers)
        assertEquals(county,opensamguk.logic.input.CountyAssignment.read(after.meta)!!.countyId)
        assertEquals(opensamguk.logic.input.DispatchStatus.ACCEPTED,
            opensamguk.logic.input.DispatchState.read(after.meta)!!.status)
        mvc.perform(get("/api/command/result/{requestId}",reply)).andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("executionApplied"))
        assertEquals(0,fixture.service(WorldId(1),InMemoryTurnWorld(accepted),published,intake=true).runIntakeCommands())
        assertEquals(listOf(enlistRequest,enlistRequest,reply),published)
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM general_turn",Int::class.java))
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

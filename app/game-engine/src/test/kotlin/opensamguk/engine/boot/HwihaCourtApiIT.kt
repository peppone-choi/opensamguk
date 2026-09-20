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
@org.springframework.context.annotation.Import(HwihaCourtApiIT.Artifacts::class)
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
class HwihaCourtApiIT {
    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var json: ObjectMapper

    @AfterEach fun clearIdentity() = SecurityContextHolder.clearContext()

    @Test fun `HTTP dispatch persists queue then executes on issuer turn and reply never consumes recipient turn`() {
        val source = checkNotNull(jdbc.dataSource)
        val flush = JdbcFlushExecutor(NamedParameterJdbcTemplate(source), TransactionTemplate(DataSourceTransactionManager(source)))
        val fixture = HwihaEnlistmentFixture(jdbc, flush)
        fixture.seed(1)
        val seeded = InMemoryTurnWorld(fixture.load(1))
        val recorder = opensamguk.engine.turn.ChangeRecorder()
        assertIs<opensamguk.engine.hwiha.EnlistmentExecution.Applied>(
            opensamguk.engine.hwiha.HwihaEnlistmentExecutor(seeded, recorder).execute(
                opensamguk.logic.input.EnlistmentRequest(1, opensamguk.logic.input.EnlistmentMode.NATION, 1)) { error("no draw") })
        flush.flush(opensamguk.engine.flush.DatabaseHooks.toFlushPayload(seeded, recorder, seeded.consumeDirtyState()))
        val county = seeded.administrativeCountyIds.min()
        jdbc.update("UPDATE city SET nation_id=1 WHERE world_id=1 AND id=?", county)
        jdbc.update("UPDATE general SET user_id='40' WHERE world_id=1 AND id=10")
        jdbc.update("UPDATE general SET user_id='42' WHERE world_id=1 AND id=1")
        jdbc.update("UPDATE general SET turn_time='0200-01-02T00:00:00Z' WHERE world_id=1 AND id<>10")
        val mvc = MockMvcBuilders.webAppContextSetup(context).build()
        fun login(id: Long) { SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(id, null, emptyList()) }
        fun dispatch() = post("/api/commands/court/dispatch").param("generalId", "10").contentType("application/json")
            .content("""{"targetGeneralId":1,"countyId":$county}""")
        SecurityContextHolder.clearContext()
        mvc.perform(dispatch()).andExpect(status().isUnauthorized)
        login(42)
        mvc.perform(dispatch()).andExpect(status().isForbidden)
        login(40)
        mvc.perform(get("/api/commands/dispatch-options").param("generalId","10").param("targetGeneralId","1"))
            .andExpect(status().isOk).andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.targets[0].generalId").value(1))
            .andExpect(jsonPath("$.counties[0].countyId").value(county))
            .andExpect(jsonPath("$.counties[0].available").value(true))
        mvc.perform(post("/api/commands/court/dispatch").param("generalId", "10").contentType("application/json")
            .content("""{"targetGeneralId":1,"targetGeneralId":1,"countyId":$county}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.status").value("BLOCKED"))
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM command_inbox",Int::class.java))
        val requestId=json.readTree(mvc.perform(dispatch()).andExpect(status().isAccepted).andExpect(jsonPath("$.status").value("AVAILABLE")).andReturn().response.contentAsString)["requestId"].asText()
        val payload=jdbc.queryForObject("SELECT payload::text FROM command_inbox WHERE request_id=?",String::class.java,requestId)!!
        val envelope=opensamguk.common.wire.decodeCommandEnvelope(payload)
        val typed=assertIs<opensamguk.common.wire.TurnDaemonCommand.HwihaCourtInput>(envelope.command)
        assertEquals(requestId,typed.requestId); assertEquals(40,typed.ownerUserId)
        val published=mutableListOf<String>()
        assertEquals(1,fixture.service(WorldId(1),InMemoryTurnWorld(fixture.load(1)),published,intake=true).runIntakeCommands())
        mvc.perform(get("/api/command/result/{requestId}",requestId)).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.phase").value("reservationAccepted"))
        mvc.perform(get("/api/commands/dispatches").param("generalId","10"))
            .andExpect(status().isOk).andExpect(jsonPath("$.queued.requestId").value(requestId))
            .andExpect(jsonPath("$.queued.ownerUserId").doesNotExist())
        mvc.perform(get("/api/commands/dispatch-options").param("generalId","10").param("targetGeneralId","1"))
            .andExpect(status().isOk).andExpect(jsonPath("$.code").value("ALREADY_QUEUED"))
            .andExpect(jsonPath("$.counties[0].available").value(false))
        val queued=fixture.load(1)
        assertNotNull(opensamguk.logic.input.HwihaQueuedDispatch.read(queued.generals.single { it.id==10 }.meta))
        assertNull(opensamguk.logic.input.HwihaDispatchState.read(queued.generals.single { it.id==1 }.meta))
        mvc.perform(dispatch()).andExpect(status().isOk).andExpect(jsonPath("$.code").value("ALREADY_QUEUED"))
        val late=Instant.parse("0200-01-01T03:00:01Z")
        fixture.service(WorldId(1),InMemoryTurnWorld(queued),published,intake=true).runDueGeneralTurns(late)
        val issued=fixture.load(1)
        assertNull(opensamguk.logic.input.HwihaQueuedDispatch.read(issued.generals.single { it.id==10 }.meta))
        assertEquals(opensamguk.logic.input.DispatchStatus.PENDING,
            opensamguk.logic.input.HwihaDispatchState.read(issued.generals.single { it.id==1 }.meta)!!.status)
        assertEquals(listOf("reservationAccepted","executionApplied"),jdbc.queryForList(
            "SELECT result_type FROM command_result WHERE request_id=? ORDER BY result_seq",String::class.java,requestId))
        mvc.perform(get("/api/command/result/{requestId}",requestId)).andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("executionApplied"))
        login(42)
        mvc.perform(get("/api/commands/dispatches").param("generalId","1")).andExpect(status().isOk)
            .andExpect(jsonPath("$.dispatches[0].dispatchId").value(requestId))
            .andExpect(jsonPath("$.dispatches[0].meta").doesNotExist())
            .andExpect(jsonPath("$.dispatches[0].issuerLabel").value("G10"))
            .andExpect(jsonPath("$.dispatches[0].targetLabel").value("G1"))
            .andExpect(jsonPath("$.dispatches[0].countyLabel").value("fixture-$county"))
        val reply=json.readTree(mvc.perform(post("/api/commands/court/dispatchReply").param("generalId","1")
            .contentType("application/json").content("""{"dispatchId":"$requestId","accept":false}"""))
            .andExpect(status().isAccepted).andReturn().response.contentAsString)["requestId"].asText()
        fixture.service(WorldId(1),InMemoryTurnWorld(issued),published,intake=true).runIntakeCommands()
        val after=fixture.load(1)
        val beforeActor=issued.generals.single { it.id==1 }; val afterActor=after.generals.single { it.id==1 }
        assertEquals(beforeActor,afterActor.copy(meta=beforeActor.meta))
        assertEquals(45,after.retainers.single { it.generalId==1 }.loyalty)
        assertEquals(29,opensamguk.logic.input.HwihaPersonPolicyState.read(afterActor.meta)!!.renownCapacity)
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM general_turn WHERE world_id=1",Int::class.java))
        assertEquals(listOf(requestId,requestId,reply),published)
        assertEquals(0,fixture.service(WorldId(1),InMemoryTurnWorld(after),published,intake=true).runIntakeCommands())
        jdbc.update("UPDATE general SET user_id='43' WHERE world_id=1 AND id=10")
        login(43)
        mvc.perform(get("/api/command/result/{requestId}",requestId)).andExpect(status().isOk).andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.result").doesNotExist())
        login(40)
        mvc.perform(get("/api/command/result/{requestId}",requestId)).andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("executionApplied"))
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

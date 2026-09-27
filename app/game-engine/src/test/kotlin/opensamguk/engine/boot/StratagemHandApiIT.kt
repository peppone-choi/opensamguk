package opensamguk.engine.boot

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.campaign.TurnOutcome
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

/** Actual personal-turn supply and owner-only read across the database boundary. */
@org.springframework.context.annotation.Import(StratagemHandApiIT.Artifacts::class)
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
class StratagemHandApiIT {
    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var json: ObjectMapper

    @AfterEach fun clearIdentity() = SecurityContextHolder.clearContext()

    @Test fun `only current owner reads committed hand without creating missing cards`() {
        val source=checkNotNull(jdbc.dataSource)
        val flush=JdbcFlushExecutor(NamedParameterJdbcTemplate(source),TransactionTemplate(DataSourceTransactionManager(source)))
        val fixture=EnlistmentFixture(jdbc,flush);fixture.seed(1)
        jdbc.update("UPDATE general SET user_id='42' WHERE world_id=1 AND id=1")
        jdbc.update("UPDATE general SET turn_time='0200-01-02T00:00:00Z' WHERE world_id=1 AND id<>1")
        val mvc=MockMvcBuilders.webAppContextSetup(context).build()
        fun request(id:String="1")=get("/api/commands/stratagem-hand").param("generalId",id)
        fun login(id:Long) { SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(id,null,emptyList()) }
        SecurityContextHolder.clearContext();mvc.perform(request()).andExpect(status().isUnauthorized)
        login(99);mvc.perform(request()).andExpect(status().isForbidden)
        mvc.perform(request("999999")).andExpect(status().isForbidden)
        login(42)
        val before=fixture.load(1).generals
        mvc.perform(request()).andExpect(status().isOk).andExpect(jsonPath("$.status").value("NOT_READY"))
        assertEquals(before,fixture.load(1).generals)
        fixture.service(WorldId(1),InMemoryTurnWorld(fixture.load(1)),mutableListOf())
            .runDueGeneralTurns(Instant.parse("0200-01-01T00:00:01Z"))
        val supplied=fixture.load(1).generals
        mvc.perform(request()).andExpect(status().isOk).andExpect(jsonPath("$.status").value("READY"))
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.cards.length()").value(2)).andExpect(jsonPath("$.cards[0].instanceId").value(1))
            .andExpect(jsonPath("$.cards[0].label").value("견벽")).andExpect(jsonPath("$.cards[1].label").value("간파"))
            .andExpect(jsonPath("$.canUse").value(false)).andExpect(jsonPath("$.drawPile").doesNotExist())
            .andExpect(jsonPath("$.meta").doesNotExist()).andExpect(jsonPath("$.lastDrawPhase").doesNotExist())
        assertEquals(supplied,fixture.load(1).generals)
        jdbc.update("UPDATE general SET user_id='99' WHERE world_id=1 AND id=1")
        mvc.perform(request()).andExpect(status().isForbidden)
        login(99)
        mvc.perform(request()).andExpect(status().isOk).andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.cards.length()").value(2))
        jdbc.update("UPDATE general SET meta=jsonb_set(meta,'{stratagemHand,ownerGeneralId}','7') WHERE world_id=1 AND id=1")
        mvc.perform(request()).andExpect(status().isOk).andExpect(jsonPath("$.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.cards.length()").value(0))
        jdbc.update("UPDATE world_state SET config=jsonb_set(config,'{ruleProfile}','\"SAMMO\"') WHERE id=1")
        mvc.perform(request()).andExpect(status().isConflict)
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
            opensamguk.infra.seed.WorldArtifactsResolver(java.nio.file.Path.of("../..")))
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

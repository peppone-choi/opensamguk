package opensamguk.gameapi.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.gameapi.controller.AdminReadController
import opensamguk.gameapi.read.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.Base64
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import org.junit.jupiter.api.Assertions.*

@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [ServerAdmissionSecurityChainTest.Config::class, GameApiSecurityConfig::class])
class ServerAdmissionSecurityChainTest {
    class Source : ServerAdmissionSource {
        var state: ServerPublicationState? = ServerPublicationState.PUBLIC
        var revision = 10L
        var reads = 0
        var delegate: ServerAdmissionSource? = null
        override fun readFresh(): ServerAdmissionRead {
            reads++
            delegate?.let { return it.readFresh() }
            return state?.let { ServerAdmissionRead.Known(ServerAdmissionSnapshot("pep", it, revision), System.nanoTime(), ServerAdmissionDraftBudget.totalNanos) }
                ?: ServerAdmissionRead.Unavailable
        }
    }
    interface Visits { fun visit() }
    @RestController class Routes(private val visits: Visits) {
        @RequestMapping("/api/world-events", "/api/events", "/api/command/push", "/api/internal/profile-icon-sync", "/api/admin/other", "/sse/probe")
        fun visit(): String { visits.visit(); return "ok" }
        @GetMapping("/actuator/health") fun health(): String = "UP"
    }
    @Configuration @EnableWebMvc @EnableWebSecurity open class Config {
        @Bean open fun source() = Source()
        @Bean open fun admissionPolicy(source: Source) = ServerAdmissionPolicy(source)
        @Bean open fun verifier() = GameApiJwtVerifier("", Base64.getEncoder().encodeToString(KEY), "2099-01-01T00:00:00Z")
        @Bean open fun jwtFilter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun visits() = mock(Visits::class.java)
        @Bean open fun routes(visits: Visits) = Routes(visits)
        @Bean open fun world() = mock(WorldStateReadRepository::class.java)
        @Bean open fun admin(verifier: GameApiJwtVerifier, world: WorldStateReadRepository) = AdminReadController(verifier,
            mock(NationReadRepository::class.java), mock(GeneralReadRepository::class.java), mock(CityReadRepository::class.java),
            mock(RankDataReadRepository::class.java), mock(DiplomacyReadRepository::class.java), mock(AdminGeneralLogReadRepository::class.java),
            world, mock(GameKvReadRepository::class.java), mock(GeneralTurnReadRepository::class.java), ScenarioTitleResolver(), "0")
    }
    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var source: Source
    @Autowired lateinit var visits: Visits
    @Autowired lateinit var world: WorldStateReadRepository
    private lateinit var mvc: MockMvc
    @BeforeEach fun setup() {
        reset(visits, world); source.state = ServerPublicationState.PUBLIC; source.revision++; source.reads = 0; source.delegate = null
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test fun `twelve concurrent public HTTP requests pass the actual security chain with bounded source lookup`() {
        val entered = CountDownLatch(8); val release = CountDownLatch(1)
        val calls = AtomicInteger(); val active = AtomicInteger(); val maximum = AtomicInteger()
        val body = """{"serverId":"pep","state":"PUBLIC","revision":"${source.revision}","sourceStatus":"KNOWN"}"""
        source.delegate = GatewayServerAdmissionSource("http://localhost", "pep", "test-only-service", ServerAdmissionTransport { _, _, _, _ ->
            calls.incrementAndGet(); maximum.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
            entered.countDown()
            try { assertTrue(release.await(2, TimeUnit.SECONDS)); ServerAdmissionHttpResponse(200, body) }
            finally { active.decrementAndGet() }
        })
        val results = (1..12).map { AtomicReference<Int>() }
        val failures = AtomicReference<Throwable>()
        val threads = results.map { result -> Thread {
            try { result.set(mvc.perform(get("/api/world-events")).andReturn().response.status) }
            catch (error: Throwable) { failures.compareAndSet(null, error) }
        } }
        try {
            threads.take(8).forEach(Thread::start); assertTrue(entered.await(2, TimeUnit.SECONDS))
            threads.drop(8).forEach(Thread::start)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (threads.drop(8).any { it.state != Thread.State.TIMED_WAITING } && System.nanoTime() < deadline) {
                LockSupport.parkNanos(100_000)
            }
            assertTrue(threads.drop(8).all { it.state == Thread.State.TIMED_WAITING })
            assertEquals(8, calls.get())
        } finally { release.countDown(); threads.forEach { if (it.state != Thread.State.NEW) it.join(3_000) } }
        assertNull(failures.get()); assertTrue(threads.none { it.isAlive })
        assertEquals(List(12) { 200 }, results.map { it.get() })
        assertEquals(12, calls.get()); assertEquals(8, maximum.get()); assertEquals(0, active.get())
        verify(visits, times(12)).visit()
    }

    @Test fun `local capacity is exact503 JSON with no-store before downstream`() {
        source.delegate = ServerAdmissionSource { ServerAdmissionRead.LocalCapacity }
        mvc.perform(get("/api/world-events"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error.code").value("SERVER_ADMISSION_UNAVAILABLE"))
            .andExpect(header().string("Cache-Control", "no-store"))
        verifyNoInteractions(visits, world)
    }

    @Test fun `PUBLIC preserves public read protected401 and valid principal paths`() {
        mvc.perform(get("/api/world-events")).andExpect(status().isOk)
        mvc.perform(get("/api/events")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/command/push").header("Authorization", "Bearer ${token()}")).andExpect(status().isOk)
        verify(visits, times(2)).visit()
        org.junit.jupiter.api.Assertions.assertEquals(3, source.reads)
    }

    @Test fun `VERIFYING distinguishes anonymous401 and valid USER or ordinary ADMIN403 on all verbs`() {
        source.state = ServerPublicationState.VERIFYING
        for (path in listOf("/api/world-events", "/api/events", "/api/command/push", "/api/internal/profile-icon-sync", "/api/admin/other", "/sse/probe")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized).andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"))
            for (role in listOf("USER", "ADMIN")) {
                for (method in listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) {
                    mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), path).header("Authorization", "Bearer ${token(role)}"))
                        .andExpect(status().isForbidden)
                    if (method != "HEAD") mvc.perform(get(path).header("Authorization", "Bearer ${token(role)}"))
                        .andExpect(jsonPath("$.error.code").value("SERVER_NOT_PUBLIC"))
                }
            }
        }
        verifyNoInteractions(visits, world)
    }

    @Test fun `UNKNOWN closes all ordinary roles with503 before downstream`() {
        source.state = null
        for (bearer in listOf(null, "invalid", token(), token("ADMIN"))) {
            val req = get("/api/world-events")
            if (bearer != null) req.header("Authorization", "Bearer $bearer")
            mvc.perform(req).andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.error.code").value("SERVER_ADMISSION_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control", "no-store"))
        }
        verifyNoInteractions(visits, world)
    }

    @Test fun `previous valid access rechecks latest revision rather than freezing PUBLIC`() {
        val access = token()
        mvc.perform(get("/api/world-events").header("Authorization", "Bearer $access")).andExpect(status().isOk)
        reset(visits); source.state = ServerPublicationState.VERIFYING; source.revision++
        mvc.perform(get("/api/world-events").header("Authorization", "Bearer $access")).andExpect(status().isForbidden)
        verifyNoInteractions(visits)
    }

    @Test fun `named actual raw current preserves401403 ADMIN0null and world503 despite UNKNOWN`() {
        source.state = null
        mvc.perform(get("/api/admin/reset-current")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/admin/reset-current").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/admin/reset-current").header("Authorization", "Bearer ${token()}")).andExpect(status().isForbidden)
        verifyNoInteractions(world)
        `when`(world.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1, config = emptyMap()))
        mvc.perform(get("/api/admin/reset-current").header("Authorization", "Bearer ${token("ADMIN")}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.worldId").value(1)).andExpect(jsonPath("$.generation").value("0"))
            .andExpect(jsonPath("$.maxGeneral").isEmpty)
        `when`(world.findProcessWorld()).thenReturn(null)
        mvc.perform(get("/api/admin/reset-current").header("Authorization", "Bearer ${token("ADMIN")}"))
            .andExpect(status().isServiceUnavailable)
        org.junit.jupiter.api.Assertions.assertEquals(0, source.reads)
    }

    @Test fun `named raw current has no HEAD POST trailing or neighboring broad exemption`() {
        source.state = null
        for (method in listOf("HEAD", "POST")) mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/api/admin/reset-current")
            .header("Authorization", "Bearer ${token("ADMIN")}")).andExpect(status().isServiceUnavailable)
        for (path in listOf("/api/admin/reset-current/", "/api/admin/other")) mvc.perform(get(path).header("Authorization", "Bearer ${token("ADMIN")}"))
            .andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.error.code").value("SERVER_ADMISSION_UNAVAILABLE"))
        verifyNoInteractions(world, visits)
    }

    @Test fun `existing exact health GET does not query failed publication source`() {
        source.state = null
        mvc.perform(get("/actuator/health")).andExpect(status().isOk).andExpect(content().string("UP"))
        org.junit.jupiter.api.Assertions.assertEquals(0, source.reads)
    }

    companion object {
        private val KEY = ByteArray(48) { (it + 1).toByte() }
        private fun token(role: String = "USER"): String {
            val now = Date()
            return Jwts.builder().subject("42").issuedAt(now).expiration(Date(now.time + 60_000))
                .claim(GatewayJwtClaims.TOKEN_TYPE, GatewayJwtClaims.ACCESS_TOKEN).claim(GatewayJwtClaims.ROLE, role)
                .signWith(Keys.hmacShaKeyFor(KEY)).compact()
        }
    }
}

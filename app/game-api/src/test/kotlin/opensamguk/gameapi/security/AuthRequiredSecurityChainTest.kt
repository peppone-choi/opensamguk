package opensamguk.gameapi.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.logic.command.CommandSchemaCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

/** Real security/JWT chain, with read probes isolated from each endpoint's separate ACL contract. */
@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [AuthRequiredSecurityChainTest.Config::class, GameApiSecurityConfig::class])
class AuthRequiredSecurityChainTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    open class Config {
        @Bean open fun verifier() = GameApiJwtVerifier("", SECRET, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun reader() = ReadProbe()
        @Bean open fun controller(reader: ReadProbe) = ProbeController(reader)
    }

    class ReadProbe {
        val calls = AtomicInteger()
        fun read(): String { calls.incrementAndGet(); return "ok" }
    }

    @RestController
    class ProbeController(private val reader: ReadProbe) {
        @RequestMapping("/api/{*path}", "/actuator/health")
        fun read() = reader.read()

        @PostMapping("/api/command/bulk")
        fun mutation(@RequestBody body: Map<String, Int>): String = reader.read()

        @GetMapping("/api/operations/foreign")
        fun foreign(): String = throw AccessDeniedException("synthetic foreign-nation denial")
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var reader: ReadProbe
    private lateinit var mvc: MockMvc

    @BeforeEach fun setup() {
        reader.calls.set(0)
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test fun everyProtectedMatcherRejectsMissingExpiredInvalidAndRefreshBeforeReads() {
        for (route in PROTECTED) for (bearer in unauthenticated()) {
            mvc.perform(build(route, bearer)).andExpect(status().isUnauthorized)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(AUTH_ERROR, true))
        }
        assertEquals(0, reader.calls.get())
    }

    @Test fun signedUserAndAdminReachEveryExistingProtectedMatcherSample() {
        for (role in listOf("USER", "ADMIN")) for (route in PROTECTED) {
            mvc.perform(build(route, "Bearer " + token(role = role))).andExpect(status().isOk)
        }
        assertEquals(PROTECTED.size * 2, reader.calls.get())
    }

    @Test fun unauthenticatedMalformedMutationBodyIsRejectedBeforeJsonParsing() {
        for (bearer in unauthenticated()) {
            mvc.perform(build(Route(HttpMethod.POST, "/api/command/bulk"), bearer, "{broken"))
                .andExpect(status().isUnauthorized).andExpect(content().json(AUTH_ERROR, true))
        }
        assertEquals(0, reader.calls.get())
    }

    @Test fun authenticatedMalformedBodyReachesParserWithoutDownstreamRead() {
        mvc.perform(build(Route(HttpMethod.POST, "/api/command/bulk"), "Bearer " + token(), "{broken"))
            .andExpect(status().isBadRequest)
        assertEquals(0, reader.calls.get())
    }

    @Test fun authenticatedForeignDenialRemainsForbidden() {
        for (role in listOf("USER", "ADMIN")) {
            mvc.perform(build(Route(HttpMethod.GET, "/api/operations/foreign"), "Bearer " + token(role = role)))
                .andExpect(status().isForbidden)
        }
        assertEquals(0, reader.calls.get())
    }

    @Test fun publicRoutesRemainPublicForEveryBearerState() {
        val identities = unauthenticated() + listOf("Bearer " + token(), "Bearer " + token(role = "ADMIN"))
        for (route in PUBLIC) for (bearer in identities) {
            mvc.perform(build(route, bearer)).andExpect(status().isOk).andExpect(content().string("ok"))
        }
        assertEquals(PUBLIC.size * identities.size, reader.calls.get())
    }

    private fun build(route: Route, bearer: String?, body: String = "{\"value\":1}"): MockHttpServletRequestBuilder {
        val req = request(route.method, route.path)
        if (route.method == HttpMethod.POST) req.contentType(MediaType.APPLICATION_JSON).content(body)
        bearer?.let { req.header("Authorization", it) }
        return req
    }

    private fun unauthenticated(): List<String?> = listOf(null, "Bearer invalid", "Bearer " + token(expired = true),
        "Bearer " + token(type = GatewayJwtClaims.REFRESH_TOKEN))

    private fun token(role: String = "USER", type: String = GatewayJwtClaims.ACCESS_TOKEN, expired: Boolean = false): String {
        val now = Date()
        return Jwts.builder().subject("7").issuedAt(Date(now.time - 120_000))
            .expiration(Date(now.time + if (expired) -60_000 else 600_000))
            .claim(GatewayJwtClaims.TOKEN_TYPE, type).claim(GatewayJwtClaims.ROLE, role)
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact()
    }

    private data class Route(val method: HttpMethod, val path: String)

    companion object {
        // Public, synthetic fixture only. No environment or production key is read.
        private const val SECRET = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU="
        private const val AUTH_ERROR = """{"error":{"code":"AUTH_REQUIRED","message":"로그인이 필요합니다."}}"""

        // Every existing authenticated matcher group, including its exact and wildcard samples.
        private val LEGACY_RECRUIT_PATH = "/api/command/" + CommandSchemaCatalog.garrisonRecruitSchema.legacyAliases.single()
        private val LEGACY_TRANSPORT_PATH = "/api/command/" + CommandSchemaCatalog.cityTransportSchema.legacyAliases.single()
        private val PROTECTED = listOf(
            Route(HttpMethod.POST, "/api/command/bulk"),
            Route(HttpMethod.POST, "/api/command/nation/push"),
            Route(HttpMethod.GET, "/api/my-page"), Route(HttpMethod.GET, "/api/my-generals"),
            Route(HttpMethod.GET, "/api/my-cities"), Route(HttpMethod.GET, "/api/my-nation-detail"),
            Route(HttpMethod.GET, "/api/events"),
            Route(HttpMethod.GET, "/api/operations"), Route(HttpMethod.GET, "/api/operations/17"),
            Route(HttpMethod.GET, "/api/my-battle-plans"), Route(HttpMethod.GET, "/api/battles/replays"),
            Route(HttpMethod.GET, "/api/battles/replays/42"),
            Route(HttpMethod.POST, "/api/battles/1/battle-1/join-ticket"),
            Route(HttpMethod.GET, "/api/v2/commands/metadata"), Route(HttpMethod.POST, "/api/v2/commands/enqueue"),
            Route(HttpMethod.GET, "/api/v2/garrison-recruit"), Route(HttpMethod.POST, "/api/v2/garrison-recruit"),
            Route(HttpMethod.GET, "/api/v2/city-transport"), Route(HttpMethod.POST, "/api/v2/city-transport"),
            Route(HttpMethod.GET, LEGACY_RECRUIT_PATH), Route(HttpMethod.POST, LEGACY_RECRUIT_PATH),
            Route(HttpMethod.GET, LEGACY_TRANSPORT_PATH), Route(HttpMethod.POST, LEGACY_TRANSPORT_PATH),
        )
        private val PUBLIC = listOf(
            Route(HttpMethod.GET, "/actuator/health"), Route(HttpMethod.GET, "/api/const"),
            Route(HttpMethod.GET, "/api/map"), Route(HttpMethod.GET, "/api/menu"),
            Route(HttpMethod.GET, "/api/command/metadata"), Route(HttpMethod.GET, "/api/mailbox"),
            Route(HttpMethod.GET, "/api/events/extra"), Route(HttpMethod.GET, "/api/operations/not/a-match"),
        )
    }
}

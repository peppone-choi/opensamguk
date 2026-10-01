package opensamguk.gameapi.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

/** Actual production chain with a dispatch probe; C10's reader/pin/cache gates are tested separately. */
@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [ProvinceNamesMatcherSecurityChainTest.Config::class, GameApiSecurityConfig::class])
class ProvinceNamesMatcherSecurityChainTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    open class Config {
        @Bean open fun verifier() = GameApiJwtVerifier("", JWT_FIXTURE, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun reader() = ReadProbe()
        @Bean open fun controller(reader: ReadProbe) = DispatchProbe(reader)
    }

    class ReadProbe {
        val calls = AtomicInteger()
        fun read(): String {
            calls.incrementAndGet()
            return PUBLIC_BODY
        }
    }

    @RestController
    class DispatchProbe(private val reader: ReadProbe) {
        // Deliberately accepts all methods: removing denyAll must reach this probe and fail the test.
        @RequestMapping(value = ["/api/map/provinces/names", "/api/map/provinces/names/v1"],
            produces = ["application/json;charset=UTF-8"])
        fun names() = reader.read()

        @RequestMapping("/api/map/provinces/names-extra", "/api/map/provinces/names/v1/extra")
        fun adjacent() = "adjacent"

        @RequestMapping("/api/events", "/api/operations")
        fun protectedRead() = "protected"
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var reader: ReadProbe
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        reader.calls.set(0)
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test
    fun `both exact GETs expose identical public body for anonymous invalid refresh USER and ADMIN`() {
        val identities = identities()
        for (path in PATHS) {
            for (authorization in identities) {
                val req = get(path)
                authorization?.let { req.header("Authorization", it) }
                mvc.perform(req).andExpect(status().isOk).andExpect(content().string(PUBLIC_BODY))
            }
        }
        assertEquals(PATHS.size * identities.size, reader.calls.get())
    }

    @Test
    fun `all unsupported methods are forbidden before reader for every identity`() {
        for (path in PATHS) {
            for (method in listOf(HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.POST,
                HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
                for (authorization in identities()) {
                    val req = request(method, path)
                    authorization?.let { req.header("Authorization", it) }
                    mvc.perform(req).andExpect(status().isForbidden)
                }
            }
        }
        assertEquals(0, reader.calls.get())
    }

    @Test
    fun `exact names matchers do not capture adjacent paths`() {
        for (path in listOf("/api/map/provinces/names-extra", "/api/map/provinces/names/v1/extra")) {
            mvc.perform(request(HttpMethod.POST, path))
                .andExpect(status().isOk).andExpect(content().string("adjacent"))
        }
        assertEquals(0, reader.calls.get())
    }

    @Test
    fun `existing identity required routes remain protected`() {
        for (path in listOf("/api/events", "/api/operations")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized)
                .andExpect(content().json(AUTH_REQUIRED_BODY, true))
            mvc.perform(get(path).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized)
                .andExpect(content().json(AUTH_REQUIRED_BODY, true))
            mvc.perform(get(path).header("Authorization", "Bearer ${token("USER")}"))
                .andExpect(status().isOk).andExpect(content().string("protected"))
        }
        assertEquals(0, reader.calls.get())
    }

    private fun identities(): List<String?> = listOf(null, "Bearer invalid",
        "Bearer ${token("USER", GatewayJwtClaims.REFRESH_TOKEN)}",
        "Bearer ${token("USER")}", "Bearer ${token("ADMIN")}")

    private fun token(role: String, type: String = GatewayJwtClaims.ACCESS_TOKEN): String {
        val now = Date()
        return Jwts.builder().subject("7").issuedAt(now).expiration(Date(now.time + 60000))
            .claim(GatewayJwtClaims.TOKEN_TYPE, type).claim(GatewayJwtClaims.ROLE, role)
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(JWT_FIXTURE))).compact()
    }

    companion object {
        private const val AUTH_REQUIRED_BODY = """{"error":{"code":"AUTH_REQUIRED","message":"로그인이 필요합니다."}}"""
        private val PATHS = listOf("/api/map/provinces/names", "/api/map/provinces/names/v1")
        private const val PUBLIC_BODY = "{\"names\":[{\"provinceId\":\"fixture-id\",\"displayName\":\"시험현\"}]}"
        private const val JWT_FIXTURE = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU="
    }
}

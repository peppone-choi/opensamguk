package opensamguk.gameapi.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.Date

/** 실제 생성 controller 통합은 생성 PR에서 확인한다. 여기서는 네 경로의 chain 경계를 검증한다. */
@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [GeneralCreationSecurityChainTest.Config::class, GameApiSecurityConfig::class])
class GeneralCreationSecurityChainTest {
    interface AdmissionProbe { fun visit(route: String, accountId: Long?) }

    @RestController
    class Routes(private val probe: AdmissionProbe) {
        private fun result(route: String, accountId: Long?): Map<String, Long?> {
            probe.visit(route, accountId)
            return mapOf("accountId" to accountId)
        }
        @PostMapping("/api/generals/creation")
        fun submit(@AuthenticationPrincipal accountId: Long?) = result("submit", accountId)
        @GetMapping("/api/generals/creation/{requestId}")
        fun receipt(@AuthenticationPrincipal accountId: Long?) = result("receipt", accountId)
        @GetMapping("/api/generals/creation/options")
        fun options(@AuthenticationPrincipal accountId: Long?) = result("options", accountId)
        @GetMapping("/api/generals/creation/historical")
        fun historical(@AuthenticationPrincipal accountId: Long?) = result("historical", accountId)
    }

    @Configuration @EnableWebMvc @EnableWebSecurity
    open class Config {
        @Bean open fun admissionPolicy() = opensamguk.gameapi.security.ServerAdmissionTestFixture.publicPolicy()
        @Bean open fun verifier() = GameApiJwtVerifier("", JWT_FIXTURE, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun probe() = mock(AdmissionProbe::class.java)
        @Bean open fun routes(probe: AdmissionProbe) = Routes(probe)
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var probe: AdmissionProbe
    private lateinit var mvc: MockMvc

    @BeforeEach fun setup() {
        reset(probe)
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test fun `생성 접수는 계정 인증을 요구한다`() = assertAuthRequired("submit")
    @Test fun `생성 결과는 계정 인증을 요구한다`() = assertAuthRequired("receipt")
    @Test fun `생성 선택지는 계정 인증을 요구한다`() = assertAuthRequired("options")
    @Test fun `역사 후보는 계정 인증을 요구한다`() = assertAuthRequired("historical")

    private fun assertAuthRequired(route: String) {
        val rejected = listOf(null, "invalid", jwt(validForMillis = -60000),
            jwt(tokenType = GatewayJwtClaims.REFRESH_TOKEN), jwt(role = "UNKNOWN"))
        for (bearer in rejected) {
            val request = request(route)
            if (bearer != null) request.header("Authorization", "Bearer $bearer")
            mvc.perform(request).andExpect(status().isUnauthorized)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.error.message").value("로그인이 필요합니다."))
            verifyNoInteractions(probe)
        }
    }

    @Test fun `장수 없는 USER와 ADMIN access 계정이 네 경로에 도달한다`() {
        for (role in listOf("USER", "ADMIN")) {
            reset(probe)
            for (route in ROUTES) {
                mvc.perform(request(route).header("Authorization", "Bearer ${jwt(role = role)}"))
                    .andExpect(status().isOk).andExpect(jsonPath("$.accountId").value(42))
                verify(probe).visit(route, 42L)
            }
            verifyNoMoreInteractions(probe)
        }
    }

    @Test fun `입력과 검증된 principal을 구분한다`() {
        for (route in ROUTES) {
            mvc.perform(request(route).param("userId", "999").param("generalId", "999")
                .header("Authorization", "Bearer ${jwt()}"))
                .andExpect(status().isOk).andExpect(jsonPath("$.accountId").value(42))
            verify(probe).visit(route, 42L)
        }
        verifyNoMoreInteractions(probe)
    }

    @Test fun `인증된 계정의 도메인 권한 거절은403을 유지한다`() {
        doThrow(ResponseStatusException(HttpStatus.FORBIDDEN)).`when`(probe).visit("submit", 42L)
        mvc.perform(request("submit").header("Authorization", "Bearer ${jwt()}"))
            .andExpect(status().isForbidden)
        verify(probe).visit("submit", 42L)
        verifyNoMoreInteractions(probe)
    }

    private fun request(route: String): MockHttpServletRequestBuilder = when (route) {
        "submit" -> post("/api/generals/creation").contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":999}")
        "receipt" -> get("/api/generals/creation/00000000-0000-0000-0000-000000000001")
        "options" -> get("/api/generals/creation/options")
        "historical" -> get("/api/generals/creation/historical")
        else -> error("unknown route: $route")
    }

    private fun jwt(role: String = "USER", tokenType: String = GatewayJwtClaims.ACCESS_TOKEN,
                    validForMillis: Long = 60000): String {
        val now = Date()
        return Jwts.builder().subject("42").issuedAt(now).expiration(Date(now.time + validForMillis))
            .claim(GatewayJwtClaims.TOKEN_TYPE, tokenType).claim(GatewayJwtClaims.ROLE, role)
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(JWT_FIXTURE))).compact()
    }

    companion object {
        val ROUTES = listOf("submit", "receipt", "options", "historical")
        const val JWT_FIXTURE = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU="
    }
}

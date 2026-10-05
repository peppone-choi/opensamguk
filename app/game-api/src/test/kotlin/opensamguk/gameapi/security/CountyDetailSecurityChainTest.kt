package opensamguk.gameapi.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.gameapi.dto.CountyDetailDto
import opensamguk.gameapi.read.CampForbidden
import opensamguk.gameapi.read.CountyDetailReader
import opensamguk.gameapi.web.CountyDetailController
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.Date

@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [CountyDetailSecurityChainTest.Config::class, GameApiSecurityConfig::class])
class CountyDetailSecurityChainTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    open class Config {
        @Bean open fun verifier() = GameApiJwtVerifier("", SECRET, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun reader() = mock(CountyDetailReader::class.java)
        @Bean open fun county(reader: CountyDetailReader) = CountyDetailController(reader)
    }
    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var reader: CountyDetailReader
    private lateinit var mvc: MockMvc

    @BeforeEach fun setup() {
        TestSecurityContextHolder.clearContext()
        reset(reader)
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }
    @AfterEach fun cleanup() = TestSecurityContextHolder.clearContext()

    @Test fun `anonymous and invalid JWT never reach the county reader`() {
        mvc.perform(get("/api/counties/3?generalId=1")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/counties/3?generalId=1").header("Authorization", "Bearer invalid"))
            .andExpect(status().isUnauthorized)
        verifyNoInteractions(reader)
    }

    @Test fun `expired and refresh JWT never reach the county reader`() {
        mvc.perform(get("/api/counties/3?generalId=1").header("Authorization", "Bearer ${token(expired = true)}"))
            .andExpect(status().isUnauthorized)
        mvc.perform(get("/api/counties/3?generalId=1").header("Authorization", "Bearer ${token(type = "refresh")}"))
            .andExpect(status().isUnauthorized)
        verifyNoInteractions(reader)
    }

    @Test fun `verified principal reaches flat county core with explicit null values and no store`() {
        `when`(reader.county(3, 1, 41)).thenReturn(CountyDetailDto("READY", 3, name = "현", visibility = "INTEL", intelAgeTurns = 2))
        mvc.perform(get("/api/counties/3?generalId=1").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.cityId").value(3)).andExpect(jsonPath("$.visibility").value("INTEL"))
            .andExpect(jsonPath("$.intelAgeTurns").value(2))
            .andExpect(jsonPath("$.population").value(nullValue<Any>()))
            .andExpect(jsonPath("$.garrison").value(nullValue<Any>()))
            .andExpect(jsonPath("$.income").value(nullValue<Any>()))
        verify(reader).county(3, 1, 41)
    }

    @Test fun `USER and ADMIN borrowed selections both remain forbidden`() {
        `when`(reader.county(3, 2, 41)).thenThrow(CampForbidden())
        for (role in listOf("USER", "ADMIN")) {
            mvc.perform(get("/api/counties/3?generalId=2").header("Authorization", "Bearer ${token(role = role)}"))
                .andExpect(status().isForbidden)
        }
        verify(reader, times(2)).county(3, 2, 41)
    }

    @Test fun `absent administrative county returns uncached not found`() {
        mvc.perform(get("/api/counties/4?generalId=1").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isNotFound).andExpect(header().string("Cache-Control", containsString("no-store")))
        verify(reader).county(4, 1, 41)
    }

    @Test fun `missing general selection is rejected before reading`() {
        mvc.perform(get("/api/counties/3").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isBadRequest)
        verifyNoInteractions(reader)
    }

    private fun token(role: String = "USER", type: String = GatewayJwtClaims.ACCESS_TOKEN, expired: Boolean = false): String {
        val now = Date()
        return Jwts.builder().subject("41").issuedAt(Date(now.time - 120000))
            .expiration(Date(now.time + if (expired) -60000 else 60000))
            .claim(GatewayJwtClaims.TOKEN_TYPE, type).claim(GatewayJwtClaims.ROLE, role)
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact()
    }
    companion object { const val SECRET = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU=" }
}

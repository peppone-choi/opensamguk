package opensamguk.gameapi.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.gameapi.dto.CountyDirectoryResponse
import opensamguk.gameapi.read.CampForbidden
import opensamguk.gameapi.read.CountyDirectoryReader
import opensamguk.gameapi.web.CountyDirectoryController
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
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
@ContextConfiguration(classes = [CountyDirectorySecurityChainTest.Config::class, GameApiSecurityConfig::class])
class CountyDirectorySecurityChainTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    open class Config {
        @Bean open fun verifier() = GameApiJwtVerifier("", SECRET, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun reader() = mock(CountyDirectoryReader::class.java)
        @Bean open fun directory(reader: CountyDirectoryReader) = CountyDirectoryController(reader)
    }
    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var reader: CountyDirectoryReader
    private lateinit var mvc: MockMvc
    @BeforeEach fun setup() {
        reset(reader)
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test fun `actual JWT chain denies anonymous and invalid county reads`() {
        mvc.perform(get("/api/counties?generalId=1")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/counties?generalId=1").header("Authorization", "Bearer invalid"))
            .andExpect(status().isUnauthorized)
        verifyNoInteractions(reader)
    }

    @Test fun `signed principal reaches county read and foreign identity denial stays forbidden`() {
        `when`(reader.counties(1, 41, "NATION", null)).thenReturn(CountyDirectoryResponse("READY", "NATION"))
        mvc.perform(get("/api/counties?generalId=1").header("Authorization", "Bearer ${token("USER")}"))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.period").value("GAME_MONTH"))
        verify(reader).counties(1, 41, "NATION", null)
        `when`(reader.counties(2, 41, "NATION", null)).thenThrow(CampForbidden())
        mvc.perform(get("/api/counties?generalId=2").header("Authorization", "Bearer ${token("USER")}"))
            .andExpect(status().isForbidden)
    }

    private fun token(role: String): String {
        val now = Date()
        return Jwts.builder().subject("41").issuedAt(now).expiration(Date(now.time + 60000))
            .claim(GatewayJwtClaims.TOKEN_TYPE, GatewayJwtClaims.ACCESS_TOKEN).claim(GatewayJwtClaims.ROLE, role)
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact()
    }
    companion object { const val SECRET = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU=" }
}

package opensamguk.gameapi.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.gameapi.dto.AdminNationDirectory
import opensamguk.gameapi.dto.PeoplePage
import opensamguk.gameapi.read.CampaignDirectoryReader
import opensamguk.gameapi.web.AdminCampaignDirectoryController
import opensamguk.gameapi.web.CampaignDirectoryController
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
@ContextConfiguration(classes = [CampaignDirectorySecurityChainTest.Config::class, GameApiSecurityConfig::class])
class CampaignDirectorySecurityChainTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    open class Config {
        @Bean open fun admissionPolicy() = opensamguk.gameapi.security.ServerAdmissionTestFixture.publicPolicy()
        @Bean open fun verifier() = GameApiJwtVerifier("", SECRET, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun reader() = mock(CampaignDirectoryReader::class.java)
        @Bean open fun directory(reader: CampaignDirectoryReader) = CampaignDirectoryController(reader)
        @Bean open fun admin(reader: CampaignDirectoryReader, verifier: GameApiJwtVerifier) = AdminCampaignDirectoryController(reader, verifier)
    }
    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var reader: CampaignDirectoryReader
    private lateinit var mvc: MockMvc
    @BeforeEach fun setup() {
        reset(reader)
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test fun `actual JWT chain denies anonymous invalid and ordinary-user administrator reads`() {
        for (path in listOf("/api/people", "/api/nation/summary?generalId=1", "/api/admin/nations", "/api/admin/people")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized)
            mvc.perform(get(path).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized)
        }
        for (path in listOf("/api/admin/nations", "/api/admin/people")) {
            mvc.perform(get(path).header("Authorization", "Bearer ${token("USER")}"))
                .andExpect(status().isForbidden).andExpect(header().string("Cache-Control", "no-store"))
        }
        verifyNoInteractions(reader)
    }

    @Test fun `signed user identity and signed admin role reach only their own read surface`() {
        `when`(reader.people(41, "ALL", "", "ID", null, 50, "ASC")).thenReturn(PeoplePage("READY"))
        `when`(reader.adminNations()).thenReturn(AdminNationDirectory("READY"))
        mvc.perform(get("/api/people?generalId=999").header("Authorization", "Bearer ${token("USER")}"))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.status").value("READY"))
        verify(reader).people(41, "ALL", "", "ID", null, 50, "ASC")
        mvc.perform(get("/api/admin/nations").header("Authorization", "Bearer ${token("ADMIN")}"))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
        verify(reader).adminNations()
    }

    @Test fun `signed principals forward server ordering and opaque cursor while ignoring actor query`() {
        `when`(reader.people(41, "NATION", "ㅂㅌ", "STRENGTH", "opaque", 2, "DESC")).thenReturn(PeoplePage("READY"))
        `when`(reader.adminPeople("베타", "AFFILIATION", null, 3, "DESC")).thenReturn(PeoplePage("READY"))
        mvc.perform(get("/api/people").param("scope", "NATION").param("q", "ㅂㅌ")
            .param("sort", "STRENGTH").param("direction", "DESC").param("cursor", "opaque")
            .param("limit", "2").param("generalId", "999").header("Authorization", "Bearer ${token("USER")}"))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
        verify(reader).people(41, "NATION", "ㅂㅌ", "STRENGTH", "opaque", 2, "DESC")
        mvc.perform(get("/api/admin/people").param("q", "베타").param("sort", "AFFILIATION")
            .param("direction", "DESC").param("limit", "3").header("Authorization", "Bearer ${token("ADMIN")}"))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
        verify(reader).adminPeople("베타", "AFFILIATION", null, 3, "DESC")
    }

    private fun token(role: String): String {
        val now = Date()
        return Jwts.builder().subject("41").issuedAt(now).expiration(Date(now.time + 60000))
            .claim(GatewayJwtClaims.TOKEN_TYPE, GatewayJwtClaims.ACCESS_TOKEN).claim(GatewayJwtClaims.ROLE, role)
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact()
    }
    companion object { const val SECRET = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU=" }
}

package opensamguk.gameapi.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.gameapi.dto.*
import opensamguk.gameapi.people.*
import opensamguk.gameapi.read.CampForbidden
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
@ContextConfiguration(classes = [PersonDetailSecurityChainTest.Config::class, GameApiSecurityConfig::class])
class PersonDetailSecurityChainTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    open class Config {
        @Bean open fun admissionPolicy(): ServerAdmissionPolicy = ServerAdmissionTestFixture.publicPolicy()
        @Bean open fun verifier() = GameApiJwtVerifier("", SECRET, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun reader() = mock(PersonDetailReader::class.java)
        @Bean open fun query(reader: PersonDetailReader) = PersonDetailQuery(reader)
        @Bean open fun person(query: PersonDetailQuery) = PersonDetailController(query)
    }
    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var reader: PersonDetailReader
    private lateinit var mvc: MockMvc

    @BeforeEach fun setup() {
        TestSecurityContextHolder.clearContext()
        reset(reader)
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }
    @AfterEach fun cleanup() = TestSecurityContextHolder.clearContext()

    @Test fun `anonymous and invalid JWT never reach the person reader`() {
        mvc.perform(get("/api/people/3?generalId=1")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/people/3?generalId=1").header("Authorization", "Bearer invalid"))
            .andExpect(status().isUnauthorized)
        verifyNoInteractions(reader)
    }

    @Test fun `expired and refresh JWT never reach the person reader`() {
        mvc.perform(get("/api/people/3?generalId=1").header("Authorization", "Bearer ${token(expired = true)}"))
            .andExpect(status().isUnauthorized)
        mvc.perform(get("/api/people/3?generalId=1").header("Authorization", "Bearer ${token(type = "refresh")}"))
            .andExpect(status().isUnauthorized)
        verifyNoInteractions(reader)
    }

    @Test fun `verified principal reaches the reader and private nulls are explicit and uncached`() {
        `when`(reader.person(3, 1, 41)).thenReturn(PersonDetailDto("READY", 3, relation = "SAME_NATION", name = "동료",
            portrait = DirectoryPortrait(null, 0), stats = DirectoryStats(1, 2, 3, 4, 5),
            unavailableReasons = mapOf("/location" to "NOT_AUTHORIZED", "/placement" to "CONTRACT_PENDING")))
        mvc.perform(get("/api/people/3?generalId=1").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.generalId").value(3)).andExpect(jsonPath("$.relation").value("SAME_NATION"))
            .andExpect(jsonPath("$.stats.strength").value(2))
            .andExpect(jsonPath("$.location").hasJsonPath()).andExpect(jsonPath("$.location").value(nullValue()))
            .andExpect(jsonPath("$.bonds").hasJsonPath()).andExpect(jsonPath("$.bonds").value(nullValue()))
            .andExpect(jsonPath("$.retinue").hasJsonPath()).andExpect(jsonPath("$.retinue").value(nullValue()))
            .andExpect(jsonPath("$.placement").hasJsonPath()).andExpect(jsonPath("$.placement").value(nullValue()))
            .andExpect(jsonPath("$.offices").hasJsonPath()).andExpect(jsonPath("$.offices").value(nullValue()))
            .andExpect(jsonPath("$.unavailableReasons['/location']").value("NOT_AUTHORIZED"))
            .andExpect(jsonPath("$.locationCityId").doesNotExist()).andExpect(jsonPath("$.enemy").doesNotExist())
        verify(reader).person(3, 1, 41)
    }

    @Test fun `retinue wire carries the card bond and location shapes`() {
        `when`(reader.person(2, 1, 41)).thenReturn(PersonDetailDto("READY", 2, relation = "RETINUE", name = "휘하",
            location = PersonLocationDto(3, "양적현"), injured = true,
            bonds = listOf(BondDto("HYANGDANG", "향당", "패국 초현", "沛國譙縣", sameAsLord = true)),
            retinue = PersonRetinueCardDto(11, 85, 12, "참모", "대기", null)))
        mvc.perform(get("/api/people/2?generalId=1").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.location.cityId").value(3)).andExpect(jsonPath("$.location.name").value("양적현"))
            .andExpect(jsonPath("$.injured").value(true))
            .andExpect(jsonPath("$.bonds[0].label").value("향당")).andExpect(jsonPath("$.bonds[0].sameAsLord").value(true))
            .andExpect(jsonPath("$.retinue.retainerId").value(11)).andExpect(jsonPath("$.retinue.cost").value(12))
            .andExpect(jsonPath("$.retinue.departureOrder").hasJsonPath())
    }

    @Test fun `USER and ADMIN borrowed selections both remain forbidden`() {
        `when`(reader.person(3, 2, 41)).thenThrow(CampForbidden())
        for (role in listOf("USER", "ADMIN")) {
            mvc.perform(get("/api/people/3?generalId=2").header("Authorization", "Bearer ${token(role = role)}"))
                .andExpect(status().isForbidden)
        }
        verify(reader, times(2)).person(3, 2, 41)
    }

    @Test fun `absent person returns uncached not found`() {
        mvc.perform(get("/api/people/99?generalId=1").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isNotFound).andExpect(header().string("Cache-Control", containsString("no-store")))
        verify(reader).person(99, 1, 41)
    }

    @Test fun `missing general selection is rejected before reading`() {
        mvc.perform(get("/api/people/3").header("Authorization", "Bearer ${token()}"))
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

package opensamguk.gameapi.security

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.gameapi.read.*
import opensamguk.gameapi.web.CampController
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
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
import java.util.Optional

/** Actual JWT filter, principal binding, controller and reader; only data sources are mocks. */
@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [CountySpecialtySecurityChainTest.Config::class, GameApiSecurityConfig::class])
class CountySpecialtySecurityChainTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    open class Config {
        @Bean open fun verifier() = GameApiJwtVerifier("", SECRET, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun generals() = mock(GeneralReadRepository::class.java)
        @Bean open fun worlds() = mock(WorldStateReadRepository::class.java)
        @Bean open fun cities() = mock(CityReadRepository::class.java)
        @Bean open fun artifacts() = mock(ActiveWorldArtifactResolver::class.java)
        @Bean open fun geography() = mock(CityGeography::class.java)
        @Bean open fun mapper() = ObjectMapper().findAndRegisterModules().setSerializationInclusion(JsonInclude.Include.NON_NULL)
        @Bean open fun reader(generals: GeneralReadRepository, worlds: WorldStateReadRepository,
                             cities: CityReadRepository, artifacts: ActiveWorldArtifactResolver,
                             geography: CityGeography, mapper: ObjectMapper) = CampReader(
            generals, worlds, mock(NationReadRepository::class.java), cities,
            mock(RetainerReadRepository::class.java), mock(GameKvReadRepository::class.java),
            artifacts, CampLedgers(mapper), geography, mapper)
        @Bean open fun controller(reader: CampReader) = CampController(reader)
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var generals: GeneralReadRepository
    @Autowired lateinit var worlds: WorldStateReadRepository
    @Autowired lateinit var cities: CityReadRepository
    @Autowired lateinit var artifacts: ActiveWorldArtifactResolver
    @Autowired lateinit var geography: CityGeography
    @Autowired lateinit var reader: CampReader
    private lateinit var mvc: MockMvc

    @BeforeEach fun setup() {
        reset(generals, worlds, cities, artifacts, geography)
        val world = WorldStateReadEntity(id = 1, config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN"))
        val bundle = mock(ResolvedWorldArtifacts::class.java)
        `when`(worlds.findProcessWorld()).thenReturn(world)
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, emptyList(), bundle))
        `when`(geography.places(bundle)).thenReturn(mapOf(
            5 to CityGeography.Place("synthetic-qa", "200197"),
            6 to CityGeography.Place("synthetic-qa", "200197")))
        `when`(generals.findById(1)).thenReturn(Optional.of(GeneralReadEntity(id = 1, worldId = 1,
            nationId = 1, cityId = 6, userId = "41")))
        `when`(generals.findById(7)).thenReturn(Optional.of(GeneralReadEntity(id = 7, worldId = 1,
            nationId = 0, cityId = 5, userId = "41")))
        `when`(generals.findById(9)).thenReturn(Optional.of(GeneralReadEntity(id = 9, worldId = 1,
            nationId = 2, cityId = 6, userId = "42")))
        `when`(generals.findById(99)).thenReturn(Optional.empty())
        `when`(cities.findById(5)).thenReturn(Optional.of(city(5, 1)))
        `when`(cities.findById(6)).thenReturn(Optional.of(city(6, 2)))
        reader.production = mapOf(5 to Resources(iron = 1000, timber = 132),
            6 to Resources(iron = 1000, timber = 132))
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun city(id: Int, nation: Int) = CityReadEntity(id = id, worldId = 1, name = "synthetic-qa-$id",
        nationId = nation, supplyState = 1, population = 5000,
        meta = mapOf(CountyWarehouse.META_KEY to CountyWarehouse(id, 3, Resources()).toMetaValue()))

    @Test fun `same signed viewer keeps own county monthly and cannot enlarge it using query identity`() {
        mvc.perform(get("/api/county/5").param("generalId", "1").param("viewerGeneralId", "9")
            .param("userId", "42").param("nationId", "2").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.specialties[0].ledgerMonthly").value(1000))
            .andExpect(jsonPath("$.specialties[0].monthly").value(1000))
        verify(generals).findById(1)
        verify(generals, never()).findById(9)
    }

    @Test fun `same viewer foreign county has only design values even with visibility and identity hints`() {
        mvc.perform(get("/api/county/6").param("generalId", "1").param("visibility", "FULL")
            .param("viewerGeneralId", "9").param("nationId", "2").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.specialties[0].ledgerMonthly").value(1000))
            .andExpect(jsonPath("$.specialties[0].monthly").doesNotExist())
            .andExpect(jsonPath("$.specialties[1].monthly").doesNotExist())
        verify(generals, never()).findById(9)
    }

    @Test fun `ronin receives design only in both held and unowned counties`() {
        for (nation in listOf(1, 0)) {
            `when`(cities.findById(5)).thenReturn(Optional.of(city(5, nation)))
            mvc.perform(get("/api/county/5").param("generalId", "7").header("Authorization", "Bearer ${token()}"))
                .andExpect(status().isOk).andExpect(jsonPath("$.specialties[0].ledgerMonthly").value(1000))
                .andExpect(jsonPath("$.specialties[0].monthly").doesNotExist())
        }
    }

    @Test fun `unowned county is not own territory for a member viewer`() {
        `when`(cities.findById(6)).thenReturn(Optional.of(city(6, 0)))
        mvc.perform(get("/api/county/6").param("generalId", "1").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.specialties[0].ledgerMonthly").value(1000))
            .andExpect(jsonPath("$.specialties[0].monthly").doesNotExist())
    }

    @Test fun `anonymous invalid expired and refresh JWT deny before any reader data access`() {
        mvc.perform(get("/api/county/5").param("generalId", "1"))
            .andExpect(status().isUnauthorized).andExpect(content().string(""))
        for (jwt in listOf("invalid", token(expired = true), token(type = GatewayJwtClaims.REFRESH_TOKEN))) {
            mvc.perform(get("/api/county/5").param("generalId", "1").header("Authorization", "Bearer $jwt"))
                .andExpect(status().isUnauthorized).andExpect(content().string(""))
        }
        verifyNoInteractions(generals, cities, worlds, artifacts, geography)
    }

    @Test fun `another or missing general stays forbidden even with signed administrator role`() {
        for (role in listOf("USER", "ADMIN")) {
            for (general in listOf(9, 99)) {
                mvc.perform(get("/api/county/6").param("generalId", general.toString())
                    .header("Authorization", "Bearer ${token(role = role)}"))
                    .andExpect(status().isForbidden).andExpect(content().string(""))
            }
        }
        verifyNoInteractions(cities, worlds, artifacts, geography)
    }

    private fun token(role: String = "USER", expired: Boolean = false,
                      type: String = GatewayJwtClaims.ACCESS_TOKEN): String {
        val now = Date()
        return Jwts.builder().subject("41").issuedAt(Date(now.time - 120000))
            .expiration(Date(now.time + if (expired) -60000 else 60000))
            .claim(GatewayJwtClaims.TOKEN_TYPE, type)
            .claim(GatewayJwtClaims.ROLE, role)
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact()
    }

    companion object { const val SECRET = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU=" }
}

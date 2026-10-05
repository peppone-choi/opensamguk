package opensamguk.gameapi.court.imperial

import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.common.auth.GatewayJwtContract
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.*
import opensamguk.gameapi.security.*
import opensamguk.logic.imperial.*
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
import java.util.Base64
import java.util.Date
import java.util.Optional
import kotlin.test.assertEquals

@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [ImperialCourtHttpTest.Config::class, GameApiSecurityConfig::class])
class ImperialCourtHttpTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    open class Config {
        @Bean open fun admissionPolicy() = ServerAdmissionTestFixture.publicPolicy()
        @Bean open fun verifier() = GameApiJwtVerifier(Base64.getEncoder().encodeToString(KEYS.public.encoded), "", "")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun resolver() = mock(GeneralResolver::class.java)
        @Bean open fun worlds() = mock(WorldStateReadRepository::class.java)
        @Bean open fun generals() = mock(GeneralReadRepository::class.java)
        @Bean open fun nations() = mock(NationReadRepository::class.java)
        @Bean open fun cities() = mock(CityReadRepository::class.java)
        @Bean open fun artifacts() = mock(ActiveWorldArtifactResolver::class.java)
        @Bean open fun reader(w: WorldStateReadRepository, g: GeneralReadRepository, n: NationReadRepository,
            c: CityReadRepository, a: ActiveWorldArtifactResolver) = ImperialCourtReader(w, g, n, c, a)
        @Bean open fun query(r: GeneralResolver, reader: ImperialCourtReader) =
            ImperialCourtQuery(r, reader, GameApiProcessWorld(1))
        @Bean open fun controller(query: ImperialCourtQuery) = ImperialCourtController(query)
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var resolver: GeneralResolver
    @Autowired lateinit var worlds: WorldStateReadRepository
    @Autowired lateinit var generals: GeneralReadRepository
    @Autowired lateinit var nations: NationReadRepository
    @Autowired lateinit var cities: CityReadRepository
    @Autowired lateinit var artifacts: ActiveWorldArtifactResolver
    private lateinit var mvc: MockMvc
    private lateinit var actor: GeneralReadEntity
    private val mapper = ObjectMapper()

    @BeforeEach fun setup() {
        reset(resolver, worlds, generals, nations, cities, artifacts)
        actor = GeneralReadEntity(id = 10, worldId = 1, userId = "41", nationId = 7)
        `when`(resolver.resolve(41)).thenReturn(GeneralResolver.ResolvedGeneral(actor, 0, 0, 7, 1))
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1))
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test fun `signed observers of every faction receive the same approved active details`() {
        val house = active().copy(regentGeneralId = 1001, courtNationId = 5)
        seed(listOf(house))
        `when`(generals.findById(1009)).thenReturn(Optional.of(GeneralReadEntity(id = 1009, worldId = 1, name = "황제")))
        `when`(generals.findById(1001)).thenReturn(Optional.of(GeneralReadEntity(id = 1001, worldId = 1, name = "섭정")))
        `when`(nations.findById(5)).thenReturn(Optional.of(NationReadEntity(id = 5, worldId = 1, name = "보호 세력")))
        for (nation in listOf(5, 7, 0)) {
            actor.nationId = nation
            val response = mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
                .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.lines[0].regentGeneralId").value(1001))
                .andExpect(jsonPath("$.lines[0].regentName").value("섭정"))
                .andExpect(jsonPath("$.lines[0].courtNationId").value(5))
                .andExpect(jsonPath("$.lines[0].courtNationName").value("보호 세력"))
                .andExpect(jsonPath("$.lines[0].fieldStates.regent").value("READY"))
                .andReturn().response
            val root = mapper.readTree(response.contentAsByteArray)
            assertEquals(setOf("status", "lines"), root.fieldNames().asSequence().toSet())
            assertEquals(true, root["lines"][0].has("courtCityId"))
            assertEquals(true, root["lines"][0]["courtCityId"].isNull)
            assertEquals(false, response.contentAsString.contains("legitimacy"))
            assertEquals(false, response.contentAsString.contains("emperorNode"))
        }
        verifyNoInteractions(cities, artifacts)
    }

    @Test fun `vacant and ended HTTP lines keep explicit null keys and per line not applicable states`() {
        val vacant = active().copy(code = "vacant_line", status = ImperialLineStatus.VACANT,
            holderGeneralId = null, regentGeneralId = 201, courtNationId = 8, courtCityId = 12)
        seed(listOf(vacant, vacant.copy(code = "ended_line", status = ImperialLineStatus.ENDED)))
        val response = mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.lines[0].status").value("ENDED"))
            .andExpect(jsonPath("$.lines[1].status").value("VACANT")).andReturn().response
        for (line in mapper.readTree(response.contentAsByteArray)["lines"]) {
            for (key in listOf("holderGeneralId", "emperorName", "courtCityId", "courtCityName",
                    "regentGeneralId", "regentName", "courtNationId", "courtNationName")) {
                assertEquals(true, line.has(key), key)
                assertEquals(true, line[key].isNull, key)
            }
            assertEquals(setOf("NOT_APPLICABLE"), line["fieldStates"].map { it.asText() }.toSet())
        }
        verifyNoInteractions(generals, nations, cities, artifacts)
    }

    @Test fun `anonymous forged query identity and invalid JWTs cannot read any court source`() {
        for (bearer in listOf(null, "invalid", token(type = GatewayJwtClaims.REFRESH_TOKEN),
                token(lifetime = -60000), token(audience = GatewayJwtContract.BOARD_API_AUDIENCE))) {
            val request = get(PATH).param("generalId", "10").param("userId", "41")
            if (bearer != null) request.header("Authorization", "Bearer $bearer")
            mvc.perform(request).andExpect(status().isUnauthorized)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"))
        }
        verifyNoInteractions(resolver, worlds, generals, nations, cities, artifacts)
    }

    @Test fun `other general nonowned account ADMIN and wrong world never bypass actor authorization`() {
        for ((id, bearer) in listOf("20" to token(), "10" to token(user = "42"), "20" to token(role = "ADMIN"),
                "0" to token(), "-1" to token())) {
            mvc.perform(get(PATH).param("generalId", id).header("Authorization", "Bearer $bearer"))
                .andExpect(status().isForbidden).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
        }
        actor.worldId = 2
        mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isForbidden)
        verifyNoInteractions(worlds, generals, nations, cities, artifacts)
    }

    @Test fun `unseeded valid empty and malformed HTTP responses stay distinguishable and uncached`() {
        mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.status").value("NOT_SEEDED")).andExpect(jsonPath("$.lines").isEmpty)
        seed(emptyList())
        mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.lines").isEmpty)
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1, meta = mapOf("imperialWorld" to null)))
        mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isConflict).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.status").value("STATE_UNAVAILABLE")).andExpect(jsonPath("$.lines").isEmpty)
    }

    @Test fun `active dangling public reference returns conflict without partial IDs or fallback names`() {
        seed(listOf(active()))
        `when`(generals.findById(1009)).thenReturn(Optional.of(GeneralReadEntity(id = 1009, worldId = 2, name = "다른 세계")))
        val response = mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isConflict).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.status").value("STATE_UNAVAILABLE")).andReturn().response
        assertEquals(false, response.contentAsString.contains("다른 세계"))
        assertEquals(emptyList(), mapper.readTree(response.contentAsByteArray)["lines"].toList())
    }

    private fun active() = ImperialHouse("active_line", "현재 황통", ImperialLineStatus.ACTIVE,
        1009, null, emptyList(), null, null, null, 70)

    private fun seed(houses: List<ImperialHouse>) {
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1,
            meta = mapOf(ImperialWorldCodec.META_KEY to ImperialWorldCodec.write(
                ImperialWorldState(houses, emptyList(), emptyList())))))
    }

    private fun token(user: String = "41", role: String = "USER", type: String = GatewayJwtClaims.ACCESS_TOKEN,
        lifetime: Long = 60000, audience: String = GatewayJwtContract.GAME_API_AUDIENCE): String {
        val now = Date()
        return Jwts.builder().subject(user).issuer(GatewayJwtContract.ISSUER).audience().add(audience).and()
            .issuedAt(now).expiration(Date(now.time + lifetime)).claim(GatewayJwtClaims.TOKEN_TYPE, type)
            .claim(GatewayJwtClaims.ROLE, role).signWith(KEYS.private).compact()
    }

    companion object {
        val KEYS = Jwts.SIG.RS256.keyPair().build()
        const val PATH = "/api/imperial/court"
    }
}

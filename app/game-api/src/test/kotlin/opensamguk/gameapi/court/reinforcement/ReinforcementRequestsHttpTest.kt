package opensamguk.gameapi.court.reinforcement

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.common.auth.GatewayJwtContract
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.*
import opensamguk.gameapi.security.*
import opensamguk.logic.input.Phase
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [ReinforcementRequestsHttpTest.Config::class, GameApiSecurityConfig::class])
class ReinforcementRequestsHttpTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    open class Config {
        @Bean open fun admissionPolicy() = ServerAdmissionTestFixture.publicPolicy()
        @Bean open fun verifier() = GameApiJwtVerifier(Base64.getEncoder().encodeToString(KEYS.public.encoded), "", "")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun resolver() = mock(GeneralResolver::class.java)
        @Bean open fun worlds() = mock(WorldStateReadRepository::class.java)
        @Bean open fun reader(w: WorldStateReadRepository) = ReinforcementRequestsReader(w)
        @Bean open fun query(r: GeneralResolver, reader: ReinforcementRequestsReader) = ReinforcementRequestsQuery(r, reader)
        @Bean open fun controller(query: ReinforcementRequestsQuery) = ReinforcementRequestsController(query)
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var resolver: GeneralResolver
    @Autowired lateinit var worlds: WorldStateReadRepository
    private lateinit var mvc: MockMvc
    private val mapper = ObjectMapper()

    @BeforeEach fun setup() {
        reset(resolver, worlds)
        val actor = GeneralReadEntity(id = 10, worldId = 1, userId = "41", nationId = 7)
        `when`(resolver.resolve(41)).thenReturn(GeneralResolver.ResolvedGeneral(actor, 0, 0, 7, 1))
        world(200, 3, 2)
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @ParameterizedTest @MethodSource("fixtureNames")
    fun `fixtures travel through actual signed JWT query reader and HTTP`(name: String) {
        val expected = fixture(name)
        if (name == "unavailable") `when`(worlds.findProcessWorld()).thenReturn(null)
        val result = mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
        assertEquals(expected, mapper.readTree(result.response.contentAsByteArray))
        verify(resolver).resolve(41)
    }

    @Test fun `without a request source every readable date stays NOT_SEEDED with a null list, never READY empty`() {
        for (month in 1..12) for (phase in 1..3) {
            world(189, month, phase)
            mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
                .andExpect(status().isOk).andExpect(jsonPath("$.status").value("NOT_SEEDED"))
                .andExpect(jsonPath("$.reason").value(ReinforcementRequestsReason.REQUEST_SOURCE_ABSENT))
                .andExpect(jsonPath("$.now.month").value(month)).andExpect(jsonPath("$.now.phase").value(phase))
                .andExpect(jsonPath("$.requests").value(nullValue()))
        }
    }

    @Test fun `identity comes only from a valid access token and is checked before the general id`() {
        val tokens = listOf(null, "invalid", token(type = GatewayJwtClaims.REFRESH_TOKEN),
            token(lifetime = -60000), token(audience = GatewayJwtContract.BOARD_API_AUDIENCE))
        for (bearer in tokens) for (id in listOf("10", "abc", null)) {
            val request = get(PATH).param("userId", "41")
            if (id != null) request.param("generalId", id)
            if (bearer != null) request.header("Authorization", "Bearer $bearer")
            mvc.perform(request).andExpect(status().isUnauthorized)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.error.message").value("로그인이 필요합니다."))
        }
        verifyNoInteractions(resolver, worlds)
    }

    @Test fun `malformed general ids are rejected before any ownership or world read`() {
        val expected = fixture("boundary")
        for (id in listOf(null, "", " 10", "abc", "1.0", "+10", "-1", "0", "00", "2147483648", "99999999999999999999")) {
            val request = get(PATH).header("Authorization", "Bearer ${token()}")
            if (id != null) request.param("generalId", id)
            val result = mvc.perform(request).andExpect(status().isBadRequest)
                .andExpect(header().string("Cache-Control", "no-store")).andReturn()
            assertEquals(expected, mapper.readTree(result.response.contentAsByteArray))
        }
        verifyNoInteractions(resolver, worlds)
    }

    @Test fun `verified account cannot select another live actor and ADMIN cannot override ownership`() {
        val expected = fixture("blocked")
        for ((actor, bearer) in listOf("20" to token(), "10" to token(user = "42"), "20" to token(role = "ADMIN"))) {
            val result = mvc.perform(get(PATH).param("generalId", actor).header("Authorization", "Bearer $bearer"))
                .andExpect(status().isForbidden).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn()
            assertEquals(expected, mapper.readTree(result.response.contentAsByteArray))
        }
        verifyNoInteractions(worlds)
    }

    @Test fun `another process world or an impossible date is unavailable with no time and no list`() {
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 2, currentYear = 200, currentMonth = 3,
            currentPhase = 2))
        expectUnavailable(ReinforcementRequestsReason.WORLD_UNAVAILABLE)
        for ((year, month, phase) in listOf(Triple(0, 3, 1), Triple(200, 13, 1), Triple(200, 0, 1),
            Triple(200, 3, 0), Triple(200, 3, 4))) {
            world(year, month, phase)
            expectUnavailable(ReinforcementRequestsReason.WORLD_DATE_INVALID)
        }
    }

    @Test fun `projection cannot turn a source-less snapshot into READY`() {
        assertFailsWith<IllegalArgumentException> {
            ReinforcementRequestsProjection.project(ReinforcementRequestsSnapshot(ReinforcementRequestsStatus.READY,
                ReinforcementRequestsReason.REQUEST_SOURCE_ABSENT, Phase(200, 3, 2)))
        }
    }

    private fun expectUnavailable(reason: String) {
        mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.status").value("UNAVAILABLE")).andExpect(jsonPath("$.reason").value(reason))
            .andExpect(jsonPath("$.now").value(nullValue())).andExpect(jsonPath("$.requests").value(nullValue()))
    }

    private fun world(year: Int, month: Int, phase: Int) {
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1, currentYear = year,
            currentMonth = month, currentPhase = phase))
    }

    private fun fixture(name: String): JsonNode = mapper.readTree(
        requireNotNull(javaClass.getResourceAsStream("/court/reinforcement-requests/$name.json")))

    private fun token(user: String = "41", role: String = "USER", type: String = GatewayJwtClaims.ACCESS_TOKEN,
        lifetime: Long = 60000, audience: String = GatewayJwtContract.GAME_API_AUDIENCE): String {
        val now = Date()
        return Jwts.builder().subject(user).issuer(GatewayJwtContract.ISSUER).audience().add(audience).and()
            .issuedAt(now).expiration(Date(now.time + lifetime)).claim(GatewayJwtClaims.TOKEN_TYPE, type)
            .claim(GatewayJwtClaims.ROLE, role).signWith(KEYS.private).compact()
    }

    companion object {
        val KEYS = Jwts.SIG.RS256.keyPair().build()
        const val PATH = "/api/court/reinforcement-requests"
        @JvmStatic fun fixtureNames() = listOf("not-seeded", "unavailable")
    }
}

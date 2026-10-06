package opensamguk.gameapi.frontier

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.common.auth.GatewayJwtContract
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.security.*
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

/** K8-09 frontier read — real JWT/security/controller/query/reader path, world store and resolver are doubles. */
@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [FrontierHttpTest.Config::class, GameApiSecurityConfig::class])
class FrontierHttpTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    open class Config {
        @Bean open fun admissionPolicy() = ServerAdmissionTestFixture.publicPolicy()
        @Bean open fun verifier() = GameApiJwtVerifier(Base64.getEncoder().encodeToString(KEYS.public.encoded), "", "")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun resolver() = mock(GeneralResolver::class.java)
        @Bean open fun worlds() = mock(WorldStateReadRepository::class.java)
        @Bean open fun reader(w: WorldStateReadRepository) = FrontierReader(w)
        @Bean open fun query(r: GeneralResolver, reader: FrontierReader) = FrontierQuery(r, reader)
        @Bean open fun controller(query: FrontierQuery) = FrontierController(query)
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var resolver: GeneralResolver
    @Autowired lateinit var worlds: WorldStateReadRepository
    private lateinit var mvc: MockMvc
    private val mapper = ObjectMapper()

    @BeforeEach fun setup() {
        reset(resolver, worlds)
        actor()
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1, currentYear = 201, currentMonth = 4, currentPhase = 3))
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @ParameterizedTest @MethodSource("fixtures")
    fun `each source state answers its exact fixture with no-store`(name: String) {
        when (name) {
            "not-seeded" -> Unit
            "unavailable" -> `when`(worlds.findProcessWorld()).thenReturn(null)
            "boundary" -> actor(nation = 0)
        }
        val result = request().andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store")).andReturn()
        assertEquals(fixture(name), mapper.readTree(result.response.contentAsByteArray))
        verify(resolver).resolve(41)
    }

    @Test fun `a nation member is never told a verified empty inland answer while no contact source exists`() {
        request().andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("NOT_SEEDED"))
            .andExpect(jsonPath("$.reason").value("CONTACTS_NOT_SEEDED"))
            .andExpect(jsonPath("$.actors").value(null as Any?))
    }

    @Test fun `anonymous and invalid refresh expired or foreign audience tokens get 401 before any read`() {
        val tokens = listOf(null, "invalid", token(type = GatewayJwtClaims.REFRESH_TOKEN),
            token(lifetime = -60000), token(audience = GatewayJwtContract.BOARD_API_AUDIENCE))
        for (bearer in tokens) {
            for (actorId in listOf("10", "bad", null)) {
                val req = get(PATH)
                if (actorId != null) req.param("generalId", actorId)
                if (bearer != null) req.header("Authorization", "Bearer $bearer")
                mvc.perform(req).andExpect(status().isUnauthorized)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"))
                    .andExpect(jsonPath("$.error.message").value("로그인이 필요합니다."))
            }
        }
        verifyNoInteractions(resolver, worlds)
    }

    @Test fun `missing empty non-decimal out-of-range or non-positive actor id is a fixed 400`() {
        for (actorId in listOf(null, "", "bad", "+10", " 10", "1e3", "2147483648", "0", "-1")) {
            val req = get(PATH).header("Authorization", "Bearer ${token()}")
            if (actorId != null) req.param("generalId", actorId)
            mvc.perform(req).andExpect(status().isBadRequest)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("INVALID_GENERAL_ID"))
                .andExpect(jsonPath("$.error.message").value("장수 번호가 올바르지 않습니다."))
        }
        verifyNoInteractions(resolver, worlds)
    }

    @Test fun `verified caller must select their own live actor even with ADMIN or a query userId`() {
        for ((actorId, bearer) in listOf("20" to token(), "10" to token(user = "42"), "20" to token(role = "ADMIN"))) {
            mvc.perform(get(PATH).param("generalId", actorId).param("userId", "41")
                .header("Authorization", "Bearer $bearer"))
                .andExpect(status().isForbidden).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("본인 장수로만 조회할 수 있습니다."))
        }
        actor(user = "99")
        request().andExpect(status().isForbidden)
        verifyNoInteractions(worlds)
    }

    @Test fun `foreign process world and invalid clocks are unavailable without a clock`() {
        for (world in listOf(WorldStateReadEntity(id = 2, currentYear = 201, currentMonth = 4, currentPhase = 3),
            WorldStateReadEntity(id = 1, currentYear = 0, currentMonth = 4, currentPhase = 3),
            WorldStateReadEntity(id = 1, currentYear = 201, currentMonth = 13, currentPhase = 3))) {
            `when`(worlds.findProcessWorld()).thenReturn(world)
            request().andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.reason").value("WORLD_UNAVAILABLE"))
                .andExpect(jsonPath("$.now").value(null as Any?))
                .andExpect(jsonPath("$.actors").value(null as Any?))
        }
    }

    private fun request() = mvc.perform(get(PATH).param("generalId", "10")
        .header("Authorization", "Bearer ${token()}"))

    private fun actor(nation: Int = 7, user: String = "41") {
        val row = GeneralReadEntity(id = 10, worldId = 1, userId = user, nationId = nation)
        `when`(resolver.resolve(41)).thenReturn(GeneralResolver.ResolvedGeneral(row, 0, 0, nation, 1))
    }

    private fun fixture(name: String): JsonNode = mapper.readTree(
        requireNotNull(javaClass.getResourceAsStream("/frontier/$name.json")))

    private fun token(user: String = "41", role: String = "USER", type: String = GatewayJwtClaims.ACCESS_TOKEN,
        lifetime: Long = 60000, audience: String = GatewayJwtContract.GAME_API_AUDIENCE): String {
        val now = Date()
        return Jwts.builder().subject(user).issuer(GatewayJwtContract.ISSUER).audience().add(audience).and()
            .issuedAt(now).expiration(Date(now.time + lifetime)).claim(GatewayJwtClaims.TOKEN_TYPE, type)
            .claim(GatewayJwtClaims.ROLE, role).signWith(KEYS.private).compact()
    }

    companion object {
        val KEYS = Jwts.SIG.RS256.keyPair().build()
        const val PATH = "/api/frontier"
        @JvmStatic fun fixtures() = listOf("not-seeded", "unavailable", "boundary")
    }
}

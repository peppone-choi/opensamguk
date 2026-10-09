package opensamguk.gameapi.court.reward

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import java.util.Date
import java.util.Optional
import kotlin.test.*
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.gameapi.owner.GeneralOwnershipSnapshot
import opensamguk.gameapi.security.*
import org.junit.jupiter.api.BeforeEach
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

@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [RewardOptionsSecurityTest.Config::class, GameApiSecurityConfig::class])
class RewardOptionsSecurityTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    open class Config {
        @Bean open fun admissionPolicy() = ServerAdmissionTestFixture.publicPolicy()
        @Bean open fun verifier() = GameApiJwtVerifier("", SECRET, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun fixture() = RewardReadFixture()
        @Bean open fun reader(f: RewardReadFixture) = f.reader
        @Bean open fun query(reader: RewardOptionsReader) = RewardOptionsQuery(reader)
        @Bean open fun controller(query: RewardOptionsQuery) = RewardOptionsController(query)
        @Bean open fun mapper() = ObjectMapper().findAndRegisterModules().setSerializationInclusion(JsonInclude.Include.NON_NULL)
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired internal lateinit var fixture: RewardReadFixture
    private lateinit var mvc: MockMvc
    private val mapper = ObjectMapper()
    private val path = "/api/court/reward-options"

    @BeforeEach fun setup() {
        // Each test uses fresh fixture stubs while Spring keeps the reader and its data sources stable.
        fixture.actor.userId = "42"
        fixture.world.currentPhase = 2
        `when`(fixture.ownership.findPlayableByUserId("42")).thenReturn(GeneralOwnershipSnapshot(10, 7, "42", 0))
        `when`(fixture.worlds.findById(7)).thenReturn(Optional.of(fixture.world))
        `when`(fixture.generals.findAll()).thenReturn(fixture.people)
        `when`(fixture.retainers.findAll()).thenReturn(fixture.cards)
        clearInvocations(fixture.ownership, fixture.generals, fixture.worlds, fixture.retainers,
            fixture.cities, fixture.nations, fixture.artifacts)
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test fun `anonymous invalid expired and refresh JWTs return AUTH_REQUIRED with no-store before reads`() {
        for (jwt in listOf(null, "invalid", token(expired = true), token(type = GatewayJwtClaims.REFRESH_TOKEN))) {
            val request = get(path).param("generalId", "10")
            jwt?.let { request.header("Authorization", "Bearer $it") }
            mvc.perform(request).andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"))
                .andExpect(header().string("Cache-Control", "no-store"))
        }
        verifyNoInteractions(fixture.ownership, fixture.generals, fixture.worlds, fixture.retainers, fixture.artifacts)
    }

    @Test fun `positive Int IDs and preview target validation return explicit 400 with no-store`() {
        for (id in listOf(null, "0", "-1", "+10", "10.0", "2147483648", "abc")) {
            val request = get(path).header("Authorization", "Bearer ${token()}")
            id?.let { request.param("generalId", it) }
            mvc.perform(request).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("INVALID_GENERAL_ID"))
                .andExpect(header().string("Cache-Control", "no-store"))
        }
        for (id in listOf("0", "-1", "1.5", "2147483648")) mvc.perform(get(path).param("generalId", "10")
            .param("retainerId", id).header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.error.code").value("INVALID_RETAINER_ID"))
            .andExpect(header().string("Cache-Control", "no-store"))
        mvc.perform(get(path).param("generalId", "10").param("money", "100")
            .header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.error.code").value("PREVIEW_TARGET_REQUIRED"))
            .andExpect(header().string("Cache-Control", "no-store"))
        verifyNoInteractions(fixture.ownership, fixture.generals)
    }

    @Test fun `foreign borrowed and wrong process bodies remain forbidden for signed users and admins`() {
        for (role in listOf("USER", "ADMIN")) mvc.perform(get(path).param("generalId", "20")
            .header("Authorization", "Bearer ${token(role = role)}"))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
            .andExpect(header().string("Cache-Control", "no-store"))
        fixture.actor.userId = "43"
        mvc.perform(get(path).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isForbidden)
        fixture.actor.userId = "42"
        `when`(fixture.ownership.findPlayableByUserId("42")).thenReturn(GeneralOwnershipSnapshot(10, 8, "42", 0))
        mvc.perform(get(path).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isForbidden)
        verifyNoInteractions(fixture.worlds, fixture.retainers, fixture.artifacts)
    }

    @Test fun `direct cards use card IDs and foreign indirect nonperson or missing cards are indistinguishable`() {
        val responses = listOf(5, 6, 7, 20, 999).map { id ->
            val response = mvc.perform(get(path).param("generalId", "10").param("retainerId", id.toString())
                .param("money", "100").param("userId", "43").header("Authorization", "Bearer ${token()}"))
                .andExpect(status().isOk).andExpect(jsonPath("$.preview.verdict").value("CARD_UNAVAILABLE"))
                .andExpect(jsonPath("$.cards.length()").value(1)).andExpect(jsonPath("$.cards[0].retainerId").value(4))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().response.contentAsString
            mapper.readTree(response).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
                (get("preview") as com.fasterxml.jackson.databind.node.ObjectNode).put("retainerId", 0)
            }
        }
        assertTrue(responses.all { it == responses.first() })
        verify(fixture.ownership, never()).findPlayableByUserId("43")
    }

    @Test fun `all nullable root and preview keys are explicit even under global NON_NULL serialization`() {
        `when`(fixture.worlds.findById(7)).thenReturn(Optional.empty())
        val response = mvc.perform(get(path).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().response
        val node = mapper.readTree(response.contentAsString)
        assertEquals(setOf("status", "reason", "generalId", "snapshot", "rule", "queued", "cards", "preview"),
            node.fieldNames().asSequence().toSet())
        for (field in listOf("snapshot", "rule", "cards", "preview")) assertTrue(node[field].isNull)
        assertEquals(setOf("status", "retainerId", "money"), node["queued"].fieldNames().asSequence().toSet())
        assertTrue(node["queued"]["money"].isNull)
    }

    @Test fun `malformed money is a preview verdict and roster contamination leaks no IDs or names`() {
        mvc.perform(get(path).param("generalId", "10").param("retainerId", "4").param("money", "100.0")
            .header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.preview.verdict").value("INVALID_AMOUNT"))
        `when`(fixture.generals.findAll()).thenReturn(fixture.people + fixture.person(555).apply {
            worldId = 8; name = "PRIVATE_FOREIGN_NAME"
        })
        val response = mvc.perform(get(path).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.reason").value("ROSTER_INVALID"))
            .andReturn().response.contentAsString
        assertFalse(response.contains("PRIVATE_FOREIGN_NAME"))
        assertFalse(response.contains("555"))
    }

    private fun token(role: String = "USER", expired: Boolean = false, type: String = GatewayJwtClaims.ACCESS_TOKEN): String {
        val now = Date()
        return Jwts.builder().subject("42").issuedAt(Date(now.time - 120000))
            .expiration(Date(now.time + if (expired) -60000 else 60000))
            .claim(GatewayJwtClaims.TOKEN_TYPE, type).claim(GatewayJwtClaims.ROLE, role)
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact()
    }

    companion object { const val SECRET = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU=" }
}

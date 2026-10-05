package opensamguk.gameapi.court.vassal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.jsonwebtoken.Jwts
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.common.auth.GatewayJwtContract
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.*
import opensamguk.gameapi.security.*
import opensamguk.infra.entity.GameKvEntity
import opensamguk.logic.economy.Resources
import opensamguk.logic.vassal.*
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
import java.util.Optional
import kotlin.test.assertEquals

@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [VassalHttpTest.Config::class, GameApiSecurityConfig::class])
class VassalHttpTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    open class Config {
        @Bean open fun admissionPolicy() = ServerAdmissionTestFixture.publicPolicy()
        @Bean open fun verifier() = GameApiJwtVerifier(Base64.getEncoder().encodeToString(KEYS.public.encoded), "", "")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun resolver() = mock(GeneralResolver::class.java)
        @Bean open fun worlds() = mock(WorldStateReadRepository::class.java)
        @Bean open fun kv() = mock(GameKvReadRepository::class.java)
        @Bean open fun generals() = mock(GeneralReadRepository::class.java)
        @Bean open fun reader(w: WorldStateReadRepository, k: GameKvReadRepository, g: GeneralReadRepository) =
            VassalStoredTermsReader(w, k, g)
        @Bean open fun stored(r: GeneralResolver, reader: VassalStoredTermsReader) = VassalStoredTermsQuery(r, reader)
        @Bean open fun query(stored: VassalStoredTermsQuery) = VassalHttpQuery(stored)
        @Bean open fun controller(query: VassalHttpQuery) = VassalController(query)
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var resolver: GeneralResolver
    @Autowired lateinit var worlds: WorldStateReadRepository
    @Autowired lateinit var kv: GameKvReadRepository
    @Autowired lateinit var generals: GeneralReadRepository
    private lateinit var mvc: MockMvc
    private val mapper = jacksonObjectMapper()

    @BeforeEach fun setup() {
        reset(resolver, worlds, kv, generals)
        val actor = GeneralReadEntity(id = 10, worldId = 1, userId = "41", nationId = 7)
        `when`(resolver.resolve(41)).thenReturn(GeneralResolver.ResolvedGeneral(actor, 0, 0, 7, 1))
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(
            id = 1, currentYear = 200, currentMonth = 1, currentPhase = 2))
        `when`(generals.findById(20)).thenReturn(Optional.empty())
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @ParameterizedTest @MethodSource("fixtureNames")
    fun `stored source fixtures travel through actual signed JWT query reader and HTTP`(name: String) {
        val expected = mapper.readTree(requireNotNull(javaClass.getResourceAsStream("/court/vassal/$name.json")))
        install(name, expected)
        val result = mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
        assertEquals(expected, mapper.readTree(result.response.contentAsByteArray))
        verify(resolver).resolve(41)
    }

    @Test fun `query identity and invalid refresh expired or foreign audience tokens never authenticate`() {
        val tokens = listOf(null, "invalid", token(type = GatewayJwtClaims.REFRESH_TOKEN),
            token(lifetime = -60000), token(audience = GatewayJwtContract.BOARD_API_AUDIENCE))
        for (bearer in tokens) {
            val request = get(PATH).param("generalId", "10").param("userId", "41")
            if (bearer != null) request.header("Authorization", "Bearer $bearer")
            mvc.perform(request).andExpect(status().isUnauthorized)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.error.message").value("로그인이 필요합니다."))
        }
        verifyNoInteractions(resolver, worlds, kv, generals)
    }

    @Test fun `verified account cannot select another live actor and ADMIN cannot override ownership`() {
        for ((actor, bearer) in listOf("20" to token(), "10" to token(user = "42"),
            "20" to token(role = "ADMIN"), "0" to token(), "-1" to token())) {
            mvc.perform(get(PATH).param("generalId", actor).header("Authorization", "Bearer $bearer"))
                .andExpect(status().isForbidden).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
        }
        verifyNoInteractions(worlds, kv, generals)
    }

    @Test fun `out of scope world or nation data is not exposed by signed requests`() {
        val expected = fixture("stored-terms-partial-paid")
        install("stored-terms-partial-paid", expected, sourceWorld = 2)
        mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.contractsStatus").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.contracts").isEmpty)
        install("stored-terms-partial-paid", expected, sourceNation = 8)
        mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.contractsStatus").value("READY"))
            .andExpect(jsonPath("$.contracts").isEmpty)
        verifyNoInteractions(generals)
    }

    @Test fun `invalid duplicate unconserved negative or overflowing receipts do not become paid or empty ready`() {
        val expected = fixture("stored-terms-partial-paid")
        install("stored-terms-partial-paid", expected)
        val valid = VassalStateCodec.encode(state(expected))
        val root = mapper.readTree(valid)
        val receipt = root["receipts"][0]
        val invalid = listOf("{}", valid.replace("\"version\":1", "\"version\":9"),
            valid.replace("\"money\":30", "\"money\":-1"),
            valid.replace("\"paid\":{\"money\":30", "\"paid\":{\"money\":29"),
            valid.replace("\"receipts\":[", "\"receipts\":[$receipt,"),
            valid.replace("\"paid\":{\"money\":30", "\"paid\":{\"money\":9223372036854775807")
                .replace("\"unpaid\":{\"money\":0", "\"unpaid\":{\"money\":1"))
        for (raw in invalid) {
            persisted(raw)
            mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
                .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.contractsStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.contracts").isEmpty)
        }
    }

    private fun fixture(name: String): JsonNode = mapper.readTree(
        requireNotNull(javaClass.getResourceAsStream("/court/vassal/$name.json")))

    private fun install(name: String, body: JsonNode, sourceWorld: Int = 1, sourceNation: Int = 7) {
        if (name == "unavailable") {
            `when`(worlds.findProcessWorld()).thenReturn(null)
            return
        }
        if (name == "not-seeded") return
        persisted(VassalStateCodec.encode(state(body, sourceNation)), sourceWorld)
        if (body["contracts"].isEmpty) return
        val row = body["contracts"][0]
        val human = row["isHuman"]
        `when`(generals.findById(20)).thenReturn(Optional.of(GeneralReadEntity(id = 20, worldId = 1,
            nationId = 7, name = row["vassalName"].takeUnless { it.isNull }?.asText() ?: " ",
            userId = if (human.isNull || human.asBoolean()) "21" else null,
            npcState = if (human.isNull || !human.asBoolean()) 2 else 0)))
    }

    private fun persisted(raw: String, world: Int = 1) {
        `when`(kv.findByTableAndNamespaceAndKey("game_env", "game_env", VassalStateCodec.META_KEY))
            .thenReturn(GameKvEntity("game_env", "game_env", VassalStateCodec.META_KEY, raw, world))
    }

    private fun state(body: JsonNode, nation: Int = 7): VassalState {
        val contracts = body["contracts"].map { c -> VassalContract(c["contractId"].asText(),
            c["sovereignLordId"].asInt(), c["vassalLordId"].asInt(), nation,
            c["fiefCountyIds"].map { it.asInt() }.toSet(), c["tributePercent"].asInt(),
            c["reinforcementTroops"].asInt(), c["autonomy"].map { VassalAutonomy.valueOf(it.asText()) }.toSet(),
            VassalDiplomacyRight.valueOf(c["diplomacyRight"].asText()), emptySet(), c["loyalty"].asInt(),
            c["signedTurn"].asLong(), c["expiresTurn"].takeUnless { it.isNull }?.asLong(),
            c["endedTurn"].takeUnless { it.isNull }?.asLong()) }
        val receipts = body["contracts"].flatMap { c -> c["tributeHistory"].map { r ->
            VassalTributeReceipt(c["contractId"].asText(), r["year"].asInt(), r["month"].asInt(),
                resources(r["due"]), resources(r["paid"]), resources(r["unpaid"])) } }
        return VassalState(contracts, receipts)
    }

    private fun resources(row: JsonNode) = Resources(row["money"].asLong(), row["grain"].asLong(),
        row["iron"].asLong(), row["timber"].asLong(), row["horses"].asLong())

    private fun token(user: String = "41", role: String = "USER", type: String = GatewayJwtClaims.ACCESS_TOKEN,
        lifetime: Long = 60000, audience: String = GatewayJwtContract.GAME_API_AUDIENCE): String {
        val now = Date()
        return Jwts.builder().subject(user).issuer(GatewayJwtContract.ISSUER).audience().add(audience).and()
            .issuedAt(now).expiration(Date(now.time + lifetime)).claim(GatewayJwtClaims.TOKEN_TYPE, type)
            .claim(GatewayJwtClaims.ROLE, role).signWith(KEYS.private).compact()
    }

    companion object {
        val KEYS = Jwts.SIG.RS256.keyPair().build()
        const val PATH = "/api/court/vassals"
        @JvmStatic fun fixtureNames() = listOf("stored-terms-partial-paid", "human-known-name-unknown",
            "verified-unowned", "identity-unavailable", "ended-record-calendar-unavailable", "unpaid-one-resource",
            "zero-due-not-contract-no-obligation", "no-monthly-receipt", "valid-stored-empty", "not-seeded", "unavailable")
    }
}

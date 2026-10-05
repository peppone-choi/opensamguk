package opensamguk.gameapi.season

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.jsonwebtoken.Jwts
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.common.auth.GatewayJwtContract
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.*
import opensamguk.gameapi.security.*
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
import java.io.File
import java.util.Base64
import java.util.Date
import kotlin.test.assertEquals

@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [WorldSeasonHttpTest.Config::class, GameApiSecurityConfig::class])
class WorldSeasonHttpTest {
    /** The repository calendar is the source the build ships; tests read it directly so READY does not depend on packaging. */
    class TestCalendar : WorldSeasonCalendarSource {
        var value: String? = REPO_CALENDAR
        override fun payload(): String? = value
    }

    @Configuration @EnableWebMvc @EnableWebSecurity
    open class Config {
        @Bean open fun admissionPolicy() = ServerAdmissionTestFixture.publicPolicy()
        @Bean open fun verifier() = GameApiJwtVerifier(Base64.getEncoder().encodeToString(KEYS.public.encoded), "", "")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun resolver() = mock(GeneralResolver::class.java)
        @Bean open fun worlds() = mock(WorldStateReadRepository::class.java)
        @Bean open fun calendar() = TestCalendar()
        @Bean open fun reader(w: WorldStateReadRepository, c: TestCalendar) = WorldSeasonReader(w, c)
        @Bean open fun query(r: GeneralResolver, reader: WorldSeasonReader) = WorldSeasonQuery(r, reader)
        @Bean open fun controller(query: WorldSeasonQuery) = WorldSeasonController(query)
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var resolver: GeneralResolver
    @Autowired lateinit var worlds: WorldStateReadRepository
    @Autowired lateinit var calendar: TestCalendar
    private lateinit var mvc: MockMvc
    private val mapper = ObjectMapper()

    @BeforeEach fun setup() {
        reset(resolver, worlds)
        calendar.value = REPO_CALENDAR
        val actor = GeneralReadEntity(id = 10, worldId = 1, userId = "41", nationId = 7)
        `when`(resolver.resolve(41)).thenReturn(GeneralResolver.ResolvedGeneral(actor, 0, 0, 7, 1))
        world(200, 1, 2)
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @ParameterizedTest @MethodSource("fixtureNames")
    fun `fixtures travel through actual signed JWT query reader and HTTP`(name: String) {
        val expected = fixture(name)
        when (name) {
            "unavailable" -> `when`(worlds.findProcessWorld()).thenReturn(null)
            "not-seeded" -> calendar.value = null
        }
        expected["now"].takeUnless { it.isNull }?.let { world(it["year"].asInt(), it["month"].asInt(), it["phase"].asInt()) }
        val result = mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
        assertEquals(expected, mapper.readTree(result.response.contentAsByteArray))
        verify(resolver).resolve(41)
    }

    @Test fun `every phase of the year maps to 0 to 35 and the confirmed season boundaries`() {
        val seasons = listOf("WINTER", "WINTER", "SPRING", "SPRING", "SPRING", "SUMMER", "SUMMER", "SUMMER",
            "AUTUMN", "AUTUMN", "AUTUMN", "WINTER")
        for (month in 1..12) for (phase in 1..3) {
            world(189, month, phase)
            mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
                .andExpect(status().isOk).andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.phaseOfYear").value((month - 1) * 3 + (phase - 1)))
                .andExpect(jsonPath("$.season").value(seasons[month - 1]))
                .andExpect(jsonPath("$.now.month").value(month)).andExpect(jsonPath("$.now.phase").value(phase))
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
        for (id in listOf(null, "", " 10", "abc", "1.0", "+10", "-1", "0", "00", "2147483648", "99999999999999999999")) {
            val request = get(PATH).header("Authorization", "Bearer ${token()}")
            if (id != null) request.param("generalId", id)
            mvc.perform(request).andExpect(status().isBadRequest)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("INVALID_GENERAL_ID"))
                .andExpect(jsonPath("$.error.message").value("장수 번호가 올바르지 않습니다."))
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

    @Test fun `another process world or an impossible date is unavailable, never a guessed season`() {
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 2, currentYear = 200, currentMonth = 7,
            currentPhase = 1))
        expectUnavailable(WorldSeasonReason.WORLD_UNAVAILABLE, now = false)
        for ((year, month, phase) in listOf(Triple(0, 7, 1), Triple(200, 13, 1), Triple(200, 0, 1),
            Triple(200, 7, 0), Triple(200, 7, 4))) {
            world(year, month, phase)
            expectUnavailable(WorldSeasonReason.WORLD_DATE_INVALID, now = false)
        }
    }

    @Test fun `damaged or unconfirmed calendar values close the season instead of falling back`() {
        world(200, 7, 3)
        val spring = { row: ObjectNode -> row["values"]["springStartMonth"] as ObjectNode }
        val probes = listOf("{}", "not json",
            edited { root, _ -> root.put("schemaVersion", 2) },
            edited { _, row -> spring(row).put("status", "PROPOSED") },
            edited { _, row -> spring(row).put("value", 7) },
            edited { _, row -> (row["values"] as ObjectNode).remove("winterStartMonth") },
            edited { _, row -> row.put("domain", "almanac") },
            edited { root, row -> (root["rows"] as ArrayNode).add(row.deepCopy()) })
        for (raw in probes) {
            calendar.value = raw
            expectUnavailable(WorldSeasonReason.SEASON_CALENDAR_INVALID, now = true)
        }
        calendar.value = edited { _, _ -> }
        mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
            .andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.season").value("SUMMER"))
    }

    /** Edits the calendar row of the repository payload as a tree, so a probe cannot silently miss its target. */
    private fun edited(edit: (ObjectNode, ObjectNode) -> Unit): String {
        val root = mapper.readTree(REPO_CALENDAR) as ObjectNode
        val row = root["rows"].single { it["domain"].asText() == "calendar" } as ObjectNode
        edit(root, row)
        return mapper.writeValueAsString(root)
    }

    private fun expectUnavailable(reason: String, now: Boolean) {
        val result = mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.status").value("UNAVAILABLE")).andExpect(jsonPath("$.reason").value(reason))
            .andExpect(jsonPath("$.season").value(nullValue())).andExpect(jsonPath("$.phaseOfYear").value(nullValue()))
            .andExpect(jsonPath("$.passageStatus").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.closedEdges").value(nullValue()))
            .andExpect(jsonPath("$.eventsStatus").value("UNAVAILABLE")).andExpect(jsonPath("$.events").value(nullValue()))
            .andReturn()
        assertEquals(now, !mapper.readTree(result.response.contentAsByteArray)["now"].isNull)
    }

    private fun world(year: Int, month: Int, phase: Int) {
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1, currentYear = year,
            currentMonth = month, currentPhase = phase))
    }

    private fun fixture(name: String): JsonNode = mapper.readTree(
        requireNotNull(javaClass.getResourceAsStream("/world/season/$name.json")))

    private fun token(user: String = "41", role: String = "USER", type: String = GatewayJwtClaims.ACCESS_TOKEN,
        lifetime: Long = 60000, audience: String = GatewayJwtContract.GAME_API_AUDIENCE): String {
        val now = Date()
        return Jwts.builder().subject(user).issuer(GatewayJwtContract.ISSUER).audience().add(audience).and()
            .issuedAt(now).expiration(Date(now.time + lifetime)).claim(GatewayJwtClaims.TOKEN_TYPE, type)
            .claim(GatewayJwtClaims.ROLE, role).signWith(KEYS.private).compact()
    }

    companion object {
        val KEYS = Jwts.SIG.RS256.keyPair().build()
        const val PATH = "/api/world/season"
        val REPO_CALENDAR: String = File("../../data/curated/han/world-event-values.json").readText()
        @JvmStatic fun fixtureNames() = listOf("ready", "boundary", "not-seeded", "unavailable")
    }
}

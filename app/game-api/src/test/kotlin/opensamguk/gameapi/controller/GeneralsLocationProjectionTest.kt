package opensamguk.gameapi.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.*
import opensamguk.gameapi.security.GameApiJwtVerifier
import opensamguk.gameapi.security.GameApiSecurityConfig
import opensamguk.gameapi.security.JwtVerifyFilter
import opensamguk.gameapi.web.AdminCampaignDirectoryController
import opensamguk.gameapi.web.CampaignDirectoryController
import opensamguk.logic.input.PersonPolicyState
import org.hamcrest.Matchers.containsString
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
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.Date
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [GeneralsLocationProjectionTest.Config::class,
    GameApiSecurityConfig::class, GeneralsController::class])
class GeneralsLocationProjectionTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    open class Config {
        @Bean open fun verifier() = GameApiJwtVerifier("", SECRET, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun owners() = mock(GeneralResolver::class.java)
        @Bean open fun worlds() = mock(WorldStateReadRepository::class.java)
        @Bean open fun generals() = mock(GeneralReadRepository::class.java)
        @Bean open fun nations() = mock(NationReadRepository::class.java)
        @Bean open fun cities() = mock(CityReadRepository::class.java)
        @Bean open fun retainers() = mock(RetainerReadRepository::class.java)
        @Bean open fun artifacts() = mock(ActiveWorldArtifactResolver::class.java)
        @Bean open fun accessLogs() = mock(GeneralAccessLogReadRepository::class.java)
        @Bean open fun reader(worlds: WorldStateReadRepository, generals: GeneralReadRepository,
            nations: NationReadRepository, artifacts: ActiveWorldArtifactResolver,
            retainers: RetainerReadRepository, owners: GeneralResolver) =
            CampaignDirectoryReader(worlds, generals, nations, artifacts, retainers, owners)
        @Bean open fun directory(reader: CampaignDirectoryReader) = CampaignDirectoryController(reader)
        @Bean open fun admin(reader: CampaignDirectoryReader, verifier: GameApiJwtVerifier) =
            AdminCampaignDirectoryController(reader, verifier)
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var owners: GeneralResolver
    @Autowired lateinit var worlds: WorldStateReadRepository
    @Autowired lateinit var generals: GeneralReadRepository
    @Autowired lateinit var nations: NationReadRepository
    @Autowired lateinit var cities: CityReadRepository
    @Autowired lateinit var retainers: RetainerReadRepository
    @Autowired lateinit var artifacts: ActiveWorldArtifactResolver
    @Autowired lateinit var accessLogs: GeneralAccessLogReadRepository
    private lateinit var mvc: MockMvc
    private val mapper = ObjectMapper()
    private val policy = PersonPolicyState(20, true, "synthetic-qa:list-context", "1", 1).toMetaValue()
    private val people = (1..6).map { id -> GeneralReadEntity(
        id = id, worldId = 7, name = "인물$id", userId = when (id) { 1 -> "41"; 3 -> "43"; 4 -> "42"; else -> null },
        nationId = if (id <= 3 || id == 5) 10 else 20, cityId = 100 + id,
        leadership = 61, strength = 72, intel = 83, politics = 94, charm = 55,
        meta = mapOf(PersonPolicyState.META_KEY to policy)) }
    private val cityRows = people.map { CityReadEntity(id = it.cityId, worldId = 7,
        name = if (it.id % 2 == 0) "合成城${it.id}" else "합성성${it.id}") }
    private val cards = listOf(
        GeneralRetainerReadEntity(worldId = 7, id = 1, masterGeneralId = 1, generalId = 2),
        GeneralRetainerReadEntity(worldId = 7, id = 2, masterGeneralId = 2, generalId = 5))

    @BeforeEach fun setup() {
        reset(owners, worlds, generals, nations, cities, retainers, artifacts, accessLogs)
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 7))
        `when`(generals.findAll()).thenReturn(people)
        people.forEach { `when`(generals.findById(it.id)).thenReturn(Optional.of(it)) }
        `when`(owners.resolveGeneralId(41)).thenReturn(1)
        `when`(owners.resolveGeneralId(42)).thenReturn(4)
        `when`(owners.resolveGeneralId(43)).thenReturn(3)
        `when`(owners.resolveGeneralId(44)).thenReturn(null)
        `when`(nations.findAll()).thenReturn(listOf(
            NationReadEntity(id = 10, worldId = 7, name = "합성甲"),
            NationReadEntity(id = 20, worldId = 7, name = "합성乙")))
        `when`(cities.findAll()).thenReturn(cityRows)
        `when`(retainers.findAll()).thenReturn(cards)
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test fun `public list preserves capabilities without locations`() {
        val rows = oldRows()
        assertEquals(6, rows.size())
        assertCapabilities(rows)
        assertLocations(rows, emptySet())
        mvc.perform(get("/api/people")).andExpect(status().isUnauthorized)
        verifyNoInteractions(owners, retainers)
    }

    @Test fun `direct relations match the people directory`() = compare(41, setOf(1, 2))
    @Test fun `another nation matches the people directory`() = compare(42, setOf(4))
    @Test fun `another household in the same nation matches the people directory`() = compare(43, setOf(3))

    @Test fun `verified administrator matches the administrator directory`() {
        val token = token(91, "ADMIN")
        val old = oldRows(token)
        val modern = modernRows(token, admin = true)
        assertLocations(old, (1..6).toSet())
        compareRows(old, modern)
        verifyNoInteractions(owners)
    }

    @Test fun `invalid identity retains the public list projection`() {
        assertLocations(oldRows("invalid"), emptySet())
        mvc.perform(get("/api/people").header("Authorization", "Bearer invalid"))
            .andExpect(status().isUnauthorized)
    }

    @Test fun `account without a general retains public capabilities`() {
        assertCapabilities(oldRows(token(44)))
        assertLocations(oldRows(token(44)), emptySet())
        mvc.perform(get("/api/people").header("Authorization", "Bearer ${token(44)}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("NO_GENERAL"))
    }

    @Test fun `query parameters do not change the reader context`() {
        val response = mvc.perform(get("/api/generals").param("generalId", "4")
            .param("role", "ADMIN").param("admin", "true").header("Authorization", "Bearer ${token(41)}"))
            .andExpect(status().isOk).andReturn().response
        assertLocations(mapper.readTree(response.contentAsString), setOf(1, 2))
    }

    @Test fun `ownership confirmation matches the people directory`() {
        `when`(generals.findById(1)).thenReturn(Optional.of(GeneralReadEntity(id = 1, worldId = 7, userId = "99")))
        for (path in listOf("/api/generals", "/api/people")) {
            mvc.perform(get(path).header("Authorization", "Bearer ${token(41)}"))
                .andExpect(status().isForbidden)
        }
    }

    @Test fun `cross world relationships match the people directory`() {
        `when`(retainers.findAll()).thenReturn(cards +
            GeneralRetainerReadEntity(worldId = 8, id = 3, masterGeneralId = 1, generalId = 4))
        for (path in listOf("/api/generals", "/api/people")) {
            mvc.perform(get(path).header("Authorization", "Bearer ${token(41)}"))
                .andExpect(status().isConflict)
        }
    }

    @Test fun `relationship changes are reflected on the next read`() {
        compare(41, setOf(1, 2))
        `when`(retainers.findAll()).thenReturn(emptyList())
        compare(41, setOf(1))
    }

    @Test fun `context specific responses are not stored by shared caches`() {
        for (bearer in listOf<String?>(null, token(41), token(91, "ADMIN"))) {
            val request = get("/api/generals")
            bearer?.let { request.header("Authorization", "Bearer $it") }
            mvc.perform(request).andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", containsString("no-store")))
        }
    }

    private fun compare(userId: Long, visible: Set<Int>) {
        val token = token(userId)
        val old = oldRows(token)
        assertCapabilities(old)
        assertLocations(old, visible)
        compareRows(old, modernRows(token))
    }

    private fun compareRows(old: JsonNode, modern: JsonNode) {
        assertEquals(old.map { it["generalId"].asInt() }, modern.map { it["generalId"].asInt() })
        for (row in old) {
            val id = row["generalId"].asInt()
            val corresponding = modern.single { it["generalId"].asInt() == id }
            val location = corresponding["locationCityId"]
            val expected = if (location.isNull) "" else cityRows.single { it.id == location.asInt() }.name
            assertEquals(expected, row["cityName"].asText(), "person $id")
            for (key in listOf("leadership", "strength", "intel", "politics", "charm")) {
                assertEquals(row[key].asInt(), corresponding["stats"][key].asInt())
            }
        }
    }

    private fun assertCapabilities(rows: JsonNode) {
        for (row in rows) {
            assertEquals(listOf(61, 72, 83, 94, 55),
                listOf("leadership", "strength", "intel", "politics", "charm").map { row[it].asInt() })
        }
    }

    private fun assertLocations(rows: JsonNode, visible: Set<Int>) {
        for (row in rows) {
            val id = row["generalId"].asInt()
            assertEquals(if (id in visible) cityRows.single { it.id == 100 + id }.name else "",
                row["cityName"].asText(), "person $id")
            assertNull(row["cityId"])
            assertNull(row["locationCityId"])
        }
        for (city in cityRows.filter { it.id - 100 !in visible }) {
            assertFalse(rows.toString().contains(city.name))
        }
        assertTrue(rows.all { it.has("generalId") })
    }

    private fun oldRows(bearer: String? = null): JsonNode {
        val request = get("/api/generals")
        bearer?.let { request.header("Authorization", "Bearer $it") }
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk).andReturn().response.contentAsString)
    }

    private fun modernRows(bearer: String, admin: Boolean = false): JsonNode = mapper.readTree(
        mvc.perform(get(if (admin) "/api/admin/people" else "/api/people")
            .header("Authorization", "Bearer $bearer")).andExpect(status().isOk)
            .andReturn().response.contentAsString)["people"]

    private fun token(userId: Long, role: String = "USER"): String {
        val now = Date()
        return Jwts.builder().subject(userId.toString()).issuedAt(now).expiration(Date(now.time + 60000))
            .claim(GatewayJwtClaims.TOKEN_TYPE, GatewayJwtClaims.ACCESS_TOKEN)
            .claim(GatewayJwtClaims.ROLE, role)
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact()
    }

    companion object {
        const val SECRET = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU="
    }
}

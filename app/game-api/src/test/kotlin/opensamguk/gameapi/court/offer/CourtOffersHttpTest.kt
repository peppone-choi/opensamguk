package opensamguk.gameapi.court.offer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.common.auth.GatewayJwtContract
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.security.*
import opensamguk.logic.input.Phase
import opensamguk.logic.office.OfficeAppointmentOffer
import opensamguk.logic.office.OfficeAppointmentRequest
import opensamguk.logic.office.OfficeOfferStatus
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
@ContextConfiguration(classes = [CourtOffersHttpTest.Config::class, GameApiSecurityConfig::class])
class CourtOffersHttpTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    open class Config {
        @Bean open fun source() = AdmissionSource()
        @Bean open fun admissionPolicy(source: AdmissionSource) = ServerAdmissionPolicy(source)
        @Bean open fun verifier() = GameApiJwtVerifier(Base64.getEncoder().encodeToString(KEYS.public.encoded), "", "")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun resolver() = mock(GeneralResolver::class.java)
        @Bean open fun worlds() = mock(WorldStateReadRepository::class.java)
        @Bean open fun generals() = mock(GeneralReadRepository::class.java)
        @Bean open fun reader(w: WorldStateReadRepository, g: GeneralReadRepository) = CourtOffersReader(w, g)
        @Bean open fun query(r: GeneralResolver, reader: CourtOffersReader) = CourtOffersQuery(r, reader)
        @Bean open fun controller(query: CourtOffersQuery) = CourtOffersController(query)
    }

    class AdmissionSource : ServerAdmissionSource {
        var state: ServerPublicationState? = ServerPublicationState.PUBLIC
        var revision = 1L
        override fun readFresh(): ServerAdmissionRead = state?.let {
            ServerAdmissionRead.Known(ServerAdmissionSnapshot("testfixture", it, revision),
                System.nanoTime(), ServerAdmissionDraftBudget.totalNanos)
        } ?: ServerAdmissionRead.Unavailable
    }

    @Autowired lateinit var source: AdmissionSource
    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var resolver: GeneralResolver
    @Autowired lateinit var worlds: WorldStateReadRepository
    @Autowired lateinit var generals: GeneralReadRepository
    private lateinit var mvc: MockMvc
    private val mapper = ObjectMapper()

    @BeforeEach fun setup() {
        reset(resolver, worlds, generals)
        source.state = ServerPublicationState.PUBLIC
        source.revision++
        val actor = person()
        `when`(resolver.resolve(41)).thenReturn(GeneralResolver.ResolvedGeneral(actor, 0, 0, 7, 1))
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(
            id = 1, currentYear = 201, currentMonth = 4, currentPhase = 3))
        `when`(generals.findById(10)).thenReturn(Optional.of(actor))
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @ParameterizedTest @MethodSource("fixtures")
    fun `actual personal source preserves office states dates terms and unavailable envelope`(name: String) {
        val expected = fixture(name)
        when (name) {
            "world-unavailable" -> `when`(worlds.findProcessWorld()).thenReturn(null)
            "source-unavailable" -> Unit
            else -> persisted(offer(OfficeOfferStatus.valueOf(name.removePrefix("office-").uppercase())).toMetaValue())
        }
        val result = request().andExpect(if (name == "world-unavailable") status().isServiceUnavailable else status().isOk)
            .andExpect(header().string("Cache-Control", "no-store")).andReturn()
        assertEquals(expected, mapper.readTree(result.response.contentAsByteArray))
        verify(resolver).resolve(41)
    }

    @Test fun `anonymous and invalid refresh expired or foreign audience tokens never read private metadata`() {
        val tokens = listOf(null, "invalid", token(type = GatewayJwtClaims.REFRESH_TOKEN),
            token(lifetime = -60000), token(audience = GatewayJwtContract.BOARD_API_AUDIENCE))
        for (bearer in tokens) {
            val req = get(PATH).param("generalId", "10").param("userId", "41")
            if (bearer != null) req.header("Authorization", "Bearer $bearer")
            mvc.perform(req).andExpect(status().isUnauthorized)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"))
        }
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized)
            .andExpect(header().string("Cache-Control", "no-store"))
        verifyNoInteractions(resolver, worlds, generals)
    }

    @Test fun `verified caller must select their live actor even with ADMIN or query userId`() {
        for ((actor, bearer) in listOf("20" to token(), "10" to token(user = "42"),
            "20" to token(role = "ADMIN"), "0" to token(), "-1" to token())) {
            mvc.perform(get(PATH).param("generalId", actor).param("userId", "41")
                .header("Authorization", "Bearer $bearer"))
                .andExpect(status().isForbidden).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
        }
        verifyNoInteractions(worlds, generals)
    }

    @Test fun `missing or malformed actor identifier has a fixed noncacheable authenticated error`() {
        for (actor in listOf(null, "", "bad", "2147483648")) {
            val req = get(PATH).header("Authorization", "Bearer ${token()}")
            if (actor != null) req.param("generalId", actor)
            mvc.perform(req).andExpect(status().isBadRequest)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("INVALID_GENERAL_ID"))
        }
        verifyNoInteractions(resolver, worlds, generals)
    }

    @Test fun `persisted detail must retain the same verified account actor and process world`() {
        val meta = mapOf(OfficeAppointmentOffer.META_KEY to offer().toMetaValue())
        val rows = listOf(person(meta, id = 20), person(meta, world = 2), person(meta, user = "42"))
        for (row in rows) {
            `when`(generals.findById(10)).thenReturn(Optional.of(row))
            unavailableOffice()
        }
        `when`(generals.findById(10)).thenReturn(Optional.empty())
        unavailableOffice()
    }

    @Test fun `actor in another process world is forbidden before personal source access`() {
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(
            id = 2, currentYear = 201, currentMonth = 4, currentPhase = 3))
        request().andExpect(status().isForbidden)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
        verifyNoInteractions(generals)
    }

    @Test fun `missing or invalid process world and clock return sanitized503 before personal source access`() {
        for (world in listOf(null, WorldStateReadEntity(id = 0),
            WorldStateReadEntity(id = 1, currentYear = 201, currentMonth = 13, currentPhase = 3),
            WorldStateReadEntity(id = 1, currentYear = 0, currentMonth = 4, currentPhase = 3))) {
            `when`(worlds.findProcessWorld()).thenReturn(world)
            unavailableWorld()
        }
        doThrow(org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT, "private format detail")).`when`(worlds).findProcessWorld()
        unavailableWorld()
        doThrow(org.springframework.dao.DataAccessResourceFailureException("private storage detail")).`when`(worlds).findProcessWorld()
        unavailableWorld()
        verifyNoInteractions(generals)
    }

    @Test fun `VERIFYING forbids verified USER and ADMIN before actor or world lookup`() {
        source.state = ServerPublicationState.VERIFYING
        source.revision++
        for (role in listOf("USER", "ADMIN")) {
            mvc.perform(get(PATH).param("generalId", "10").header("Authorization", "Bearer ${token(role = role)}"))
                .andExpect(status().isForbidden).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("SERVER_NOT_PUBLIC"))
        }
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized)
            .andExpect(header().string("Cache-Control", "no-store"))
        verifyNoInteractions(resolver, worlds, generals)
    }

    @Test fun `unavailable publication source returns503 before actor or world lookup`() {
        source.state = null
        request().andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.error.code").value("SERVER_ADMISSION_UNAVAILABLE"))
        verifyNoInteractions(resolver, worlds, generals)
    }

    private fun unavailableWorld() {
        val response = request().andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.error.code").value("WORLD_UNAVAILABLE")).andReturn()
        assertEquals(fixture("world-unavailable"), mapper.readTree(response.response.contentAsByteArray))
    }

    @Test fun `null malformed or unsupported personal source is never a ready empty source`() {
        val valid = offer().toMetaValue()
        val badRequest = (valid["request"] as Map<*, *>).entries.associate { it.key to it.value } +
            ("candidateId" to 20)
        for (raw in listOf(null, "{}", emptyMap<String, Any>(), valid + ("version" to 2),
            valid + ("version" to 1L), valid + ("status" to "APPLIED"), valid + ("request" to badRequest),
            valid + ("dueAt" to mapOf("year" to 201, "month" to 4, "phase" to 4)))) {
            persisted(raw)
            unavailableOffice()
        }
    }

    @Test fun `vassal contracts edicts claims and unconnected nomination metadata are not proposal sources`() {
        val meta = mapOf("vassalContracts" to mapOf("version" to 1),
            "officeClaimHistory" to mapOf("version" to 1), "imperialEdicts" to mapOf("schema" to 1),
            "officeNominations" to mapOf("id" to "not-a-connected-source", "status" to "OFFERED"))
        `when`(generals.findById(10)).thenReturn(Optional.of(person(meta)))
        val result = request().andExpect(status().isOk).andReturn()
        assertEquals(fixture("source-unavailable"), mapper.readTree(result.response.contentAsByteArray))
    }

    @Test fun `stored office deadline is not regenerated as now plus three or turned into automatic acceptance`() {
        val original = offer()
        persisted(original.toMetaValue())
        request().andExpect(status().isOk).andExpect(jsonPath("$.offers[0].state").value("PENDING"))
            .andExpect(jsonPath("$.offers[0].issuedAt.year").value(200))
            .andExpect(jsonPath("$.offers[0].dueAt.year").value(201))
            .andExpect(jsonPath("$.offers[0].dueAt.month").value(4))
            .andExpect(jsonPath("$.offers[0].offerId").doesNotExist())
            .andExpect(jsonPath("$.offers[0].replyInputId").doesNotExist())
            .andExpect(jsonPath("$.offers[0].responseOptions").isEmpty)
        verify(generals).findById(10)
        verifyNoMoreInteractions(generals)
    }

    private fun request() = mvc.perform(get(PATH).param("generalId", "10")
        .header("Authorization", "Bearer ${token()}"))

    private fun unavailableOffice() {
        request().andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.offers").isEmpty)
            .andExpect(jsonPath("$.sources[0].readStatus").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.sources[0].records").doesNotExist())
            .andExpect(jsonPath("$.sources[0].revisionStatus").doesNotExist())
    }

    private fun persisted(raw: Any?) {
        `when`(generals.findById(10)).thenReturn(Optional.of(person(mapOf(OfficeAppointmentOffer.META_KEY to raw))))
    }

    private fun person(meta: Map<String, Any?> = emptyMap(), id: Int = 10, world: Int = 1, user: String = "41") =
        GeneralReadEntity(id = id, worldId = world, userId = user, nationId = 7, meta = meta)

    private fun offer(state: OfficeOfferStatus = OfficeOfferStatus.PENDING) = OfficeAppointmentOffer(
        "stored-office-1", OfficeAppointmentRequest(20, 10, "office.commandery-prefect", "hhs-group:109:京兆尹", 100),
        Phase(200, 12, 3), Phase(201, 4, 3), state)

    private fun fixture(name: String): JsonNode = mapper.readTree(
        requireNotNull(javaClass.getResourceAsStream("/court/offer/$name.json")))

    private fun token(user: String = "41", role: String = "USER", type: String = GatewayJwtClaims.ACCESS_TOKEN,
        lifetime: Long = 60000, audience: String = GatewayJwtContract.GAME_API_AUDIENCE): String {
        val now = Date()
        return Jwts.builder().subject(user).issuer(GatewayJwtContract.ISSUER).audience().add(audience).and()
            .issuedAt(now).expiration(Date(now.time + lifetime)).claim(GatewayJwtClaims.TOKEN_TYPE, type)
            .claim(GatewayJwtClaims.ROLE, role).signWith(KEYS.private).compact()
    }

    companion object {
        val KEYS = Jwts.SIG.RS256.keyPair().build()
        const val PATH = "/api/court/offers"
        @JvmStatic fun fixtures() = listOf("office-pending", "office-accepted", "office-refused", "source-unavailable", "world-unavailable")
    }
}

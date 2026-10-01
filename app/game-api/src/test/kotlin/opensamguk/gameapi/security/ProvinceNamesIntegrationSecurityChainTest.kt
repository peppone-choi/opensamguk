package opensamguk.gameapi.security

import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.gameapi.controller.ProvinceNamesController
import opensamguk.gameapi.read.*
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.infra.seed.WorldArtifactsResolver
import opensamguk.infra.seed.WorldTopologyPin
import opensamguk.logic.world.StrategicRouteProjection
import opensamguk.logic.world.StrategicTopologySnapshot
import opensamguk.logic.world.WorldMapVariant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Real reader/resolver/controller/JWT chain; only DB reads and the immutable artifact catalog are mocked. */
@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [ProvinceNamesIntegrationSecurityChainTest.Config::class, GameApiSecurityConfig::class])
class ProvinceNamesIntegrationSecurityChainTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    open class Config {
        @Bean open fun verifier() = GameApiJwtVerifier("", SECRET, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun states() = mock(WorldStateReadRepository::class.java)
        @Bean open fun cities() = mock(CityReadRepository::class.java)
        @Bean open fun pins() = mock(WorldArtifactIdentityReadRepository::class.java)
        @Bean open fun catalog() = mock(WorldArtifactsResolver::class.java)
        @Bean open fun artifacts(states: WorldStateReadRepository, cities: CityReadRepository,
                                pins: WorldArtifactIdentityReadRepository, catalog: WorldArtifactsResolver) =
            ActiveWorldArtifactResolver(states, cities, pins, catalog)
        @Bean open fun reader(states: WorldStateReadRepository, artifacts: ActiveWorldArtifactResolver,
                             pins: WorldArtifactIdentityReadRepository) = ProvinceNamesReader(states, artifacts, pins)
        @Bean open fun controller(reader: ProvinceNamesReader) = ProvinceNamesController(reader)
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var states: WorldStateReadRepository
    @Autowired lateinit var cities: CityReadRepository
    @Autowired lateinit var pins: WorldArtifactIdentityReadRepository
    @Autowired lateinit var catalog: WorldArtifactsResolver
    private lateinit var mvc: MockMvc
    private lateinit var bundle: ResolvedWorldArtifacts
    private lateinit var topology: StrategicTopologySnapshot
    private var worldId = 0
    private val mapper = ObjectMapper()
    private val hash = "b".repeat(64)
    private val saved = listOf(WorldTopologyPin("province_control", "synthetic", hash))
    private val metadata = "/api/map/provinces/names"
    private val source = """{"owner":"private-owner","geometry":"private-geometry","provinceRecords":[{"id":"200012","displayName":"문안현","fog":"private-fog"},{"id":"KOR-X1","displayName":"구역 이름","generalId":999}]}""".toByteArray()

    @BeforeEach fun setup() {
        reset(states, cities, pins, catalog)
        bundle = mock(ResolvedWorldArtifacts::class.java)
        topology = mock(StrategicTopologySnapshot::class.java)
        val projection = mock(StrategicRouteProjection::class.java)
        `when`(bundle.variant).thenReturn(WorldMapVariant.V3_1428)
        `when`(bundle.projection).thenReturn(projection)
        `when`(projection.topology).thenReturn(topology)
        `when`(topology.topologyRevision).thenReturn("synthetic")
        `when`(topology.contentHash).thenReturn(hash)
        `when`(topology.landProvinceIds).thenReturn(setOf("200012", "KOR-X1"))
        `when`(topology.artifactHashes).thenReturn(mapOf("data/map/han-tiles.json" to
            MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }))
        `when`(bundle.artifactBytes("data/map/han-tiles.json")).thenReturn(source)
        selectWorld(nextWorld.incrementAndGet())
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun selectWorld(id: Int) {
        worldId = id
        `when`(states.findProcessWorld()).thenReturn(WorldStateReadEntity(id = id, config = mapOf("mapName" to "han-world-v3")))
        `when`(cities.findAll()).thenReturn(listOf(CityReadEntity(id = 11, worldId = id)))
        `when`(pins.readPins(id)).thenReturn(saved)
        `when`(catalog.resolve(listOf(11), saved)).thenReturn(bundle)
    }

    private fun path(): String {
        val result = mvc.perform(get(metadata)).andExpect(status().isOk).andReturn().response
        return mapper.readTree(result.contentAsByteArray)["representationPath"].asText()
    }

    @Test fun `real selection and label projection serve identical bytes to anonymous invalid refresh user and admin JWT`() {
        val path = path()
        val auths = identities()
        var bytes: ByteArray? = null
        var tag: String? = null
        for (auth in auths) {
            val request = get(path)
            if (auth != null) request.header("Authorization", auth)
            val result = mvc.perform(request).andExpect(status().isOk).andReturn().response
            if (bytes == null) { bytes = result.contentAsByteArray; tag = result.getHeader("ETag") }
            else { assertEquals(bytes!!.toList(), result.contentAsByteArray.toList()); assertEquals(tag, result.getHeader("ETag")) }
            val node = mapper.readTree(result.contentAsByteArray)
            assertEquals(worldId, node["worldId"].asInt())
            assertEquals(setOf("worldId", "mapRelease", "topologyRevision", "topologyHash", "sourceSha256", "names"),
                node.fieldNames().asSequence().toSet())
            assertEquals(listOf("200012", "KOR-X1"), node["names"].map { it["provinceId"].asText() })
            assertEquals(listOf("문안현", "구역 이름"), node["names"].map { it["displayName"].asText() })
            node["names"].forEach { assertEquals(setOf("provinceId", "displayName"), it.fieldNames().asSequence().toSet()) }
            assertFalse(result.contentAsString.contains("private-"))
        }
        verify(bundle, times(1)).artifactBytes("data/map/han-tiles.json")
        verify(catalog, times(1 + auths.size)).resolve(listOf(11), saved)
        verify(pins, times(2 * (1 + auths.size))).readPins(worldId)
    }

    @Test fun `empty or changed stored pins fail before conditional cached output with safe no-store body`() {
        val path = path()
        mvc.perform(get(path)).andExpect(status().isOk)
        `when`(pins.readPins(worldId)).thenReturn(emptyList())
        clearInvocations(catalog, cities)
        mvc.perform(get(path).header("If-None-Match", "*")).andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().string(""))
        verifyNoInteractions(catalog, cities)
        `when`(pins.readPins(worldId)).thenReturn(saved)
        `when`(topology.contentHash).thenReturn("c".repeat(64))
        mvc.perform(get(path).header("If-None-Match", "*")).andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().string(""))
    }

    @Test fun `actual world reset rejects old immutable URL before wildcard and missing world is no-store`() {
        val oldPath = path()
        selectWorld(nextWorld.incrementAndGet())
        mvc.perform(get(oldPath).header("If-None-Match", "*")).andExpect(status().isConflict)
            .andExpect(header().string("Cache-Control", "no-store"))
        mvc.perform(get(metadata)).andExpect(status().isOk).andExpect(jsonPath("$.worldId").value(worldId))
        doReturn(null).`when`(states).findProcessWorld()
        mvc.perform(get(oldPath).header("If-None-Match", "*")).andExpect(status().isNotFound)
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().string(""))
    }

    @Test fun `duplicate pin and actor metadata queries never read the real world selection`() {
        val path = path()
        clearInvocations(states, cities, pins, catalog)
        for (url in listOf("$path&worldId=$worldId", "$metadata?generalId=999")) {
            mvc.perform(get(url)).andExpect(status().isBadRequest)
                .andExpect(header().string("Cache-Control", "no-store"))
        }
        verifyNoInteractions(states, cities, pins, catalog)
    }

    @Test fun `both real endpoints deny unsupported methods before world or artifact selection for every identity`() {
        val immutablePath = path()
        clearInvocations(states, cities, pins, catalog, bundle, topology)
        for (path in listOf(metadata, immutablePath)) {
            for (method in listOf(HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.POST,
                HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
                for (auth in identities()) {
                    val req = request(method, path).header("If-None-Match", "*")
                    if (auth != null) req.header("Authorization", auth)
                    mvc.perform(req).andExpect(status().isForbidden)
                }
            }
        }
        verifyNoInteractions(states, cities, pins, catalog, bundle, topology)
    }

    private fun identities(): List<String?> = listOf(null, "Bearer invalid",
        "Bearer ${token("USER", GatewayJwtClaims.REFRESH_TOKEN)}",
        "Bearer ${token("USER")}", "Bearer ${token("ADMIN")}")

    private fun token(role: String, type: String = GatewayJwtClaims.ACCESS_TOKEN): String {
        val now = Date()
        return Jwts.builder().subject("41").issuedAt(now).expiration(Date(now.time + 60000))
            .claim(GatewayJwtClaims.TOKEN_TYPE, type).claim(GatewayJwtClaims.ROLE, role)
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact()
    }

    companion object {
        private val nextWorld = AtomicInteger(100)
        const val SECRET = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU="
    }
}

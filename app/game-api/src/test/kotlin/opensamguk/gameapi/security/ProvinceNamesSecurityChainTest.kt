package opensamguk.gameapi.security

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.controller.ProvinceNamesController
import opensamguk.gameapi.dto.ProvinceNameDto
import opensamguk.gameapi.dto.ProvinceNamesDto
import opensamguk.gameapi.read.ProvinceNamesReader
import opensamguk.gameapi.read.ProvinceNamesRepresentation
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import kotlin.test.*

@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [ProvinceNamesSecurityChainTest.Config::class, GameApiSecurityConfig::class])
class ProvinceNamesSecurityChainTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    open class Config {
        @Bean open fun verifier() = GameApiJwtVerifier("", SECRET, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun reader() = mock(ProvinceNamesReader::class.java)
        @Bean open fun controller(reader: ProvinceNamesReader) = ProvinceNamesController(reader)
    }
    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var reader: ProvinceNamesReader
    private lateinit var mvc: MockMvc
    private val mapper = ObjectMapper()
    private val dto = ProvinceNamesDto(7, "synthetic-release", "synthetic-topology", "b".repeat(64),
        "c".repeat(64), listOf(ProvinceNameDto("P1", "한구역")))
    private val representation = ProvinceNamesRepresentation(dto, mapper.writeValueAsBytes(dto))
    private val metadata = "/api/map/provinces/names"

    @BeforeEach fun setup() {
        reset(reader)
        `when`(reader.current()).thenReturn(representation)
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun path(): String {
        val body = mvc.perform(get(metadata)).andExpect(status().isOk).andReturn().response.contentAsByteArray
        return mapper.readTree(body)["representationPath"].asText()
    }

    @Test fun `anonymous bootstrap pins an immutable public allowlist without live or personal fields`() {
        val result = mvc.perform(get(metadata)).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", containsString("no-cache")))
            .andExpect(header().string("Cache-Control", containsString("must-revalidate")))
            .andExpect(header().string("Cache-Control", containsString("public")))
            .andReturn().response
        val node = mapper.readTree(result.contentAsByteArray)
        assertEquals(7, node["worldId"].asInt())
        assertEquals(setOf("worldId", "mapRelease", "topologyRevision", "topologyHash", "sourceSha256",
            "representationSha256", "representationPath"), node.fieldNames().asSequence().toSet())
        mvc.perform(get(node["representationPath"].asText()))
            .andExpect(status().isOk).andExpect(content().bytes(representation.body()))
            .andExpect(header().string("Cache-Control", containsString("max-age=31536000")))
            .andExpect(header().string("Cache-Control", containsString("public")))
            .andExpect(header().string("Cache-Control", containsString("immutable")))
            .andExpect(header().string("ETag", representation.etag))
        mvc.perform(get(node["representationPath"].asText()).header("Authorization", "Bearer invalid"))
            .andExpect(status().isOk).andExpect(content().bytes(representation.body()))
    }

    @Test fun `strong weak list and wildcard conditions revalidate only the current matching pins`() {
        val path = path()
        for (condition in listOf(representation.etag, "W/${representation.etag}", "\"other\", W/${representation.etag}", "*")) {
            mvc.perform(get(path).header("If-None-Match", condition)).andExpect(status().isNotModified)
                .andExpect(header().string("ETag", representation.etag)).andExpect(content().string(""))
        }
        mvc.perform(get(path).header("If-None-Match", "\"other\"" )).andExpect(status().isOk)
            .andExpect(content().bytes(representation.body()))
        val bootstrap = mvc.perform(get(metadata)).andReturn().response
        mvc.perform(get(metadata).header("If-None-Match", "W/${bootstrap.getHeader("ETag")}"))
            .andExpect(status().isNotModified)
    }

    @Test fun `missing extra or wrong full tuple pins are no-store and old tag cannot bypass them`() {
        val path = path()
        mvc.perform(get(ProvinceNamesController.PINNED_PATH)).andExpect(status().isBadRequest)
            .andExpect(header().string("Cache-Control", "no-store"))
        mvc.perform(get("$path&worldId=7")).andExpect(status().isBadRequest)
            .andExpect(header().string("Cache-Control", "no-store"))
        mvc.perform(get("$path&generalId=41")).andExpect(status().isBadRequest)
            .andExpect(header().string("Cache-Control", "no-store"))
        mvc.perform(get(path.replace("worldId=7", "worldId=8")).header("If-None-Match", "*"))
            .andExpect(status().isConflict).andExpect(header().string("Cache-Control", "no-store"))
        mvc.perform(get(path.replace("representationSha256=${representation.sha256}", "representationSha256=wrong"))
            .header("If-None-Match", representation.etag)).andExpect(status().isConflict)
            .andExpect(header().string("Cache-Control", "no-store"))
    }

    @Test fun `reset or failed pin selection refuses old immutable URL even with a matching condition`() {
        val oldPath = path()
        val updated = dto.copy(worldId = 8)
        `when`(reader.current()).thenReturn(ProvinceNamesRepresentation(updated, mapper.writeValueAsBytes(updated)))
        mvc.perform(get(oldPath).header("If-None-Match", representation.etag)).andExpect(status().isConflict)
            .andExpect(header().string("Cache-Control", "no-store"))
        mvc.perform(get(metadata)).andExpect(status().isOk).andExpect(jsonPath("$.worldId").value(8))
        `when`(reader.current()).thenThrow(IllegalArgumentException("private storage detail"))
        mvc.perform(get(oldPath).header("If-None-Match", "*")).andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().string(""))
        doReturn(null).`when`(reader).current()
        mvc.perform(get(metadata)).andExpect(status().isNotFound).andExpect(header().string("Cache-Control", "no-store"))
    }

    // C1 owns the explicit GET allowlist and non-GET denial patch; assert that boundary after main lands it.
    @Test fun `public GET leaves unrelated authenticated surfaces protected and rejects actor query`() {
        mvc.perform(get("/api/events?section=PERSONAL")).andExpect(status().isForbidden)
        mvc.perform(get("$metadata?generalId=41")).andExpect(status().isBadRequest)
            .andExpect(header().string("Cache-Control", "no-store"))
    }

    companion object { const val SECRET = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU=" }
}

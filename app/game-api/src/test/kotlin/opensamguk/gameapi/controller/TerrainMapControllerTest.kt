package opensamguk.gameapi.controller

import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * [TerrainMapController] 슬라이스 — 지형 격자를 주입하지 않은 배포가 죽지 않는다는 것과,
 * 0.3MB 를 재방문마다 다시 보내지 않는다는 것을 고정한다.
 */
class TerrainMapControllerTest {

    private fun mockMvc(file: String): MockMvc =
        MockMvcBuilders.standaloneSetup(TerrainMapController(file)).build()

    @Test
    fun `맵을 주입하지 않으면 404 로 답한다 - 프런트가 che 로 폴백한다`() {
        mockMvc("/nonexistent/han-tiles.json").perform(get("/api/map/terrain"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `격자를 그대로 흘려보내고 같은 ETag 재요청은 304 다`() {
        val f = Files.createTempFile("han-tiles", ".json")
        Files.writeString(f, """{"_meta":{"cols":256},"terrain":["01"]}""")
        val mvc = mockMvc(f.toString())

        val tag = mvc.perform(get("/api/map/terrain"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$._meta.cols").value(256))
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andReturn().response.getHeader(HttpHeaders.ETAG)!!

        mvc.perform(get("/api/map/terrain").header(HttpHeaders.IF_NONE_MATCH, tag))
            .andExpect(status().isNotModified)
        Files.deleteIfExists(f)
    }
}

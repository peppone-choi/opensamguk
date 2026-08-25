package opensamguk.gameapi.controller

import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * [TerrainMapController] 슬라이스 — 지형 격자를 주입하지 않은 배포가 죽지 않는다는 것과,
 * 큰 격자 blob을 재방문마다 다시 보내지 않는다는 것을 고정한다.
 */
class TerrainMapControllerTest {

    private fun mockMvc(file: String): MockMvc =
        MockMvcBuilders.standaloneSetup(TerrainMapController(file)).build()

    @Test
    fun `맵을 주입하지 않으면 폴백 없이 404 로 답한다`() {
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

    @Test
    fun `mapCode 의 타일 파일을 골라 서빙하고 ETag 재요청은 304 다`() {
        val dir = Files.createTempDirectory("map-tiles")
        val han = dir.resolve("han-tiles.json")
        val che = dir.resolve("che-tiles.json")
        Files.writeString(han, """{"map":"han"}""")
        Files.writeString(che, """{"map":"che"}""")
        val mvc = mockMvc(han.toString())

        val tag = mvc.perform(get("/api/map/terrain").queryParam("mapCode", "che"))
            .andExpect(status().isOk)
            .andExpect(content().string("""{"map":"che"}"""))
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andReturn().response.getHeader(HttpHeaders.ETAG)!!

        mvc.perform(
            get("/api/map/terrain")
                .queryParam("mapCode", "che")
                .header(HttpHeaders.IF_NONE_MATCH, tag),
        ).andExpect(status().isNotModified)

        Files.deleteIfExists(che)
        Files.deleteIfExists(han)
        Files.deleteIfExists(dir)
    }

    @Test
    fun `mapCode 경로 문자는 404 로 거절한다`() {
        val f = Files.createTempFile("han-tiles", ".json")
        Files.writeString(f, """{"map":"han"}""")

        mockMvc(f.toString()).perform(get("/api/map/terrain").queryParam("mapCode", "../secret"))
            .andExpect(status().isNotFound)

        Files.deleteIfExists(f)
    }
}

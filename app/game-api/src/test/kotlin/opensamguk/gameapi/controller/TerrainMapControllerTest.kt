package opensamguk.gameapi.controller

import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
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

    @Test
    fun `V3 terrain ETag pins exact bytes even when file size and timestamp are unchanged`() {
        val dir = Files.createTempDirectory("v3-terrain-hash")
        val canonical = dir.resolve("han-tiles.json")
        val v3 = dir.resolve("han-world-v3-tiles.json")
        val first = "{\"v\":1}".toByteArray()
        val second = "{\"v\":2}".toByteArray()
        try {
            Files.write(v3, first)
            val timestamp = Files.getLastModifiedTime(v3)
            val expected = "\"sha256-" + java.security.MessageDigest.getInstance("SHA-256")
                .digest(first).joinToString("") { "%02x".format(it) } + "\""
            val mvc = mockMvc(canonical.toString())
            mvc.perform(get("/api/map/terrain").queryParam("mapCode", "han-world-v3"))
                .andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.ETAG, expected))
            Files.write(v3, second)
            Files.setLastModifiedTime(v3, timestamp)
            mvc.perform(get("/api/map/terrain").queryParam("mapCode", "han-world-v3")
                .header(HttpHeaders.IF_NONE_MATCH, expected))
                .andExpect(status().isOk)
                .andExpect(content().bytes(second))
        } finally {
            Files.deleteIfExists(v3)
            Files.deleteIfExists(dir)
        }
    }

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
    fun `versioned compatibility mapCode serves its sibling terrain bytes`() {
        val dir = Files.createTempDirectory("versioned-map-tiles")
        val han = dir.resolve("han-tiles.json")
        val compatibility = dir.resolve("han-780-v1-tiles.json")
        val bytes = """{"map":"han-780-v1"}""".toByteArray()
        Files.writeString(han, """{"map":"han"}""")
        Files.write(compatibility, bytes)

        mockMvc(han.toString()).perform(
            get("/api/map/terrain").queryParam("mapCode", "han-780-v1"),
        )
            .andExpect(status().isOk)
            .andExpect(content().bytes(bytes))

        Files.deleteIfExists(compatibility)
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

    @Test
    fun `province 이미지를 바이트 그대로 보내고 같은 ETag 재요청은 304 다`() {
        val dir = Files.createTempDirectory("map-provinces")
        val han = dir.resolve("han-tiles.json")
        val che = dir.resolve("che-provinces.png")
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        Files.write(han, byteArrayOf())
        Files.write(che, bytes)
        val mvc = mockMvc(han.toString())

        val tag = mvc.perform(get("/api/map/provinces").queryParam("mapCode", "che"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.IMAGE_PNG))
            .andExpect(content().bytes(bytes))
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=3600, public"))
            .andReturn().response.getHeader(HttpHeaders.ETAG)!!

        mvc.perform(
            get("/api/map/provinces")
                .queryParam("mapCode", "che")
                .header(HttpHeaders.IF_NONE_MATCH, tag),
        ).andExpect(status().isNotModified)

        Files.deleteIfExists(che)
        Files.deleteIfExists(han)
        Files.deleteIfExists(dir)
    }

    @Test
    fun `기본 province mapCode 도 설정 파일의 형제 이미지를 쓴다`() {
        val dir = Files.createTempDirectory("map-provinces")
        val han = dir.resolve("han-tiles.json")
        val province = dir.resolve("han-provinces.png")
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        Files.write(han, byteArrayOf())
        Files.write(province, bytes)

        mockMvc(han.toString()).perform(get("/api/map/provinces"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.IMAGE_PNG))
            .andExpect(content().bytes(bytes))

        Files.deleteIfExists(province)
        Files.deleteIfExists(han)
        Files.deleteIfExists(dir)
    }

    @Test
    fun `province mapCode 경로 문자는 404 로 거절한다`() {
        val f = Files.createTempFile("han-tiles", ".json")
        Files.write(f, byteArrayOf())

        mockMvc(f.toString()).perform(get("/api/map/provinces").queryParam("mapCode", "../secret"))
            .andExpect(status().isNotFound)

        Files.deleteIfExists(f)
    }

    @Test
    fun `없는 province mapCode 는 404 다`() {
        val f = Files.createTempFile("han-tiles", ".json")
        Files.write(f, byteArrayOf())

        mockMvc(f.toString()).perform(get("/api/map/provinces").queryParam("mapCode", "che"))
            .andExpect(status().isNotFound)

        Files.deleteIfExists(f)
    }

    @Test
    fun `없는 che province 요청은 han province 로 폴백하지 않는다`() {
        val dir = Files.createTempDirectory("map-provinces")
        val han = dir.resolve("han-tiles.json")
        val hanProvince = dir.resolve("han-provinces.png")
        Files.write(han, byteArrayOf())
        Files.write(hanProvince, byteArrayOf(0x01))

        mockMvc(han.toString()).perform(get("/api/map/provinces").queryParam("mapCode", "che"))
            .andExpect(status().isNotFound)

        Files.deleteIfExists(hanProvince)
        Files.deleteIfExists(han)
        Files.deleteIfExists(dir)
    }
}

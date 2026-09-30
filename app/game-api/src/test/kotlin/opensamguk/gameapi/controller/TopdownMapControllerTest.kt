package opensamguk.gameapi.controller

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.read.TopdownMapArtifacts
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.world.WorldMapVariant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.util.pattern.PathPatternParser
import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TopdownMapControllerTest {
    @TempDir lateinit var testRoot: Path
    private val mapper = ObjectMapper()
    private val sourceBytes = mapOf(
        "data/map/han-tiles.json" to "terrain-source".toByteArray(),
        "infra/src/main/resources/map/han-world-v3.json" to "world-source".toByteArray(),
        "data/map/han-land-roads-v1.json" to "roads-source".toByteArray(),
    )
    private val variant = WorldMapVariant.entries.single { it.artifactId == "han-world-v3-1428" }

    private fun selected() = Mockito.mock(ResolvedWorldArtifacts::class.java).also { artifacts ->
        Mockito.`when`(artifacts.variant).thenReturn(variant)
        sourceBytes.forEach { (path, bytes) -> Mockito.`when`(artifacts.artifactBytes(path)).thenReturn(bytes) }
    }

    private data class Fixture(val root: Path, val bakeId: String, val manifest: com.fasterxml.jackson.databind.node.ObjectNode)

    private fun fixture(partial: Boolean = false): Fixture {
        val root = Files.createTempDirectory(testRoot, "topdown-bundle")
        val manifest = mapper.createObjectNode().apply {
            put("schemaVersion", 1); put("artifactId", "topdown-bake"); put("formatVersion", 1)
            put("mapRelease", variant.artifactId); put("kitVersion", "a".repeat(40)); put("kitId", "273d596")
            put("chunkSize", 256); put("undrawnTile", 65535); put("partial", partial)
            putObject("shape").apply { put("cols", 4); put("rows", 4) }
            putObject("inputFingerprint").apply {
                put("hanTilesSha256", TopdownMapArtifacts.sha256(sourceBytes.getValue("data/map/han-tiles.json")))
                put("worldJsonSha256", TopdownMapArtifacts.sha256(sourceBytes.getValue("infra/src/main/resources/map/han-world-v3.json")))
                put("roadsSha256", TopdownMapArtifacts.sha256(sourceBytes.getValue("data/map/han-land-roads-v1.json")))
                put("demSha256", "b".repeat(64))
                putObject("designJsonSha256").put("fixture.json", "c".repeat(64))
                putNull("region")
            }
        }
        val id = TopdownMapArtifacts.identityHash(manifest, mapper)
        manifest.put("bakeId", id)
        val bundle = root.resolve(id); Files.createDirectories(bundle.resolve("grid/L0"))
        val payloads = linkedMapOf(
            "grid/L0/0_0.bin.gz" to ByteArray(4 * 256 * 256),
            "grid/L2.bin.gz" to ByteArray(4),
            "places.json.gz" to "{\"provinceCount\":2,\"cities\":[]}".toByteArray(),
            "defects.json" to "{\"defects\":[]}".toByteArray(),
        )
        val chunks = manifest.putArray("chunks")
        val files = manifest.putArray("files")
        payloads.forEach { (path, raw) ->
            val bytes = if (path.endsWith(".gz")) ByteArrayOutputStream().let { output ->
                GZIPOutputStream(output).use { it.write(raw) }; output.toByteArray()
            } else raw
            Files.write(bundle.resolve(path), bytes)
            val entry = mapper.createObjectNode().apply {
                put("file", path); put("sha256", TopdownMapArtifacts.sha256(bytes)); put("bytes", bytes.size)
                put("rawSha256", TopdownMapArtifacts.sha256(raw))
            }
            when (path) {
                "grid/L0/0_0.bin.gz" -> chunks.add(entry.deepCopy().apply { put("cx", 0); put("cy", 0) })
                "grid/L2.bin.gz" -> manifest.set<com.fasterxml.jackson.databind.JsonNode>("overview", entry.apply {
                    put("cols", 1); put("rows", 1); put("block", 4)
                })
                "places.json.gz" -> manifest.set<com.fasterxml.jackson.databind.JsonNode>("places", entry)
                else -> manifest.set<com.fasterxml.jackson.databind.JsonNode>("defects", entry)
            }
            files.add(entry.deepCopy().apply { put("compression", if (path.endsWith(".gz")) "gzip" else "none") })
        }
        Files.write(bundle.resolve("manifest.json"), mapper.writeValueAsBytes(manifest))
        return Fixture(root, id, manifest)
    }

    private fun service(fixture: Fixture) = TopdownMapArtifacts(fixture.root.toString(), fixture.bakeId, mapper)
    private fun mvc(fixture: Fixture) = MockMvcBuilders.standaloneSetup(TopdownMapController(service(fixture)))
        .setPatternParser(PathPatternParser()).build()

    @Test
    fun `canonical identity matches Python's independently specified bytes`() {
        val node = mapper.readTree("""{"mapRelease":"fixture-map","kitVersion":"kit-pin","formatVersion":1,"inputFingerprint":{"z":2,"a":{"z":4,"a":3}}}""")
        val canonical = """{"formatVersion":1,"inputFingerprint":{"a":{"a":3,"z":4},"z":2},"kitVersion":"kit-pin","mapRelease":"fixture-map"}""".toByteArray()
        assertEquals(TopdownMapArtifacts.sha256(canonical), TopdownMapArtifacts.identityHash(node, mapper))
    }

    @Test
    fun `manifest and gzip file are immutable with exact ETag and gzip has no content encoding`() {
        val fixture = fixture(); val mvc = mvc(fixture)
        for (file in listOf("manifest.json", "grid/L0/0_0.bin.gz", "grid/L2.bin.gz", "places.json.gz", "defects.json")) {
            val bytes = Files.readAllBytes(fixture.root.resolve(fixture.bakeId).resolve(file))
            val tag = "\"sha256-${TopdownMapArtifacts.sha256(bytes)}\""
            mvc.perform(get("/api/map/topdown/${fixture.bakeId}/$file"))
                .andExpect(status().isOk).andExpect(content().bytes(bytes))
                .andExpect(header().string(HttpHeaders.ETAG, tag))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=31536000, public, immutable"))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_ENCODING))
                .andExpect(content().contentType(if (file.endsWith(".gz")) "application/octet-stream" else "application/json"))
            mvc.perform(get("/api/map/topdown/${fixture.bakeId}/$file").header(HttpHeaders.IF_NONE_MATCH, tag))
                .andExpect(status().isNotModified).andExpect(content().bytes(byteArrayOf()))
                .andExpect(header().string(HttpHeaders.ETAG, tag))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=31536000, public, immutable"))
        }
    }

    @Test
    fun `tampered bytes are rejected even when the old cached ETag is supplied`() {
        val fixture = fixture(); val file = "places.json.gz"
        val path = fixture.root.resolve(fixture.bakeId).resolve(file)
        val tag = "\"sha256-${TopdownMapArtifacts.sha256(Files.readAllBytes(path))}\""
        Files.write(path, byteArrayOf(8, 9))
        mvc(fixture).perform(get("/api/map/topdown/${fixture.bakeId}/$file").header(HttpHeaders.IF_NONE_MATCH, tag))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `refreshed transport hash cannot hide a wrong decoded raw hash`() {
        val fixture = fixture()
        val file = "places.json.gz"
        for (entry in listOf(fixture.manifest.path("places")) + fixture.manifest.path("files").filter { it.path("file").asText() == file }) {
            (entry as com.fasterxml.jackson.databind.node.ObjectNode).put("rawSha256", "0".repeat(64))
        }
        Files.write(fixture.root.resolve(fixture.bakeId).resolve("manifest.json"), mapper.writeValueAsBytes(fixture.manifest))
        assertNull(service(fixture).asset(fixture.bakeId, file))
    }

    @Test
    fun `paths outside manifest and bundle cannot be read`() {
        val fixture = fixture(); val service = service(fixture)
        for (file in listOf("../manifest.json", "grid/L0/../../manifest.json", "/etc/passwd", "secrets.json", "grid/L0/9_9.bin.gz")) {
            assertNull(service.asset(fixture.bakeId, file))
        }
        assertNull(service.asset("../escape", "manifest.json"))
        val outside = Files.createTempFile(testRoot, "outside-bundle", ".bin.gz")
        val path = fixture.root.resolve(fixture.bakeId).resolve("places.json.gz")
        Files.write(outside, Files.readAllBytes(path)); Files.delete(path); Files.createSymbolicLink(path, outside)
        assertNull(service.asset(fixture.bakeId, "places.json.gz"))
    }

    @Test
    fun `active world binding refuses another pin and a partial bake`() {
        val fixture = fixture(); val selected = selected()
        assertEquals(fixture.bakeId, service(fixture).binding(selected))
        Mockito.`when`(selected.artifactBytes("data/map/han-tiles.json")).thenReturn("reset-source".toByteArray())
        assertNull(service(fixture).binding(selected))
        val partial = fixture(partial = true)
        assertNull(service(partial).binding(selected()))
        assertNull(service(partial).asset(partial.bakeId, "manifest.json"))
    }

    @Test
    fun `manifest identity mutation and disabled selection cannot create a binding`() {
        val fixture = fixture()
        fixture.manifest.put("mapRelease", "another-map")
        Files.write(fixture.root.resolve(fixture.bakeId).resolve("manifest.json"), mapper.writeValueAsBytes(fixture.manifest))
        assertNull(service(fixture).asset(fixture.bakeId, "manifest.json"))
        assertNull(TopdownMapArtifacts(fixture.root.toString(), "", mapper).binding(selected()))
    }
}

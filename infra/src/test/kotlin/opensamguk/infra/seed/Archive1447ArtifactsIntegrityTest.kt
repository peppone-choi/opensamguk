package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class Archive1447ArtifactsIntegrityTest {
    @TempDir lateinit var temporary: Path

    @Test fun `new release loads placed gap counties and matching economy in both runtime paths`() {
        val release = Archive1447Artifacts.load(Path.of(".."))
        val previous = Archive1224Artifacts.load(Path.of("..")).cityConst.all().keys
        val cities = MapJson.loadCityDetails(release.artifactBytes(
            "infra/src/main/resources/map/han-world-v3.json").toString(Charsets.UTF_8)).associateBy { it.id }
        assertEquals(cities.keys, release.cityConst.all().keys)
        // 결손 縣 223곳만 새로 들어왔고, 1224 판의 城 정체성은 하나도 바뀌지 않았다.
        val added = cities.keys.filter { it !in previous }
        assertTrue(added.isNotEmpty())
        assertTrue(previous.all { it in cities.keys })
        for (id in added) {
            val raw = cities.getValue(id)
            val runtime = release.cityConst.byId(id)!!
            // 결손 縣은 다른 縣과 같은 등급표 경로(RawCity statScale 100)라 런타임은 백 단위 내림이다.
            // 1224 판이 더한 취락은 정확 배정(statScale 1)이라 같은 값이었다 — 경로가 다르다.
            assertEquals(raw.populationMax / 100 * 100, runtime.population)
            assertEquals(raw.agricultureMax / 100 * 100, runtime.agriculture)
            assertEquals(raw.commerceMax / 100 * 100, runtime.commerce)
            assertTrue(raw.populationInit!! > 0)
            assertTrue(raw.populationInit!! <= raw.populationMax)
        }
    }

    @Test fun `changed catalog and corrupt or missing blob never fall back to current files`() {
        val relative = "data/map/han-world-v3-1447-artifacts-v1"
        val source = Path.of("..").resolve(relative)
        val catalog = Files.readAllBytes(source.resolve("catalog.json"))
        val blob = ObjectMapper().readTree(catalog).path("files").first().path("blob").asText()
        for (mutation in listOf("catalog", "blob", "missing")) {
            val root = temporary.resolve(mutation)
            val destination = root.resolve(relative)
            Files.walk(source).use { paths -> paths.forEach { path ->
                val target = destination.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            } }
            Archive1447Artifacts.load(root) // Establish a valid fixture before introducing the fault.
            when (mutation) {
                "catalog" -> Files.write(destination.resolve("catalog.json"), catalog + byteArrayOf(10))
                "blob" -> Files.write(destination.resolve(blob), byteArrayOf(0, 1, 2))
                "missing" -> Files.delete(destination.resolve(blob))
            }
            if (mutation == "missing") {
                assertFailsWith<java.nio.file.NoSuchFileException> { Archive1447Artifacts.load(root) }
            } else {
                assertFailsWith<IllegalArgumentException>(mutation) { Archive1447Artifacts.load(root) }
            }
        }
    }
}

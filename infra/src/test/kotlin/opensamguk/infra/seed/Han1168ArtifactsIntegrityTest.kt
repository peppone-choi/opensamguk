package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class Han1168ArtifactsIntegrityTest {
    @TempDir lateinit var temporary: Path

    @Test fun `new release loads expanded settlements and exact economy in both runtime paths`() {
        val release = Han1168Artifacts.load(Path.of(".."))
        val cities = MapJson.loadCityDetails(release.artifactBytes(
            "infra/src/main/resources/map/han-world-v3.json").toString(Charsets.UTF_8)).associateBy { it.id }
        assertEquals(cities.keys, release.cityConst.all().keys)
        for (id in cities.keys.filter { it >= 1134 }) {
            val raw = cities.getValue(id)
            val runtime = release.cityConst.byId(id)!!
            assertEquals(raw.populationMax, runtime.population)
            assertEquals(raw.agricultureMax, runtime.agriculture)
            assertEquals(raw.commerceMax, runtime.commerce)
            assertTrue(raw.populationInit!! > 0)
            assertTrue(raw.populationInit!! <= raw.populationMax)
        }
    }

    @Test fun `changed catalog and corrupt or missing blob never fall back to current files`() {
        val relative = "data/map/han-world-v3-1168-artifacts-v1"
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
            Han1168Artifacts.load(root) // Establish a valid fixture before introducing the fault.
            when (mutation) {
                "catalog" -> Files.write(destination.resolve("catalog.json"), catalog + byteArrayOf(10))
                "blob" -> Files.write(destination.resolve(blob), byteArrayOf(0, 1, 2))
                "missing" -> Files.delete(destination.resolve(blob))
            }
            if (mutation == "missing") {
                assertFailsWith<java.nio.file.NoSuchFileException> { Han1168Artifacts.load(root) }
            } else {
                assertFailsWith<IllegalArgumentException>(mutation) { Han1168Artifacts.load(root) }
            }
        }
    }
}

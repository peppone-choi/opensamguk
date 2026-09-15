package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class Han1097ArtifactsIntegrityTest {
    @TempDir lateinit var temporary: Path

    @Test fun `changed catalog and corrupt or missing blob never fall back to current files`() {
        val relative = "data/map/han-world-v3-1097-artifacts-v1"
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
            Han1097Artifacts.load(root) // Establish a valid fixture before introducing the fault.
            when (mutation) {
                "catalog" -> Files.write(destination.resolve("catalog.json"), catalog + byteArrayOf(10))
                "blob" -> Files.write(destination.resolve(blob), byteArrayOf(0, 1, 2))
                "missing" -> Files.delete(destination.resolve(blob))
            }
            if (mutation == "missing") {
                assertFailsWith<java.nio.file.NoSuchFileException> { Han1097Artifacts.load(root) }
            } else {
                assertFailsWith<IllegalArgumentException>(mutation) { Han1097Artifacts.load(root) }
            }
        }
    }
}

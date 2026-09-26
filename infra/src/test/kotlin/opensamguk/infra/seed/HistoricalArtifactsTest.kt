package opensamguk.infra.seed

import java.nio.file.Path
import java.nio.file.Files
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class HistoricalArtifactsTest {
    @TempDir lateinit var temporary: Path
    private val root = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun `frozen 832 and 835 inputs load with their own complete route identities`() {
        val old = HistoricalArtifacts.loadFromDirectory(root, "han-world-v3-832")
        val current = HistoricalArtifacts.loadFromDirectory(root, "han-world-v3-835")
        assertEquals((1..832).toSet(), old.bindingsByCityId.keys)
        assertEquals((1..835).toSet(), current.bindingsByCityId.keys)
        for ((id, binding) in old.bindingsByCityId) {
            assertEquals(binding, current.bindingsByCityId[id])
        }
        assertNotEquals(old.topology.contentHash, current.topology.contentHash)
        assertEquals(1520, old.topology.landProvinceIds.size)
    }

    @Test
    fun `modified catalog bytes cannot authorize a different archive`() {
        val directory = temporary.resolve("data/map/han-world-artifacts-v1")
        Files.createDirectories(directory)
        val original = Files.readAllBytes(root.resolve("data/map/han-world-artifacts-v1/catalog.json"))
        Files.write(directory.resolve("catalog.json"), original + byteArrayOf(32))
        val failure = assertFailsWith<IllegalArgumentException> {
            HistoricalArtifacts.loadFromDirectory(temporary, "han-world-v3-832")
        }
        assertContains(failure.message.orEmpty(), "catalog hash mismatch")
    }

    @Test
    fun `valid catalog cannot hide a corrupted historical blob`() {
        val directory = temporary.resolve("data/map/han-world-artifacts-v1")
        Files.createDirectories(directory.resolve("blobs"))
        val original = Files.readAllBytes(root.resolve("data/map/han-world-artifacts-v1/catalog.json"))
        Files.write(directory.resolve("catalog.json"), original)
        val firstBlob = ObjectMapper().readTree(original).path("variants")[0].path("files")[0].path("blob").asText()
        Files.write(directory.resolve(firstBlob), "corrupted".toByteArray())
        val failure = assertFailsWith<IllegalArgumentException> {
            HistoricalArtifacts.loadFromDirectory(temporary, "han-world-v3-832")
        }
        assertContains(failure.message.orEmpty(), "blob hash/length mismatch")
    }

    @Test
    fun `unknown version and missing archive never fall back to current assets`() {
        assertFailsWith<IllegalArgumentException> {
            HistoricalArtifacts.loadFromDirectory(root, "han-world-v3")
        }
        assertFailsWith<IllegalArgumentException> {
            HistoricalArtifacts.loadFromDirectory(root.resolve("missing-archive-root"), "han-world-v3-832")
        }
    }
}

package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class HanRuntimeConstantsIntegrityTest {
    @Test fun `frozen Kotlin constants match approved manifest and original source byte pins`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val raw = Files.readAllBytes(root.resolve("data/map/han-world-artifacts-v1/runtime-constants.json"))
        assertEquals("134e634bbff9279cdf98babbe2954ee9f798bc7239cc94dd4008f60f26037332", sha(raw))
        val files = ObjectMapper().readTree(raw).path("files").toList()
        assertEquals(4, files.size)
        for (entry in files) {
            val destination = Path.of(entry.path("path").asText())
            val source = Path.of(entry.path("sourcePath").asText())
            val bytes = Files.readAllBytes(root.resolve(destination))
            assertEquals(entry.path("sha256").asText(), sha(bytes), destination.toString())
            val originalName = source.fileName.toString().removeSuffix(".kt")
            val frozenName = destination.fileName.toString().removeSuffix(".kt")
            val restored = bytes.toString(Charsets.UTF_8).split("\n").drop(3).joinToString("\n")
                .replace("object $frozenName {", "object $originalName {").toByteArray(Charsets.UTF_8)
            assertEquals(entry.path("sourceSha256").asText(), sha(restored), "Source drift: $destination")
        }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

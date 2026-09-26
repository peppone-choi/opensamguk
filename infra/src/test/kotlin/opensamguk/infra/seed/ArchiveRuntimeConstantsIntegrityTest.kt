package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class ArchiveRuntimeConstantsIntegrityTest {
    @Test fun `frozen Kotlin constants match approved manifest and original source byte pins`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val raw = Files.readAllBytes(root.resolve("data/map/han-world-artifacts-v1/runtime-constants.json"))
        assertEquals("cd6579c680db934f4e1d1f53cd484335893de8e078f8501cf471b4d05202d553", sha(raw))
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
    @Test fun `846 frozen constants preserve approved snapshot and original source identity`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val raw = Files.readAllBytes(root.resolve("data/map/han-world-v3-846-artifacts-v1/runtime-constants.json"))
        assertEquals("f368fe0276b1205d07aa45b4c67b9c46e9749b8945d0e634108ffb307de0d380", sha(raw))
        val files = ObjectMapper().readTree(raw).path("files").toList()
        assertEquals(2, files.size)
        for (entry in files) {
            val path = Path.of(entry.path("snapshot").asText())
            val bytes = Files.readAllBytes(root.resolve(path))
            assertEquals(entry.path("snapshotSha256").asText(), sha(bytes))
            val originalName = Path.of(entry.path("source").asText()).fileName.toString().removeSuffix(".kt")
            val frozenName = path.fileName.toString().removeSuffix(".kt")
            val restored = bytes.toString(Charsets.UTF_8).substringAfter("\n")
                .replace(frozenName, originalName).toByteArray(Charsets.UTF_8)
            assertEquals(entry.path("sourceSha256").asText(), sha(restored))
        }
    }
    @Test fun `848 frozen constants preserve approved snapshot and original source identity`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val raw = Files.readAllBytes(root.resolve("data/map/han-world-v3-848-artifacts-v1/runtime-constants.json"))
        assertEquals("3cb286722fedee827ac08bac1e7def58e6a1eb531f70461064dfd006d3abd341", sha(raw))
        val files = ObjectMapper().readTree(raw).path("files").toList()
        assertEquals(2, files.size)
        for (entry in files) {
            val path = Path.of(entry.path("snapshot").asText())
            val bytes = Files.readAllBytes(root.resolve(path))
            assertEquals(entry.path("snapshotSha256").asText(), sha(bytes))
            val originalName = Path.of(entry.path("source").asText()).fileName.toString().removeSuffix(".kt")
            val frozenName = path.fileName.toString().removeSuffix(".kt")
            val restored = bytes.toString(Charsets.UTF_8).substringAfter("\n")
                .replace(frozenName, originalName).toByteArray(Charsets.UTF_8)
            assertEquals(entry.path("sourceSha256").asText(), sha(restored))
        }
    }
    @Test fun `1098 frozen constants preserve approved snapshot and original source identity`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val raw = Files.readAllBytes(root.resolve("data/map/han-world-v3-1098-artifacts-v1/runtime-constants.json"))
        assertEquals("f407ed1cead207f942237607a2c1ea175e7de21127f954380d15b3b54954f809", sha(raw))
        val files = ObjectMapper().readTree(raw).path("files").toList()
        assertEquals(2, files.size)
        for (entry in files) {
            val path = Path.of(entry.path("snapshot").asText())
            val bytes = Files.readAllBytes(root.resolve(path))
            assertEquals(entry.path("snapshotSha256").asText(), sha(bytes))
            val originalName = Path.of(entry.path("source").asText()).fileName.toString().removeSuffix(".kt")
            val frozenName = path.fileName.toString().removeSuffix(".kt")
            val restored = bytes.toString(Charsets.UTF_8).substringAfter("\n")
                .replace(frozenName, originalName).toByteArray(Charsets.UTF_8)
            assertEquals(entry.path("sourceSha256").asText(), sha(restored))
        }
    }
    @Test fun `1133 frozen constants preserve approved snapshot and original source identity`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val raw = Files.readAllBytes(root.resolve("data/map/han-world-v3-1133-artifacts-v1/runtime-constants.json"))
        assertEquals("76ced0869db263ef9063167a1a2016e9a8eeb4a6c75a798edb021257eecfc40c", sha(raw))
        val files = ObjectMapper().readTree(raw).path("files").toList()
        assertEquals(2, files.size)
        for (entry in files) {
            val path = Path.of(entry.path("snapshot").asText())
            val bytes = Files.readAllBytes(root.resolve(path))
            assertEquals(entry.path("snapshotSha256").asText(), sha(bytes))
            val originalName = Path.of(entry.path("source").asText()).fileName.toString().removeSuffix(".kt")
            val frozenName = path.fileName.toString().removeSuffix(".kt")
            val restored = bytes.toString(Charsets.UTF_8).substringAfter("\n")
                .replace(frozenName, originalName).toByteArray(Charsets.UTF_8)
            assertEquals(entry.path("sourceSha256").asText(), sha(restored))
        }
    }
    @Test fun `1141 frozen constants preserve approved snapshot and original source identity`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val raw = Files.readAllBytes(root.resolve("data/map/han-world-v3-1141-artifacts-v1/runtime-constants.json"))
        assertEquals("67ab21fed8d034d17d49f0068329ddef18a6c590e3ba64acad5900d91778724e", sha(raw))
        val files = ObjectMapper().readTree(raw).path("files").toList()
        assertEquals(2, files.size)
        for (entry in files) {
            val path = Path.of(entry.path("snapshot").asText())
            val bytes = Files.readAllBytes(root.resolve(path))
            assertEquals(entry.path("snapshotSha256").asText(), sha(bytes))
            val originalName = Path.of(entry.path("source").asText()).fileName.toString().removeSuffix(".kt")
            val frozenName = path.fileName.toString().removeSuffix(".kt")
            val restored = bytes.toString(Charsets.UTF_8).substringAfter("\n")
                .replace(frozenName, originalName).toByteArray(Charsets.UTF_8)
            assertEquals(entry.path("sourceSha256").asText(), sha(restored))
        }
    }
    @Test fun `1341 frozen constants preserve approved snapshot and original source identity`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val raw = Files.readAllBytes(root.resolve("data/map/han-world-v3-1341-artifacts-v1/runtime-constants.json"))
        assertEquals("5c5dee6ae948eca9f35de55a2fc90889cb0f46804a8f5d7e75156c7042e342ec", sha(raw))
        val files = ObjectMapper().readTree(raw).path("files").toList()
        assertEquals(2, files.size)
        for (entry in files) {
            val path = Path.of(entry.path("snapshot").asText())
            val bytes = Files.readAllBytes(root.resolve(path))
            assertEquals(entry.path("snapshotSha256").asText(), sha(bytes))
            val originalName = Path.of(entry.path("source").asText()).fileName.toString().removeSuffix(".kt")
            val frozenName = path.fileName.toString().removeSuffix(".kt")
            val restored = bytes.toString(Charsets.UTF_8).substringAfter("\n")
                .replace(frozenName, originalName).toByteArray(Charsets.UTF_8)
            assertEquals(entry.path("sourceSha256").asText(), sha(restored))
        }
    }
    @Test fun `1194 frozen constants preserve approved snapshot and original source identity`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val raw = Files.readAllBytes(root.resolve("data/map/han-world-v3-1194-artifacts-v1/runtime-constants.json"))
        assertEquals("df18936b76b388278275c73283faf0f18c3cffc4fbdea24c83516ea2d9a22f87", sha(raw))
        val files = ObjectMapper().readTree(raw).path("files").toList()
        assertEquals(2, files.size)
        for (entry in files) {
            val path = Path.of(entry.path("snapshot").asText())
            val bytes = Files.readAllBytes(root.resolve(path))
            assertEquals(entry.path("snapshotSha256").asText(), sha(bytes))
            val originalName = Path.of(entry.path("source").asText()).fileName.toString().removeSuffix(".kt")
            val frozenName = path.fileName.toString().removeSuffix(".kt")
            val restored = bytes.toString(Charsets.UTF_8).substringAfter("\n")
                .replace(frozenName, originalName).toByteArray(Charsets.UTF_8)
            assertEquals(entry.path("sourceSha256").asText(), sha(restored))
        }
    }
    @Test fun `1168 frozen constants preserve approved snapshot and original source identity`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val raw = Files.readAllBytes(root.resolve("data/map/han-world-v3-1168-artifacts-v1/runtime-constants.json"))
        assertEquals("d46b692a89dfce381dce58eda4ae3fd8fd842ea769e6252d6b66f0527495ccf1", sha(raw))
        val files = ObjectMapper().readTree(raw).path("files").toList()
        assertEquals(2, files.size)
        for (entry in files) {
            val path = Path.of(entry.path("snapshot").asText())
            val bytes = Files.readAllBytes(root.resolve(path))
            assertEquals(entry.path("snapshotSha256").asText(), sha(bytes))
            val originalName = Path.of(entry.path("source").asText()).fileName.toString().removeSuffix(".kt")
            val frozenName = path.fileName.toString().removeSuffix(".kt")
            val restored = bytes.toString(Charsets.UTF_8).substringAfter("\n")
                .replace(frozenName, originalName).toByteArray(Charsets.UTF_8)
            assertEquals(entry.path("sourceSha256").asText(), sha(restored))
        }
    }
    @Test fun `1224 frozen constants preserve approved snapshot and original source identity`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val raw = Files.readAllBytes(root.resolve("data/map/han-world-v3-1224-artifacts-v1/runtime-constants.json"))
        assertEquals("660965d2569028a2b3fcef7d2e3bae68b1d79d9a7fd12e955966bcb67b88fb48", sha(raw))
        val files = ObjectMapper().readTree(raw).path("files").toList()
        assertEquals(2, files.size)
        for (entry in files) {
            val path = Path.of(entry.path("snapshot").asText())
            val bytes = Files.readAllBytes(root.resolve(path))
            assertEquals(entry.path("snapshotSha256").asText(), sha(bytes))
            val originalName = Path.of(entry.path("source").asText()).fileName.toString().removeSuffix(".kt")
            val frozenName = path.fileName.toString().removeSuffix(".kt")
            val restored = bytes.toString(Charsets.UTF_8).substringAfter("\n")
                .replace(frozenName, originalName).toByteArray(Charsets.UTF_8)
            assertEquals(entry.path("sourceSha256").asText(), sha(restored))
        }
    }
    @Test fun `1447 frozen constants preserve approved snapshot and original source identity`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val raw = Files.readAllBytes(root.resolve("data/map/han-world-v3-1447-artifacts-v1/runtime-constants.json"))
        assertEquals("47fbfea012b6c609dddfefd5ea9e80cac950991a916309bc450e3dffb04a46dc", sha(raw))
        val files = ObjectMapper().readTree(raw).path("files").toList()
        assertEquals(2, files.size)
        for (entry in files) {
            val path = Path.of(entry.path("snapshot").asText())
            val bytes = Files.readAllBytes(root.resolve(path))
            assertEquals(entry.path("snapshotSha256").asText(), sha(bytes))
            val originalName = Path.of(entry.path("source").asText()).fileName.toString().removeSuffix(".kt")
            val frozenName = path.fileName.toString().removeSuffix(".kt")
            val restored = bytes.toString(Charsets.UTF_8).substringAfter("\n")
                .replace(frozenName, originalName).toByteArray(Charsets.UTF_8)
            assertEquals(entry.path("sourceSha256").asText(), sha(restored))
        }
    }
    @Test fun `1447 map4 frozen constants match the reviewed source and snapshots`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val raw = Files.readAllBytes(root.resolve("data/map/han-world-v3-1447-map4-artifacts-v1/runtime-constants.json"))
        assertEquals("a6267b0d342dd9915d39ec993855a1730b5c26f56bd951dd6ae23fd31524cfa5", sha(raw))
        val files = ObjectMapper().readTree(raw).path("files").toList()
        assertEquals(2, files.size)
        for (entry in files) {
            val path = Path.of(entry.path("snapshot").asText())
            val bytes = Files.readAllBytes(root.resolve(path))
            assertEquals(entry.path("snapshotSha256").asText(), sha(bytes))
            val originalName = Path.of(entry.path("source").asText()).fileName.toString().removeSuffix(".kt")
            val frozenName = path.fileName.toString().removeSuffix(".kt")
            val restored = bytes.toString(Charsets.UTF_8).substringAfter("\n")
                .replace(frozenName, originalName).toByteArray(Charsets.UTF_8)
            assertEquals(entry.path("sourceSha256").asText(), sha(restored))
        }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

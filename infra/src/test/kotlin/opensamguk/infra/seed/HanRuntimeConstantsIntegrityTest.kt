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
    @Test fun `846 frozen constants preserve approved snapshot and original source identity`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val raw = Files.readAllBytes(root.resolve("data/map/han-world-v3-846-artifacts-v1/runtime-constants.json"))
        assertEquals("a6b218a4eee186d01036607750c856782788614eff2d16c7033ded6e3a033e5a", sha(raw))
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
        assertEquals("5c34c74e9a88822cf8505cfe9d1386500dee90e7a2b115431e36a7c233217c4a", sha(raw))
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
        assertEquals("49e5be4c2a60ed3b4be8810eddc34a7f38fc485714a8cdf5cadde377f3e78ef2", sha(raw))
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
        assertEquals("fa2e424e43c64f2a5d09a80c3a0589b963bd940c1bad273fa95d59d96370daac", sha(raw))
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
        assertEquals("e984c04fe93a2c801634d6c7c4496c67b26588f811f59d5f91c11e3563199eff", sha(raw))
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
        assertEquals("469640d26505b34b161eb25f3e5f2ce40fa4a7995164fec198d9b4f2beea0241", sha(raw))
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
        assertEquals("a33e67fdd7623c0eadfdb1aefc2caf4092c7d49a3999757bfcc76920d8688a2b", sha(raw))
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
        assertEquals("825b6fe1722699424b18cde3f7fff9ac6449521ba089a2d1be171cc5d684230c", sha(raw))
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
        assertEquals("fbf2d20a76016a3539b457ee498ca697482dd88b4b41d684e683272167ece970", sha(raw))
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
        assertEquals("4ed0d4b9156df27f2b42837821249ed2580c0d38afda6735ceb4f05e86dc3666", sha(raw))
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

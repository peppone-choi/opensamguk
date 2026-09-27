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
        assertEquals("50c59f1ae09dd297ec95f4a3209532f2abe3afaed022f17ee9d9f5f5537ae14a", sha(raw))
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
        assertEquals("434c9e04569aefa5dde9f805faea822e4beaf7e61438c8894cf27d9d23efdfac", sha(raw))
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
        assertEquals("0c9a09832b15bf9b3455af30f31fff3426422e0a5d3e0ea556b622082e78d3ba", sha(raw))
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
        assertEquals("aaf495fe64f495a1f7be98ddc1091523c8b6eebc6822064b4b74aec8dd84a1fa", sha(raw))
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
        assertEquals("1ad9994f94e0c44734315a7e60f036d88e060b6b35088e22672333bc0d45da12", sha(raw))
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
        assertEquals("e91219ff1e604f1d3a50f8690fcea022b9e37d8d61116a09f247da1301d7547e", sha(raw))
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
        assertEquals("b46344ef7ea88ed84753f32353c7c00deaf44325a32d69ea9c8cbdf9602493e9", sha(raw))
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
        assertEquals("7d9ebeede16888351adf280202f99bedd2cec3bb3dd115fc0c06d56899f536f2", sha(raw))
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
        assertEquals("b8e2e4eb20203b32c54c090d852977dbc00996619f841eb8f13fecf05c1700df", sha(raw))
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
        assertEquals("970af6cace93d4e6f3474574a1fc74644c87cbdb6847da7ddccbb42cc667fe4a", sha(raw))
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
        assertEquals("3724cedc4453e023192fbbb1d5da8db0a31daa2731b427ec3708813abc6ff4c5", sha(raw))
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
        assertEquals("99d5db37a9d506081d03300686af25c98a12d8863a5cd0fb25633b17997ae54f", sha(raw))
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

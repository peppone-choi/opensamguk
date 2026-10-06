package opensamguk.engine.boot

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.infra.seed.SelectedSourceUnavailable
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class D101SeedInstallationOriginalTest {
    private val mapper = ObjectMapper()
    private val top = linkedMapOf<String, Any>(
        "schemaVersion" to 1, "kind" to "D101_SEED_INSTALLATION_V1",
        "originalOp" to "a".repeat(32), "approvalIntentSha256" to "b".repeat(64),
        "typedTargetFingerprint" to "c".repeat(64), "appSourceSha" to "d".repeat(40),
        "imagePins" to emptyMap<String, String>(), "producerIdentity" to "root",
        "rootTrust" to emptyMap<String, String>(), "approvedMaterial" to emptyMap<String, String>(),
        "selectedEnvelope" to emptyMap<String, String>(), "configurationOriginal" to emptyMap<String, String>(),
        "resolverDecision" to emptyMap<String, String>(), "typedTargetOriginal" to emptyMap<String, String>(),
        "runtimeClasses" to emptyMap<String, String>(), "artifactsRoot" to "/app",
        "database" to emptyMap<String, String>(),
    )

    @Test
    fun `installed top-level transport rejects unknown and duplicate fields`() {
        val canonical = mapper.writeValueAsString(top)
        assertEquals(top.keys, D101SeedInstallationOriginal
            .parseInstallationDocument(canonical.toByteArray()).fieldNames().asSequence().toSet())
        assertFailsWith<SelectedSourceUnavailable> {
            D101SeedInstallationOriginal.parseInstallationDocument(
                mapper.writeValueAsBytes(top + ("unexpected" to true)))
        }
        val duplicate = canonical.replaceFirst(
            "\"kind\":\"D101_SEED_INSTALLATION_V1\"",
            "\"kind\":\"D101_SEED_INSTALLATION_V1\",\"kind\":\"D101_SEED_INSTALLATION_V1\"",
        )
        assertFalse(duplicate == canonical)
        assertFailsWith<Exception> {
            D101SeedInstallationOriginal.parseInstallationDocument(duplicate.toByteArray())
        }
    }

    @Test
    fun `Root filePath original is bound to its fixed name and SHA`() {
        val original = "approved signed query original".toByteArray()
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original))
        fun ref(vararg entries: Pair<String, String>) = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
            mapOf(*entries))
        val fixed = Path.of("/run/d101/seed-approval.json")
        val good = ref("filePath" to fixed.toString(), "sha256" to digest)
        assertContentEquals(original, D101SeedInstallationOriginal.readBoundOriginal(
            good, "seed-approval.json", 128 * 1024) { path, limit ->
            assertEquals(fixed, path)
            assertEquals(128 * 1024, limit)
            original
        })
        assertFailsWith<SelectedSourceUnavailable> {
            D101SeedInstallationOriginal.readBoundOriginal(
                ref("path" to fixed.toString(), "sha256" to digest),
                "seed-approval.json", 128 * 1024) { _, _ -> original }
        }
        var opened = false
        assertFailsWith<SelectedSourceUnavailable> {
            D101SeedInstallationOriginal.readBoundOriginal(
                ref("filePath" to "/run/d101/selected-envelope.json", "sha256" to digest),
                "seed-approval.json", 128 * 1024) { _, _ -> opened = true; original }
        }
        assertFalse(opened)
        assertFailsWith<SelectedSourceUnavailable> {
            D101SeedInstallationOriginal.readBoundOriginal(
                ref("filePath" to fixed.toString(), "sha256" to "0".repeat(64)),
                "seed-approval.json", 128 * 1024) { _, _ -> original }
        }
    }
}

package opensamguk.engine.boot

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.infra.seed.SelectedSourceUnavailable
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class D101PreIntentUnsignedSinkTest {
    private val mapper = ObjectMapper()
    private val options = mapOf("SERVER_NAME" to "빼섭", "SERVER_GENERATION" to "0", "SCENARIO_CODE" to "scenario_3190",
        "SCENARIO_SEED_ENABLED" to "true", "SCENARIO_LOOKUP_DIR" to "", "RESET_MAXGENERAL" to "50", "RESET_FIRST_TURN" to "immediate",
        "RESET_EXTEND" to "1", "RESET_TURNTERM" to "60", "RESET_BLOCK_GENERAL_CREATE" to "1", "RESET_NPCMODE" to "0",
        "RESET_SHOW_IMG_LEVEL" to "3", "RESET_FICTION" to "1")
    private val images = setOf("game-api", "game-engine", "web-game", "game-postgres", "game-redis")
        .associateWith { "sha256:" + "a".repeat(64) }

    private fun fixture(): Pair<ByteArray, Map<String, ByteArray>> {
        val target = mapper.writeValueAsBytes(mapOf("id" to "pep", "target" to mapOf("generation" to 0, "scenarioCode" to "scenario_3190",
            "scenarioSeedEnabled" to true, "updates" to options, "imageDigests" to images.filterKeys { it !in setOf("game-postgres", "game-redis") },
            "storageImageDigests" to images.filterKeys { it in setOf("game-postgres", "game-redis") })))
        val profile = mapper.writeValueAsBytes(mapOf("schemaVersion" to 1, "kind" to "D101_PRE_INTENT_SOURCE_PINS_V1", "originalOp" to "b".repeat(32),
            "typedTargetFingerprint" to d101PreIntentSha(target), "appSourceSha" to "c".repeat(40), "imagePins" to images, "artifactsRoot" to "/app",
            "preIntentInstallationSha256" to "d".repeat(64), "nativeHelperPath" to D101PreIntentSourceProfile.HELPER_PATH,
            "nativeHelperSha256" to "e".repeat(64), "producerIdentity" to "root-selected-source"))
        val config = mapper.writeValueAsBytes(mapOf("schemaVersion" to 1, "kind" to "D101_EFFECTIVE_SEED_INPUTS_V1", "originalOp" to "b".repeat(32),
            "typedTargetFingerprint" to d101PreIntentSha(target), "appSourceSha" to "c".repeat(40), "imagePins" to images,
            "rawInputs" to (options + ("RESET_MAXGENERAL" to " 050 "))))
        return profile to mapOf("configuration" to config, "typedTarget" to target)
    }

    @Test
    fun `unsigned whole originals preserve exact config and same snapshot facts`() {
        val (profileBytes, originals) = fixture()
        val snapshot = D101PreIntentSelectedCapture(D101PreIntentInputsSource { D101PreIntentFixedInputs(options, Path.of("../..")) }).capture()
        val clock = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC)
        val capture = D101PreIntentUnsignedSink.assemble(D101PreIntentSourceProfile.decode(profileBytes), originals, snapshot, clock)
        val raw = capture.originals()
        val manifest = mapper.readTree(capture.manifest())
        assertEquals(9, manifest.size())
        assertEquals(14 + snapshot.world.topologyOriginals().size, raw.size)
        assertContentEquals(originals.getValue("configuration"), raw.getValue("configuration"))
        assertEquals(" 050 ", mapper.readTree(raw.getValue("configuration"))["rawInputs"]["RESET_MAXGENERAL"].textValue())
        assertEquals("50", mapper.readTree(raw.getValue("parsedOptions"))["effectiveOptions"]["RESET_MAXGENERAL"].textValue())
        assertEquals(6, mapper.readTree(raw.getValue("selectedWorld")).size())
        assertEquals(4, mapper.readTree(raw.getValue("parsedOptions")).size())
        val facts = mapper.readTree(raw.getValue("captureFacts"))
        assertEquals(23, facts.size())
        assertEquals(d101PreIntentSha(raw.getValue("configuration")), facts["configurationSha256"].textValue())
        assertEquals(d101PreIntentSha(raw.getValue("resolverDecision")), facts["resolverDecisionReceiptSha256"].textValue())
        for ((id, wire) in raw) {
            assertEquals(d101PreIntentSha(wire), manifest["originals"][id]["rawSha256"].textValue())
            assertEquals(wire.size.toLong(), manifest["originals"][id]["byteLength"].longValue())
        }
        assertEquals("application/octet-stream", manifest["originals"]["topologyCanonical"]["mediaType"].textValue())
        raw.getValue("configuration")[0] = 0
        assertNotEquals(0.toByte(), capture.originals().getValue("configuration")[0])
        assertContentEquals(snapshot.world.topologyOriginals().getValue("dryLandProjectionPolicy"), capture.originals().getValue("topology-input:dryLandProjectionPolicy"))
    }

    @Test
    fun `unsigned assembler refuses substituted target config and raw snapshot`() {
        val (bytes, originals) = fixture()
        val profile = D101PreIntentSourceProfile.decode(bytes)
        val snapshot = D101PreIntentSelectedCapture(D101PreIntentInputsSource { D101PreIntentFixedInputs(options, Path.of("../..")) }).capture()
        assertFailsWith<SelectedSourceUnavailable> { D101PreIntentUnsignedSink.assemble(profile, originals - "configuration", snapshot) }
        assertFailsWith<SelectedSourceUnavailable> { D101PreIntentUnsignedSink.assemble(profile, originals + ("typedTarget" to "{}".toByteArray()), snapshot) }
        val altered = mapper.readTree(originals.getValue("configuration")) as com.fasterxml.jackson.databind.node.ObjectNode
        (altered["rawInputs"] as com.fasterxml.jackson.databind.node.ObjectNode).put("RESET_MAXGENERAL", "51")
        assertFailsWith<SelectedSourceUnavailable> { D101PreIntentUnsignedSink.assemble(profile, originals + ("configuration" to mapper.writeValueAsBytes(altered)), snapshot) }
        val substituted = D101PreIntentSelectedSnapshot(snapshot.inputs, snapshot.world, snapshot.rawOriginals() + ("world.json" to "{}".toByteArray()),
            snapshot.parserClassOriginal(), snapshot.topologyRootClassOriginal(), snapshot.effectiveOptions())
        assertFailsWith<SelectedSourceUnavailable> { D101PreIntentUnsignedSink.assemble(profile, originals, substituted) }
    }

    @Test
    fun `fixed profile rejects caller paths aliases duplicates and missing pins`() {
        val (bytes, _) = fixture()
        assertEquals(Path.of("/app"), D101PreIntentSourceProfile.decode(bytes).scope.artifactsRoot)
        for ((key, value) in mapOf("nativeHelperPath" to "/caller/helper", "artifactsRoot" to "/caller/root", "kind" to "D101_PRE_INTENT_SOURCE_V1", "producerIdentity" to "")) {
            val altered = mapper.readTree(bytes) as com.fasterxml.jackson.databind.node.ObjectNode
            altered.put(key, value)
            assertFailsWith<SelectedSourceUnavailable> { D101PreIntentSourceProfile.decode(mapper.writeValueAsBytes(altered)) }
        }
        val removed = mapper.readTree(bytes) as com.fasterxml.jackson.databind.node.ObjectNode
        removed.remove("nativeHelperSha256")
        assertFailsWith<SelectedSourceUnavailable> { D101PreIntentSourceProfile.decode(mapper.writeValueAsBytes(removed)) }
        val duplicate = String(bytes, Charsets.UTF_8).dropLast(1) + ",\"schemaVersion\":1}"
        assertFailsWith<SelectedSourceUnavailable> { D101PreIntentSourceProfile.decode(duplicate.toByteArray()) }
        assertFailsWith<SelectedSourceUnavailable> { D101PreIntentSourceProfile.decode(bytes + "{}".toByteArray()) }
    }
}

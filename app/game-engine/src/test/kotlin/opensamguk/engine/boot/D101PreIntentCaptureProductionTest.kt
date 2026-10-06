package opensamguk.engine.boot

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.infra.seed.SelectedSourceUnavailable
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class D101PreIntentCaptureProductionTest {
    private val mapper = ObjectMapper()
    private val op = "b".repeat(32)
    private val app = "c".repeat(40)
    private val pins = setOf("game-api", "game-engine", "web-game", "game-postgres", "game-redis")
        .associateWith { "sha256:" + "a".repeat(64) }
    private val options = mapOf(
        "SERVER_NAME" to "빼섭", "SERVER_GENERATION" to "0",
        "SCENARIO_CODE" to "scenario_3190", "SCENARIO_SEED_ENABLED" to "true",
        "SCENARIO_LOOKUP_DIR" to "", "RESET_MAXGENERAL" to "50",
        "RESET_FIRST_TURN" to "immediate", "RESET_EXTEND" to "1",
        "RESET_TURNTERM" to "60", "RESET_BLOCK_GENERAL_CREATE" to "1",
        "RESET_NPCMODE" to "0", "RESET_SHOW_IMG_LEVEL" to "3", "RESET_FICTION" to "1",
    )

    private fun syntheticNativeSource(root: Path): D101NativePreIntentInputsSource {
        val targetBytes = mapper.writeValueAsBytes(mapOf("id" to "pep", "target" to mapOf(
            "storageImageDigests" to pins.filterKeys { it in setOf("game-postgres", "game-redis") },
            "scenarioCode" to "scenario_3190", "generation" to 0, "scenarioSeedEnabled" to true,
            "updates" to options, "imageDigests" to pins.filterKeys { it in setOf("game-api", "game-engine", "web-game") },
        )))
        val targetSha = hash(targetBytes)
        val configBytes = mapper.writeValueAsBytes(mapOf(
            "schemaVersion" to 1, "kind" to "D101_EFFECTIVE_SEED_INPUTS_V1", "originalOp" to op,
            "typedTargetFingerprint" to targetSha, "appSourceSha" to app, "imagePins" to pins,
            "rawInputs" to options,
        ))
        val manifestBytes = mapper.writeValueAsBytes(mapOf(
            "schemaVersion" to 1, "kind" to "D101_PRE_INTENT_INPUT_BINDINGS_V1", "originalOp" to op,
            "typedTargetFingerprint" to targetSha, "appSourceSha" to app, "imagePins" to pins,
            "configurationOriginal" to mapOf("rawSha256" to hash(configBytes), "byteLength" to configBytes.size),
            "typedTargetOriginal" to mapOf("rawSha256" to targetSha, "byteLength" to targetBytes.size),
            "artifactsRoot" to root.toString(),
        ))
        val manifestSha = hash(manifestBytes)
        val response = mapper.writeValueAsBytes(mapOf(
            "schemaVersion" to 1, "preIntentInstallationSha256" to manifestSha,
            "preIntentInstallationBytesBase64url" to b64(manifestBytes),
            "configurationBytesBase64url" to b64(configBytes),
            "typedTargetBytesBase64url" to b64(targetBytes),
        ))
        return D101NativePreIntentInputsSource(D101PreIntentNativeReader { action ->
            assertEquals(D101NativePreIntentInputsSource.ACTION, action)
            response
        }, manifestSha, D101PreIntentInputScope(op, targetSha, app, pins, root))
    }

    @Test
    fun `one native-sourced selection reaches delivery once and closes installation`() {
        var deliveries = 0
        var closes = 0
        val provider = D101PreIntentCaptureProvider {
            object : D101PreIntentCaptureInstallation {
                override val fixedSource = syntheticNativeSource(Path.of("../..").toRealPath())

                override fun deliverUnsigned(snapshot: D101PreIntentSelectedSnapshot) {
                    deliveries++
                    assertEquals(options, snapshot.effectiveOptions())
                    assertEquals(options - setOf("SERVER_NAME", "SERVER_GENERATION"),
                        snapshot.inputs.parsedImporterOptions)
                    assertEquals(5, snapshot.rawOriginals().size)
                    assertTrue(snapshot.world.topologyOriginals().isNotEmpty())
                }

                override fun close() { closes++ }
            }
        }
        assertEquals(0, D101PreIntentCaptureProduction.runWithProviders(listOf(provider)))
        assertEquals(1, deliveries)
        assertEquals(1, closes)
    }

    @Test
    fun `no unique provider missing native reader and failed delivery remain closed`() {
        assertEquals(78, D101PreIntentCaptureProduction.runWithProviders(emptyList()))
        var installed = 0
        val missingReader = D101PreIntentCaptureProvider {
            installed++
            object : D101PreIntentCaptureInstallation {
                override val fixedSource = D101NativePreIntentInputsSource(null, "a".repeat(64),
                    D101PreIntentInputScope(op, "c".repeat(64), app, pins, Path.of("../..").toRealPath()))
                override fun deliverUnsigned(snapshot: D101PreIntentSelectedSnapshot) = error("must not deliver")
                override fun close() = Unit
            }
        }
        assertEquals(78, D101PreIntentCaptureProduction.runWithProviders(listOf(missingReader, missingReader)))
        assertEquals(0, installed)
        assertEquals(78, D101PreIntentCaptureProduction.runWithProviders(listOf(missingReader)))
        assertEquals(1, installed)

        var closed = false
        val failingDelivery = D101PreIntentCaptureProvider {
            object : D101PreIntentCaptureInstallation {
                override val fixedSource = syntheticNativeSource(Path.of("../..").toRealPath())
                override fun deliverUnsigned(snapshot: D101PreIntentSelectedSnapshot): Unit = throw SelectedSourceUnavailable()
                override fun close() { closed = true }
            }
        }
        assertEquals(78, D101PreIntentCaptureProduction.runWithProviders(listOf(failingDelivery)))
        assertTrue(closed)
    }

    private fun hash(wire: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wire))
    private fun b64(wire: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(wire)
}

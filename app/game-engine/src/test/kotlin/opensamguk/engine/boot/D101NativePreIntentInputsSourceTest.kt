package opensamguk.engine.boot

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.infra.seed.SelectedSourceUnavailable
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

class D101NativePreIntentInputsSourceTest {
    @TempDir lateinit var directory: Path
    private val mapper = ObjectMapper()
    private val imagePins = setOf("game-api", "game-engine", "web-game", "game-postgres", "game-redis")
        .associateWith { "sha256:" + "a".repeat(64) }
    private val options = mapOf("SERVER_NAME" to "빼섭", "SERVER_GENERATION" to "0", "SCENARIO_CODE" to "scenario_3190",
        "SCENARIO_SEED_ENABLED" to "true", "SCENARIO_LOOKUP_DIR" to "", "RESET_MAXGENERAL" to "50",
        "RESET_FIRST_TURN" to "immediate", "RESET_EXTEND" to "1", "RESET_TURNTERM" to "60",
        "RESET_BLOCK_GENERAL_CREATE" to "1", "RESET_NPCMODE" to "0", "RESET_SHOW_IMG_LEVEL" to "0", "RESET_FICTION" to "0")
    private var calls = 0

    private fun source(mode: String = ""): D101NativePreIntentInputsSource {
        val root = directory.toRealPath()
        val target = mutableMapOf<String, Any>("storageImageDigests" to imagePins.filterKeys { it in setOf("game-postgres", "game-redis") },
            "scenarioCode" to "scenario_3190", "generation" to 0, "scenarioSeedEnabled" to true, "updates" to options,
            "imageDigests" to imagePins.filterKeys { it in setOf("game-api", "game-engine", "web-game") })
        if (mode == "target-generation") target["generation"] = "0"
        if (mode == "target-seed") target["scenarioSeedEnabled"] = "true"
        if (mode == "target-options") target["updates"] = options + ("RESET_MAXGENERAL" to "49")
        if (mode == "target-image") target["imageDigests"] = imagePins.filterKeys { it in setOf("game-api", "game-engine", "web-game") } + ("game-engine" to "sha256:" + "f".repeat(64))
        val targetBytes = mapper.writeValueAsBytes(mapOf("id" to "pep", "target" to target))
        val fp = hash(targetBytes)
        val op = "b".repeat(32)
        val app = "c".repeat(40)
        val config = mutableMapOf<String, Any>("schemaVersion" to 1, "kind" to "D101_EFFECTIVE_SEED_INPUTS_V1", "originalOp" to op,
            "typedTargetFingerprint" to fp, "appSourceSha" to app, "imagePins" to imagePins,
            "rawInputs" to (options + ("RESET_MAXGENERAL" to " 050 ")))
        when (mode) {
            "config-op" -> config["originalOp"] = "0".repeat(32)
            "config-app" -> config["appSourceSha"] = "0".repeat(40)
            "raw-numeric" -> config["rawInputs"] = options + ("RESET_MAXGENERAL" to "50.0")
            "raw-missing" -> config["rawInputs"] = options - "RESET_FICTION"
            "raw-null" -> config["rawInputs"] = options + ("RESET_FICTION" to null)
        }
        var configBytes = mapper.writeValueAsBytes(config)
        if (mode == "config-duplicate") configBytes = String(configBytes).replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1").toByteArray()
        val manifest = mutableMapOf<String, Any>("schemaVersion" to 1, "kind" to "D101_PRE_INTENT_INPUT_BINDINGS_V1", "originalOp" to op,
            "typedTargetFingerprint" to fp, "appSourceSha" to app, "imagePins" to imagePins,
            "configurationOriginal" to mapOf("rawSha256" to hash(configBytes), "byteLength" to configBytes.size),
            "typedTargetOriginal" to mapOf("rawSha256" to fp, "byteLength" to targetBytes.size), "artifactsRoot" to root.toString())
        if (mode == "future") manifest["selectedEnvelopeSha256"] = "0".repeat(64)
        if (mode == "root") manifest["artifactsRoot"] = root.resolve("unreviewed").toString()
        if (mode == "path") manifest["configurationOriginal"] = mapOf("rawSha256" to hash(configBytes), "byteLength" to configBytes.size, "filePath" to "/caller")
        val manifestBytes = mapper.writeValueAsBytes(manifest)
        val pin = hash(manifestBytes)
        val response = mutableMapOf<String, Any>("schemaVersion" to 1, "preIntentInstallationSha256" to pin,
            "preIntentInstallationBytesBase64url" to b64(manifestBytes), "configurationBytesBase64url" to b64(configBytes), "typedTargetBytesBase64url" to b64(targetBytes))
        if (mode == "config-bytes") response["configurationBytesBase64url"] = b64(configBytes + ' '.code.toByte())
        if (mode == "target-bytes") response["typedTargetBytesBase64url"] = b64(targetBytes + ' '.code.toByte())
        if (mode == "response-extra") response["path"] = "/caller"
        val raw = mapper.writeValueAsBytes(response)
        val reader = D101PreIntentNativeReader { action ->
            assertEquals("read-pre-intent-inputs", action)
            calls++
            if ((mode == "drift" && calls % 2 == 0) || (mode == "later-drift" && calls >= 4)) raw + ' '.code.toByte() else raw
        }
        return D101NativePreIntentInputsSource(reader, pin, D101PreIntentInputScope(op, fp, app, imagePins, root))
    }

    @Test fun `fixed config and target precede any selected receipt and preserve full thirteen inputs`() {
        val source = source()
        val supplied = source.readFixedInputs()
        assertEquals(options, supplied.effectiveOptions())
        assertEquals(directory.toRealPath(), supplied.artifactsRoot)
        assertEquals(2, calls)
        (supplied.effectiveOptions() as MutableMap<String, String>)["SERVER_NAME"] = "changed"
        assertEquals("빼섭", supplied.effectiveOptions()["SERVER_NAME"])
    }

    @Test fun `unknown future fields original rebinding source drift and typed option fraud stay closed`() {
        for (mode in listOf("future", "root", "path", "config-op", "config-app", "raw-numeric", "raw-missing", "raw-null",
            "config-duplicate", "target-generation", "target-seed", "target-options", "target-image", "config-bytes", "target-bytes", "response-extra", "drift")) {
            calls = 0
            assertThrows(SelectedSourceUnavailable::class.java, { source(mode).readFixedInputs() }, mode)
        }
    }

    @Test fun `missing source and unpinned executable cannot start a child or select another action`() {
        val root = directory.toRealPath()
        assertThrows(SelectedSourceUnavailable::class.java) {
            D101NativePreIntentInputsSource(null, "a".repeat(64), D101PreIntentInputScope("b".repeat(32), "c".repeat(64), "d".repeat(40), imagePins, root)).readFixedInputs()
        }
        val reader = D101PinnedPreIntentNativeReader(root.resolve("missing-helper"), "a".repeat(64))
        assertThrows(SelectedSourceUnavailable::class.java) { reader.read("read-originals") }
        assertThrows(SelectedSourceUnavailable::class.java) { reader.read("read-pre-intent-inputs") }
    }

    @Test fun `unsigned sink receives exact original bytes and failed later read clears them`() {
        val source = source("later-drift")
        assertThrows(SelectedSourceUnavailable::class.java) { source.capturedInputOriginals() }
        source.readFixedInputs()
        val originals = source.capturedInputOriginals()
        assertEquals(setOf("configuration", "typedTarget"), originals.keys)
        assertEquals(" 050 ", mapper.readTree(originals.getValue("configuration"))["rawInputs"]["RESET_MAXGENERAL"].textValue())
        originals.getValue("configuration").fill(0)
        assertEquals(" 050 ", mapper.readTree(source.capturedInputOriginals().getValue("configuration"))["rawInputs"]["RESET_MAXGENERAL"].textValue())
        assertThrows(SelectedSourceUnavailable::class.java) { source.readFixedInputs() }
        assertThrows(SelectedSourceUnavailable::class.java) { source.capturedInputOriginals() }
    }

    private fun hash(wire: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wire))
    private fun b64(wire: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(wire)
}

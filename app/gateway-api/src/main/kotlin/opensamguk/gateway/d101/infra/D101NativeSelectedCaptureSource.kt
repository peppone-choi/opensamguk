package opensamguk.gateway.d101.infra

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.D101StrictJson
import opensamguk.gateway.d101.domain.D101PurposeAuthorityUnavailable
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Pre-intent transport scope. No future intent/card/command hash is required. */
internal data class D101SelectedCaptureScope(
    val originalOp: String,
    val targetFingerprint: String,
    val appSourceSha: String,
    val imagePins: Map<String, String>,
    val producerIdentity: String,
    val producerContainerId: String,
    val producerImageId: String,
)

internal data class D101SelectedTransferMetrics(val logicalArtifactId: String, val bytes: Int, val parts: Int, val elapsedNanos: Long, val completed: Boolean)

/** Assembles one original at a time from reviewed native custody. The index pin
 * must come from an independently reviewed pre-intent capture, not caller input.
 * Whole bytes/SHA/length are checked here; selected semantics remain separate. */
internal class D101NativeSelectedCaptureSource(
    private val reader: D101NativeHostReader,
    private val readerBindingsSha256: String,
    private val captureIndexSha256: String,
    scope: D101SelectedCaptureScope,
    mapper: ObjectMapper = ObjectMapper(),
    private val now: () -> Instant = Instant::now,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val expected = scope.copy(imagePins = scope.imagePins.toMap())
    private val json = D101StrictJson(mapper)
    private val started = nanoTime()
    private val indexBytes = closed { readIndex() }
    private val references = closed { decodeIndex(indexBytes) }
    private val transfers = mutableListOf<D101SelectedTransferMetrics>()

    @Synchronized fun metrics(): List<D101SelectedTransferMetrics> = transfers.toList()

    @Synchronized fun readOriginal(logicalArtifactId: String): ByteArray = closed {
        val began = nanoTime()
        val ref = references[logicalArtifactId] ?: unavailable()
        if (!readIndex().contentEquals(indexBytes)) unavailable()
        val length = json.positiveLong(ref["byteLength"]).toInt()
        val count = (length - 1) / PART_BYTES + 1
        val out = ByteArrayOutputStream(length)
        var receivedParts = 0
        var completed = false
        try {
            repeat(count) { ordinal ->
                checkDeadline()
                val response = json.objectBytes(reader.read("$PART_ACTION$logicalArtifactId:$ordinal"), PART_KEYS, 2 * 1024 * 1024)
                requireResponse(response)
                if (json.text(response["logicalArtifactId"]) != logicalArtifactId ||
                    json.sha(response["pinnedOriginalSha256"]) != json.sha(ref["rawSha256"]) ||
                    json.positiveLong(response["originalByteLength"]) != length.toLong() ||
                    json.unsigned(response["partIndex"]).toLong() != ordinal.toLong() ||
                    json.unsigned(response["partOffset"]).toLong() != ordinal.toLong() * PART_BYTES ||
                    response["nativeSnapshot"] != ref["snapshot"]) unavailable()
                val bytes = json.base64url(json.text(response["partBytesBase64url"]), PART_BYTES)
                if (bytes.size != minOf(PART_BYTES, length - ordinal * PART_BYTES) ||
                    D101StrictJson.hash(bytes) != json.sha(response["partSha256"])) unavailable()
                out.write(bytes)
                receivedParts++
                checkDeadline()
            }
            val bytes = out.toByteArray()
            if (bytes.size != length || D101StrictJson.hash(bytes) != json.sha(ref["rawSha256"]) ||
                !readIndex().contentEquals(indexBytes)) unavailable()
            checkDeadline()
            completed = true
            bytes
        } finally {
            transfers += D101SelectedTransferMetrics(logicalArtifactId, out.size(), receivedParts, nanoTime() - began, completed)
        }
    }

    private fun readIndex(): ByteArray {
        checkDeadline()
        if (!D101StrictJson.SHA.matches(readerBindingsSha256) || !D101StrictJson.SHA.matches(captureIndexSha256)) unavailable()
        val response = json.objectBytes(reader.read(INDEX_ACTION), INDEX_RESPONSE_KEYS, 96 * 1024)
        requireResponse(response)
        return json.base64url(json.text(response["captureIndexBytesBase64url"]), 64 * 1024).also {
            if (D101StrictJson.hash(it) != captureIndexSha256) unavailable()
            checkDeadline()
        }
    }

    private fun decodeIndex(bytes: ByteArray): Map<String, JsonNode> {
        val root = json.objectBytes(bytes, INDEX_KEYS, 64 * 1024)
        if (json.positiveLong(root["schemaVersion"]) != 1L || json.text(root["kind"]) != "D101_SELECTED_CAPTURE_INDEX_V1" ||
            json.text(root["originalOp"]) != expected.originalOp || !D101StrictJson.OPERATION.matches(expected.originalOp) ||
            json.sha(root["typedTargetFingerprint"]) != expected.targetFingerprint ||
            json.text(root["appSourceSha"]) != expected.appSourceSha || !D101StrictJson.SHA40.matches(expected.appSourceSha) ||
            json.stringMap(root["imagePins"], IMAGE_IDS, true) != expected.imagePins ||
            json.text(root["trustedProducerIdentity"]) != expected.producerIdentity || !D101StrictJson.KEY_ID.matches(expected.producerIdentity) ||
            json.sha(root["producerContainerId"]) != expected.producerContainerId ||
            json.text(root["producerImageId"]) != expected.producerImageId || !D101StrictJson.DIGEST.matches(expected.producerImageId) ||
            json.positiveLong(root["partBytes"]) != PART_BYTES.toLong()) unavailable()
        val captured = json.text(root["capturedAtUtc"])
        val at = Instant.parse(captured)
        val current = now()
        if (!captured.endsWith("Z") || at.epochSecond <= 0 || current.epochSecond <= 0 || at > current) unavailable()
        val originals = root["originals"]
        if (!originals.isObject) unavailable()
        val ids = originals.fieldNames().asSequence().toSet()
        if (!ids.containsAll(REQUIRED_IDS) || ids.size !in REQUIRED_IDS.size..REQUIRED_IDS.size + 32 || ids.any { !validSourceId(it) }) unavailable()
        json.requireKeys(originals, ids)
        val refs = ids.associateWith { id ->
            val ref = originals[id]
            json.requireKeys(ref, REFERENCE_KEYS)
            val expectedPath = "$ORIGINAL_DIRECTORY/${expected.originalOp}/${D101StrictJson.hash(id.toByteArray(Charsets.UTF_8))}.bin"
            if (json.text(ref["filePath"]) != expectedPath) unavailable()
            json.sha(ref["rawSha256"])
            val length = json.positiveLong(ref["byteLength"])
            if (length > sourceLimit(id)) unavailable()
            val snapshot = ref["snapshot"]
            json.requireKeys(snapshot, SNAPSHOT_KEYS)
            for (key in setOf("device", "inode")) if (json.unsigned(snapshot[key]).signum() <= 0) unavailable()
            if (json.positiveLong(snapshot["byteLength"]) != length || json.positiveLong(snapshot["modifiedAtUnixNano"]) <= 0) unavailable()
            val media = json.text(ref["mediaType"])
            val allowedMedia = when {
                id in CLASS_IDS || id == "topologyCanonical" -> setOf("application/octet-stream")
                id.startsWith("topology-input:") -> setOf("application/json", "application/octet-stream")
                else -> setOf("application/json")
            }
            if (media !in allowedMedia) unavailable()
            ref.deepCopy<JsonNode>()
        }
        if (json.sha(root["selectedEnvelopeSha256"]) != json.sha(refs.getValue("selectedEnvelope")["rawSha256"]) ||
            json.sha(root["selectedReceiptSha256"]) != json.sha(refs.getValue("selectedReceipt")["rawSha256"]) ||
            expected.targetFingerprint != json.sha(refs.getValue("typedTarget")["rawSha256"])) unavailable()
        return refs
    }

    private fun requireResponse(root: JsonNode) {
        if (json.positiveLong(root["schemaVersion"]) != 1L || json.sha(root["installationSha256"]) != readerBindingsSha256 ||
            json.sha(root["captureIndexSha256"]) != captureIndexSha256) unavailable()
    }

    private fun checkDeadline() {
        val elapsed = nanoTime() - started
        if (elapsed < 0 || elapsed >= TimeUnit.SECONDS.toNanos(30)) unavailable()
    }
    private fun <T> closed(block: () -> T): T = try { block() } catch (_: Exception) { unavailable() }
    private fun unavailable(): Nothing = throw D101PurposeAuthorityUnavailable()

    companion object {
        const val INDEX_ACTION = "read-selected-source-index"
        const val PART_ACTION = "read-selected-source-part:"
        const val PART_BYTES = 1024 * 1024
        const val ORIGINAL_DIRECTORY = "/etc/opensamguk/d101/selected-capture-originals"
        val REQUIRED_IDS = setOf("selectedEnvelope", "selectedReceipt", "typedTarget", "configuration", "parserClass", "resolverDecision", "topologyRootClass", "topologyCanonical", "selectedWorld", "parsedOptions", "tiles.json", "world.json", "roads.json", "selected-scenario.json", "classpath-scenario.json")
        private val CLASS_IDS = setOf("parserClass", "topologyRootClass")
        private val IMAGE_IDS = setOf("game-api", "game-engine", "web-game", "game-postgres", "game-redis")
        private val INDEX_KEYS = setOf("schemaVersion", "kind", "originalOp", "typedTargetFingerprint", "appSourceSha", "imagePins", "trustedProducerIdentity", "producerContainerId", "producerImageId", "selectedEnvelopeSha256", "selectedReceiptSha256", "partBytes", "capturedAtUtc", "originals")
        private val INDEX_RESPONSE_KEYS = setOf("schemaVersion", "installationSha256", "captureIndexSha256", "captureIndexBytesBase64url")
        private val REFERENCE_KEYS = setOf("filePath", "rawSha256", "byteLength", "snapshot", "mediaType")
        private val SNAPSHOT_KEYS = setOf("device", "inode", "byteLength", "modifiedAtUnixNano")
        private val PART_KEYS = setOf("schemaVersion", "installationSha256", "captureIndexSha256", "logicalArtifactId", "pinnedOriginalSha256", "originalByteLength", "partIndex", "partOffset", "partSha256", "partBytesBase64url", "nativeSnapshot")
        fun validSourceId(id: String): Boolean {
            if (id in REQUIRED_IDS) return true
            if (!id.startsWith("topology-input:")) return false
            val logical = id.removePrefix("topology-input:")
            return Regex("[A-Za-z0-9._/-]{1,256}").matches(logical) && logical != "." && logical != ".." &&
                !logical.startsWith("../") && !logical.contains("/../") &&
                !Path.of(logical).isAbsolute && Path.of(logical).normalize().toString() == logical
        }
        fun validAction(action: String): Boolean {
            if (action == INDEX_ACTION) return true
            if (!action.startsWith(PART_ACTION)) return false
            val value = action.removePrefix(PART_ACTION)
            val ordinal = value.substringAfterLast(':', "")
            val n = ordinal.toIntOrNull() ?: return false
            return n in 0..63 && n.toString() == ordinal && validSourceId(value.substringBeforeLast(':', ""))
        }
        private fun sourceLimit(id: String): Long = when (id) {
            "selectedEnvelope" -> 96L * 1024
            "selectedReceipt", "configuration", "resolverDecision", "parsedOptions" -> 64L * 1024
            "typedTarget" -> 16L * 1024
            "parserClass", "topologyRootClass" -> 2L * 1024 * 1024
            "selected-scenario.json", "classpath-scenario.json" -> 16L * 1024 * 1024
            else -> 64L * 1024 * 1024 // Transport cap, never a semantic parser limit.
        }
    }
}

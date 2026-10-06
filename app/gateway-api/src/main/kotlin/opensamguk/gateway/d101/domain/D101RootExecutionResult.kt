package opensamguk.gateway.d101.domain

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Clock
import java.time.Instant
import java.util.Collections

/** A signed Root terminal observation, not a final publication or settlement receipt. */
internal class D101RootExecutionResult private constructor(
    original: ByteArray,
    val rawSha256: String,
    val status: Status,
    val operationId: String,
    val approvalIntentSha256: String,
    val approvalPlanSha256: String,
    val executionReceiptSha256: String,
    val targetFingerprint: String,
    val rootRequestFingerprint: String,
    val gatewayPayloadSha256: String,
    val verifyingRevision: Long,
    val appSourceSha: String,
    imageDigests: Map<String, String>,
    val acceptedAtUtc: Instant,
    val completedAtUtc: Instant,
    val executionJournalSha256: String?,
    val actualRuntimeReceiptSha256: String?,
    val failureCode: FailureCode?,
) {
    private val original = original.copyOf()
    val imageDigests: Map<String, String> = Collections.unmodifiableMap(imageDigests.toSortedMap())
    fun originalBytes(): ByteArray = original.copyOf()

    enum class Status { SUCCEEDED, FAILED, RECOVERY_REQUIRED, CANCELLED }
    enum class FailureCode {
        ADMISSION_REJECTED, PHASE_FAILED, WORKER_FAILED, CANCELLED, SOURCE_UNAVAILABLE,
        CLOCK_UNAVAILABLE, CUSTODY_UNAVAILABLE, UNKNOWN_STAGE,
    }

    companion object {
        const val MAX_BYTES = 16 * 1024
        private val KEYS = setOf(
            "schemaVersion", "serverId", "worldId", "operationId", "kind", "status", "generation",
            "scenarioCode", "serverName", "approvalIntentSha256", "approvalPlanSha256",
            "executionReceiptSha256", "targetFingerprint", "rootRequestFingerprint", "gatewayPayloadSha256",
            "verifyingRevision", "appSourceSha", "imageDigests", "acceptedAtUtc", "completedAtUtc",
            "executionJournalSha256", "actualRuntimeReceiptSha256", "failureCode",
        )
        private val UTC_Z = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z")

        /** Invoke only after original SHA and the separate Root signature have been checked. */
        fun decode(original: ByteArray, expectedSha256: String, execution: D101Execution,
            clock: Clock, mapper: ObjectMapper): D101RootExecutionResult {
            try {
                if (!D101StrictJson.SHA.matches(expectedSha256) || original.isEmpty() || original.size > MAX_BYTES ||
                    D101StrictJson.hash(original) != expectedSha256 ||
                    original.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())) invalid()
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(original))
                val strict = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                val root = strict.readTree(original)
                if (root == null || !root.isObject || root.fieldNames().asSequence().toSet() != KEYS) invalid()
                if (!integer(root["schemaVersion"], BigInteger.ONE) || !integer(root["worldId"], BigInteger.ONE) ||
                    !integer(root["generation"], BigInteger.ZERO) || text(root["serverId"]) != "pep" ||
                    text(root["kind"]) != "reset" || text(root["scenarioCode"]) != "scenario_3190" ||
                    text(root["serverName"]) != "빼섭") invalid()
                val status = when (text(root["status"])) {
                    "succeeded" -> Status.SUCCEEDED
                    "failed" -> Status.FAILED
                    "recovery_required" -> Status.RECOVERY_REQUIRED
                    "cancelled" -> Status.CANCELLED
                    else -> invalid()
                }
                val failure = nullableText(root["failureCode"])?.let {
                    FailureCode.entries.singleOrNull { code -> code.name == it } ?: invalid()
                }
                val journal = nullableSha(root["executionJournalSha256"])
                val runtime = nullableSha(root["actualRuntimeReceiptSha256"])
                if (status == Status.SUCCEEDED && (journal == null || runtime == null || failure != null)) invalid()
                if (status != Status.SUCCEEDED && failure == null) invalid()
                val dispatch = execution.dispatch ?: invalid()
                val intent = execution.intent
                val operationId = text(root["operationId"])
                val intentSha = sha(root["approvalIntentSha256"])
                val planSha = sha(root["approvalPlanSha256"])
                val receiptSha = sha(root["executionReceiptSha256"])
                val targetSha = sha(root["targetFingerprint"])
                val requestFp = sha(root["rootRequestFingerprint"])
                val gatewaySha = sha(root["gatewayPayloadSha256"])
                val revision = revision(root["verifyingRevision"])
                val appSha = text(root["appSourceSha"])
                if (!D101StrictJson.OPERATION.matches(operationId) || !D101StrictJson.SHA40.matches(appSha) ||
                    operationId != intent.operationId || intentSha != intent.sha256 ||
                    planSha != dispatch.approvalPlanSha256 || receiptSha != dispatch.executionReceiptSha256 ||
                    targetSha != intent.targetFingerprint || requestFp != dispatch.rootRequestFingerprint ||
                    gatewaySha != execution.gatewayPayloadSha256 || revision != execution.verifyingRevision ||
                    appSha != intent.appSourceSha) invalid()
                val pinsNode = root["imageDigests"]
                if (pinsNode == null || !pinsNode.isObject ||
                    pinsNode.fieldNames().asSequence().toSet() != D101ApprovalIntentCodec.FIVE_IMAGES) invalid()
                val pins = D101ApprovalIntentCodec.FIVE_IMAGES.associateWith { key ->
                    text(pinsNode[key]).also { if (!D101StrictJson.DIGEST.matches(it)) invalid() }
                }
                if (pins != intent.newImageDigests) invalid()
                val accepted = instant(root["acceptedAtUtc"])
                val completed = instant(root["completedAtUtc"])
                val now = clock.instant()
                if (completed < accepted || accepted > now || completed > now) invalid()
                return D101RootExecutionResult(original, expectedSha256, status, operationId, intentSha, planSha,
                    receiptSha, targetSha, requestFp, gatewaySha, revision, appSha, pins, accepted, completed,
                    journal, runtime, failure)
            } catch (_: Exception) {
                invalid()
            }
        }

        private fun integer(node: JsonNode?, expected: BigInteger): Boolean =
            node != null && node.isIntegralNumber && node.bigIntegerValue() == expected

        private fun text(node: JsonNode?): String =
            node?.takeIf { it.isTextual && it.textValue().isNotEmpty() }?.textValue() ?: invalid()

        private fun nullableText(node: JsonNode?): String? =
            if (node?.isNull == true) null else text(node)

        private fun sha(node: JsonNode?): String = text(node).also {
            if (!D101StrictJson.SHA.matches(it)) invalid()
        }

        private fun nullableSha(node: JsonNode?): String? =
            if (node?.isNull == true) null else sha(node)

        private fun revision(node: JsonNode?): Long = text(node).let {
            if (!D101StrictJson.REVISION.matches(it)) invalid()
            it.toLongOrNull()?.takeIf { revision -> revision > 0 } ?: invalid()
        }

        private fun instant(node: JsonNode?): Instant = text(node).let {
            if (!UTC_Z.matches(it)) invalid()
            Instant.parse(it)
        }

        private fun invalid(): Nothing = throw D101ObservationUnavailable()
    }
}

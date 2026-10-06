package opensamguk.gateway.d101.domain

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant

/** Original signed Root preparation. It is neither a dispatch nor an approval. */
internal class D101RootPreparedProof private constructor(
    original: ByteArray,
    val rawSha256: String,
    val acceptedAtUtc: Instant,
    val preparedAtUtc: Instant,
    val preparedJournalSha256: String,
    val candidate: D101DispatchIntentCandidate,
) {
    private val original = original.copyOf()
    fun originalBytes(): ByteArray = original.copyOf()

    companion object {
        const val MAX_BYTES = 16 * 1024
        private val UTC_Z = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z")
        private val KEYS = setOf(
            "schemaVersion", "serverId", "worldId", "operationId", "phase", "approvalIntentSha256",
            "approvalPlanSha256", "executionReceiptSha256", "targetFingerprint", "rootRequestFingerprint",
            "gatewayPayloadSha256", "initialPublicRevision", "verifyingRevision", "appSourceSha", "imageDigests",
            "acceptedAtUtc", "preparedAtUtc", "preparedJournalSha256", "destructiveCutoffUnix", "recoveryDeadlineUnix",
        )

        /** Signature/trust must be checked independently before calling this decoder. */
        fun decode(original: ByteArray, expectedSha256: String, execution: D101Execution,
            candidate: D101DispatchIntentCandidate, clock: Clock, mapper: ObjectMapper): D101RootPreparedProof {
            try {
                if (!D101StrictJson.SHA.matches(expectedSha256) || D101StrictJson.hash(original) != expectedSha256 ||
                    execution.state != D101ExecutionState.PREPARED || execution.dispatch != null) unavailable()
                val json = D101StrictJson(mapper)
                val root = json.objectBytes(original, KEYS, MAX_BYTES)
                val intent = execution.intent
                if (json.positiveLong(root["schemaVersion"]) != 1L || json.positiveLong(root["worldId"]) != 1L ||
                    json.text(root["serverId"]) != "pep" || json.text(root["phase"]) != "prepared" ||
                    json.text(root["operationId"]) != intent.operationId || json.sha(root["approvalIntentSha256"]) != intent.sha256 ||
                    json.sha(root["approvalPlanSha256"]) != candidate.approvalPlanSha256 ||
                    json.sha(root["executionReceiptSha256"]) != candidate.executionReceiptSha256 ||
                    json.sha(root["targetFingerprint"]) != intent.targetFingerprint ||
                    json.sha(root["rootRequestFingerprint"]) != candidate.rootRequestFingerprint ||
                    json.sha(root["gatewayPayloadSha256"]) != execution.gatewayPayloadSha256 ||
                    json.revision(root["initialPublicRevision"]) != intent.initialPublicRevision ||
                    json.revision(root["verifyingRevision"]) != execution.verifyingRevision ||
                    candidate.verifyingRevision != execution.verifyingRevision ||
                    json.text(root["appSourceSha"]) != intent.appSourceSha ||
                    json.stringMap(root["imageDigests"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.newImageDigests ||
                    json.positiveLong(root["destructiveCutoffUnix"]) != intent.destructiveCutoffUnix ||
                    json.positiveLong(root["recoveryDeadlineUnix"]) != intent.recoveryDeadlineUnix) unavailable()
                fun instant(field: String): Instant = json.text(root[field]).let {
                    if (!UTC_Z.matches(it)) unavailable()
                    Instant.parse(it)
                }
                val accepted = instant("acceptedAtUtc")
                val prepared = instant("preparedAtUtc")
                val now = clock.instant()
                if (accepted < Instant.ofEpochSecond(intent.windowOpensAtUnix) || prepared < accepted || prepared > now ||
                    now >= prepared.plusSeconds(30) || now.epochSecond >= intent.destructiveCutoffUnix ||
                    now.epochSecond < intent.windowOpensAtUnix) unavailable()
                return D101RootPreparedProof(original, expectedSha256, accepted, prepared,
                    json.sha(root["preparedJournalSha256"]), candidate)
            } catch (_: Exception) {
                unavailable()
            }
        }

        private fun unavailable(): Nothing = throw D101ObservationUnavailable()
    }
}

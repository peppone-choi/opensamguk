package opensamguk.gateway.d101.security

import com.fasterxml.jackson.databind.JsonNode
import opensamguk.gateway.d101.domain.*
import java.math.BigInteger

/** C9 frozen v1 tuple and RawRef shape only. No issuer, signature or custody grant. */
internal class D101Original6ScopeCheck(private val json: D101StrictJson) {
    fun verify(node: JsonNode, intent: D101ApprovalIntent, dockerSourceSha: String) {
        json.requireKeys(node, SCOPE_KEYS)
        if (json.text(node["operationId"]) != intent.operationId ||
            !D101StrictJson.OPERATION.matches(intent.operationId) ||
            json.text(node["serverId"]) != "pep" || json.positiveLong(node["worldId"]) != 1L ||
            json.sha(node["targetFingerprint"]) != intent.targetFingerprint ||
            source(node["appSourceSha"]) != intent.appSourceSha ||
            source(node["dockerSourceSha"]) != dockerSourceSha ||
            json.stringMap(node["oldImageDigests"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.oldImageDigests ||
            json.stringMap(node["newImageDigests"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.newImageDigests ||
            json.revision(node["initialPublicRevision"]) != intent.initialPublicRevision) json.invalid()
        val target = ref(node["typedTargetRef"])
        val bytes = intent.target.originalBytes()
        if (target.sha256 != intent.targetFingerprint || target.sha256 != D101StrictJson.hash(bytes) ||
            target.byteLength != bytes.size.toLong()) json.invalid()
        val window = node["window"]
        json.requireKeys(window, WINDOW_KEYS)
        val opens = json.positiveLong(window["windowOpensAtUnix"])
        val cutoff = json.positiveLong(window["destructiveCutoffUnix"])
        val deadline = json.positiveLong(window["recoveryDeadlineUnix"])
        if (opens != intent.windowOpensAtUnix || cutoff != intent.destructiveCutoffUnix ||
            deadline != intent.recoveryDeadlineUnix || opens >= cutoff || cutoff >= deadline) json.invalid()
        val budget = node["spaceBudget"]
        json.requireKeys(budget, D101ApprovalIntentCodec.SPACE_KEYS)
        val decoded = D101SpaceBudget(json.unsigned(budget["CandidateUnpackedBytes"]),
            json.unsigned(budget["BackupBytes"]), json.unsigned(budget["RecoveryBytes"]),
            json.unsigned(budget["TemporaryBytes"]), json.unsigned(budget["NewFileCount"]),
            json.unsigned(budget["InodeReserve"]))
        if (decoded != intent.spaceBudget || decoded.candidateUnpackedBytes + decoded.backupBytes +
            decoded.recoveryBytes + decoded.temporaryBytes + RESERVE > D101StrictJson.UINT64_MAX ||
            decoded.newFileCount + decoded.inodeReserve > D101StrictJson.UINT64_MAX) json.invalid()
    }

    fun ref(node: JsonNode?): RawRef {
        json.requireKeys(node, REF_KEYS)
        val id = json.text(node!!["logicalId"])
        val length = json.positiveLong(node["byteLength"])
        val media = json.text(node["mediaType"])
        if (id.length > 128 || !RAW_ID.matches(id) || length > 64L * 1024 * 1024 || media !in MEDIA) json.invalid()
        return RawRef(id, json.sha(node["sha256"]), length, media)
    }

    fun source(node: JsonNode?): String = json.text(node).also { if (!D101StrictJson.SHA40.matches(it)) json.invalid() }

    internal data class RawRef(val logicalId: String, val sha256: String, val byteLength: Long, val mediaType: String)

    companion object {
        private val RESERVE = BigInteger.valueOf(10L * 1024 * 1024 * 1024)
        private val SCOPE_KEYS = setOf("operationId", "serverId", "worldId", "targetFingerprint", "typedTargetRef",
            "appSourceSha", "dockerSourceSha", "oldImageDigests", "newImageDigests", "initialPublicRevision", "window", "spaceBudget")
        private val WINDOW_KEYS = setOf("windowOpensAtUnix", "destructiveCutoffUnix", "recoveryDeadlineUnix")
        private val REF_KEYS = setOf("logicalId", "sha256", "byteLength", "mediaType")
        private val MEDIA = setOf("application/json", "application/xml", "application/zip", "text/plain", "application/octet-stream")
        private val RAW_ID = Regex("(?:raw:[A-Za-z0-9._:-]{1,123}|" +
            D101ApprovedPurposeAuthority.ORIGINAL_IDS.sorted().joinToString("|") + ")")
    }
}

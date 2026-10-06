package opensamguk.gateway.d101.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.D101StrictJson
import java.security.MessageDigest
import java.util.HexFormat

/** Parent bytes and decision slices only; issuer and user scope remain separate. */
internal class D101ApprovalDecisionOriginalCheck(private val mapper: ObjectMapper = ObjectMapper()) {
    private val json = D101StrictJson(mapper)
    private val refs = D101Original6ScopeCheck(json)

    fun verify(approvalOriginal: ByteArray, parentOriginals: Map<String, ByteArray>) {
        val node = json.objectBytes(approvalOriginal, TOP_KEYS, 64 * 1024)
        if (json.positiveLong(node["schemaVersion"]) != 1L || json.text(node["kind"]) != "D101_APPROVAL_RECEIPT_V1") json.invalid()
        val parents = array(node["decisionParents"], 5)
        val parentsById = parents.associateBy { refs.ref(it).logicalId }
        if (parentsById.size != 5 || parentsById.keys != parentOriginals.keys ||
            parentsById.keys.any { !it.startsWith("raw:") }) json.invalid()
        val slices = array(node["decisionSlices"], 20)
        val decisionIds = slices.map { slice ->
            json.requireKeys(slice, SLICE_KEYS)
            json.text(slice["decisionId"]).also { if (!IDENTITY.matches(it)) json.invalid() }
        }
        if (decisionIds.toSet().size != 20) json.invalid()
        val grouped = slices.groupBy { json.text(it["parentLogicalId"]) }
        if (grouped.keys.any { it !in parentsById }) json.invalid()
        // Validate every parent, including unused ones; retain one frozen parent at a time.
        for ((id, ref) in parentsById) {
            val bytes = D101RawEvidenceBinding.bind(ref, parentOriginals.getValue(id), mapper).originalBytes()
            D101RawEvidenceBinding.requireUtf8(bytes)
            for (slice in grouped[id].orEmpty()) {
                val start = number(slice["startByte"], 0, 64 * 1024 * 1024)
                val end = number(slice["endByte"], 1, 64 * 1024 * 1024)
                val length = number(slice["byteLength"], 1, 64 * 1024 * 1024)
                val line = number(slice["startLine"], 1, 1_000_000)
                if (end <= start || end > bytes.size || length != end - start) json.invalid()
                D101RawEvidenceBinding.requireUtf8(bytes, start, length)
                val digest = MessageDigest.getInstance("SHA-256").run {
                    update(bytes, start, length)
                    HexFormat.of().formatHex(digest())
                }
                if (digest != json.sha(slice["sha256"])) json.invalid()
                var observedLine = 1L
                for (offset in 0 until start) if (bytes[offset] == 0x0a.toByte()) observedLine++
                if (observedLine != line.toLong()) json.invalid()
            }
        }
    }

    private fun array(node: JsonNode?, count: Int): List<JsonNode> {
        if (node == null || !node.isArray || node.size() != count || node.any { it.isNull }) json.invalid()
        return node.toList()
    }
    private fun number(node: JsonNode?, min: Int, max: Int): Int {
        if (node == null || !node.isIntegralNumber || !node.canConvertToInt() || node.intValue() !in min..max) json.invalid()
        return node.intValue()
    }
    companion object {
        private val IDENTITY = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val TOP_KEYS = setOf("schemaVersion", "kind", "scope", "sourceAuthority", "documentCollectorId",
            "decisionParents", "decisionSlices", "originalScopeRef", "issuer", "issuedAtUnix")
        private val SLICE_KEYS = setOf("decisionId", "parentLogicalId", "startByte", "endByte", "startLine", "byteLength", "sha256")
    }
}

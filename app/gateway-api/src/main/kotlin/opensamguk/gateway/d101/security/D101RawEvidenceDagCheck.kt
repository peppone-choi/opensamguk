package opensamguk.gateway.d101.security

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.D101StrictJson

/** Fixed four consumers' byte/reference DAG only. This authenticates no producer. */
internal class D101RawEvidenceDagCheck(private val mapper: ObjectMapper = ObjectMapper()) {
    private val json = D101StrictJson(mapper)
    private val refs = D101Original6ScopeCheck(json)
    private val parser = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun verify(ownerId: String, original: ByteArray, rawOriginals: Map<String, ByteArray>,
               fixedOriginals: Map<String, ByteArray>) {
        if (ownerId !in OWNERS || original.isEmpty() || original.size > 64 * 1024) json.invalid()
        val pending = ArrayDeque<Visit>()
        val active = mutableSetOf<String>()
        val checked = mutableMapOf<String, D101Original6ScopeCheck.RawRef>()
        val hashes = fixedOriginals.filterKeys { it in STAGES }.mapValues { D101StrictJson.hash(it.value) }
        val root = parse(original)
        if (!root.isObject) json.invalid()
        val references = scan(root, ownerId, true, hashes)
        for (reference in references.asReversed()) pending.addLast(Visit(reference, false))
        while (pending.isNotEmpty()) {
            val visit = pending.removeLast()
            val ref = refs.ref(visit.reference)
            val id = ref.logicalId
            if (visit.exit) { active.remove(id); continue }
            allowed(id, ownerId)
            if (id in active) json.invalid()
            val previous = checked[id]
            if (previous != null) {
                if (previous != ref) json.invalid()
                continue
            }
            val supplied = if (id.startsWith("raw:")) rawOriginals[id] else fixedOriginals[id]
            val bytes = D101RawEvidenceBinding.bind(visit.reference, supplied ?: json.invalid(), mapper).originalBytes()
            checked[id] = ref
            if (ref.mediaType == "application/json") {
                active.add(id)
                pending.addLast(Visit(visit.reference, true))
                val children = scan(parse(bytes), ownerId, false, hashes)
                for (child in children.asReversed()) pending.addLast(Visit(child, false))
            }
            // XML/ZIP/plain/opaque are byte-bound here; their own consumers parse them.
        }
    }

    private fun scan(root: JsonNode, owner: String, ownerRoot: Boolean,
                     knownHashes: Map<String, String>): List<JsonNode> {
        val found = mutableListOf<JsonNode>()
        val nodes = ArrayDeque<Pair<JsonNode, Boolean>>()
        nodes.addLast(root to ownerRoot)
        while (nodes.isNotEmpty()) {
            val (node, isOwnerRoot) = nodes.removeLast()
            if (node.isTextual) {
                for ((id, hash) in knownHashes) {
                    if (node.textValue() == hash || node.textValue() == "sha256:$hash") allowed(id, owner)
                }
            }
            if (node.isObject) {
                val keys = node.fieldNames().asSequence().toSet()
                if ("logicalId" in keys && "sha256" in keys) {
                    val ref = refs.ref(node)
                    allowed(ref.logicalId, owner)
                    found.add(node.deepCopy<JsonNode>())
                }
                if (!isOwnerRoot && node["kind"]?.isTextual == true) {
                    val id = KINDS[json.text(node["kind"])]
                    if (id != null) allowed(id, owner)
                }
                for ((key, child) in node.fields().asSequence()) {
                    if (key in ALWAYS_FORBIDDEN || key in forbiddenHashes(owner)) json.invalid()
                    nodes.addLast(child to false)
                }
            } else if (node.isArray) {
                for (child in node) nodes.addLast(child to false)
            }
        }
        return found
    }

    private fun allowed(id: String, owner: String) {
        if (id.startsWith("raw:")) return
        val stage = STAGES[id] ?: json.invalid()
        if (stage >= STAGES.getValue(owner) || (owner == "evidenceCatalog" && id !in CATALOG_IDS)) json.invalid()
    }

    private fun forbiddenHashes(owner: String): Set<String> = when (owner) {
        "approvalReceipt", "combinedCiReceipt" -> setOf("approvalIntentSha256", "deploymentCardSha256", "reviewBasisSha256", "evidenceCatalogSha256")
        else -> setOf("deploymentCardSha256", "reviewBasisSha256", "evidenceCatalogSha256")
    }

    private fun parse(bytes: ByteArray): JsonNode {
        if (bytes.isEmpty() || bytes.size > 64 * 1024 * 1024 ||
            bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())) json.invalid()
        try {
            D101RawEvidenceBinding.requireUtf8(bytes)
            return (parser.readTree(bytes) ?: json.invalid()).also { if (!it.isObject && !it.isArray) json.invalid() }
        } catch (_: Exception) { json.invalid() }
    }

    private data class Visit(val reference: JsonNode, val exit: Boolean)
    companion object {
        private val OWNERS = setOf("approvalReceipt", "combinedCiReceipt", "reviewBasis", "evidenceCatalog")
        private val CATALOG_IDS = setOf("approvalReceipt", "combinedCiReceipt", "selectedSourceReceipt", "isolatedSeedTickReceipt",
            "spaceInventoryReceipt", "approvalIntent", "configInventory", "commandPlan", "recoveryPlan", "readerBindings")
        private val STAGES = mapOf("approvalReceipt" to 0, "combinedCiReceipt" to 0, "selectedSourceReceipt" to 0,
            "isolatedSeedTickReceipt" to 0, "spaceInventoryReceipt" to 0, "approvalIntent" to 1, "configInventory" to 2,
            "commandPlan" to 2, "recoveryPlan" to 2, "readerBindings" to 2, "reviewBasis" to 3, "evidenceCatalog" to 4,
            "deploymentCard" to 5, "approvedReceiptProvenance" to 6, "trustManifest" to 7, "signedManifest" to 7)
        private val ALWAYS_FORBIDDEN = setOf("selfSha256", "manifestSha256", "trustManifestSha256",
            "outerManifestSha256", "approvedReceiptProvenanceSha256")
        private val KINDS = mapOf("D101_APPROVAL_RECEIPT_V1" to "approvalReceipt", "D101_COMBINED_CI_RECEIPT_V1" to "combinedCiReceipt",
            "D101_FINAL_SELECTED_SOURCE_V1" to "selectedSourceReceipt", "D101_SPACE_INVENTORY_RECEIPT_V1" to "spaceInventoryReceipt",
            "D101_REVIEW_BASIS_V1" to "reviewBasis", "D101_EVIDENCE_CATALOG_V1" to "evidenceCatalog",
            "D101_PEP_EXECUTION_CARD" to "deploymentCard", "D101_APPROVED_RECEIPT_PROVENANCE_V1" to "approvedReceiptProvenance")
    }
}

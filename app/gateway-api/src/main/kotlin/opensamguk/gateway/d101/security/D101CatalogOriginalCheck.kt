package opensamguk.gateway.d101.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.D101StrictJson

/** Original ten bytes and upstream DAG only; producer identity is not granted here. */
internal class D101CatalogOriginalCheck(private val mapper: ObjectMapper = ObjectMapper()) {
    private val json = D101StrictJson(mapper)
    private val refs = D101Original6ScopeCheck(json)

    fun verify(catalogOriginal: ByteArray, fixedOriginals: Map<String, ByteArray>, rawOriginals: Map<String, ByteArray>) {
        val node = json.objectBytes(catalogOriginal, TOP_KEYS, 64 * 1024)
        if (json.positiveLong(node["schemaVersion"]) != 1L || json.text(node["kind"]) != "D101_EVIDENCE_CATALOG_V1" ||
            json.text(node["sourceAuthority"]) != "UPSTREAM_RAW_INVENTORY") json.invalid()
        identity(node["inventoryAssemblerId"])
        val entries = node["entries"]
        if (!entries.isArray || entries.size() != 10 || entries.any { it.isNull }) json.invalid()
        val ids = entries.map { refs.ref(it["originalRef"]).logicalId }
        if (ids.toSet() != IDS || ids.toSet().size != ids.size) json.invalid()
        for (entry in entries) {
            json.requireKeys(entry, ENTRY_KEYS)
            val ref = refs.ref(entry["originalRef"])
            val original = fixedOriginals[ref.logicalId] ?: json.invalid()
            if (ref.mediaType != "application/json" || original.isEmpty() || original.size > 64 * 1024) json.invalid()
            D101RawEvidenceBinding.bind(entry["originalRef"], original, mapper)
            if (json.text(entry["producerClass"]) !in PRODUCER_CLASSES) json.invalid()
            identity(entry["producerId"])
            json.positiveLong(entry["observedAtUnix"])
            refs.ref(entry["producerSourceRef"])
            refs.ref(entry["scopeOriginalRef"])
            val verification = entry["verificationOriginalRefs"]
            if (!verification.isArray || verification.size() !in 1..32 || verification.any { it.isNull }) json.invalid()
            verification.forEach { refs.ref(it) }
        }
        D101RawEvidenceDagCheck(mapper).verify("evidenceCatalog", catalogOriginal, rawOriginals, fixedOriginals)
    }

    private fun identity(node: JsonNode?): String = json.text(node).also { if (!IDENTITY.matches(it)) json.invalid() }
    companion object {
        private val IDS = setOf("approvalReceipt", "combinedCiReceipt", "selectedSourceReceipt", "isolatedSeedTickReceipt",
            "spaceInventoryReceipt", "approvalIntent", "configInventory", "commandPlan", "recoveryPlan", "readerBindings")
        private val TOP_KEYS = setOf("schemaVersion", "kind", "scope", "sourceAuthority", "inventoryAssemblerId", "entries")
        private val ENTRY_KEYS = setOf("originalRef", "producerClass", "producerId", "producerSourceRef",
            "observedAtUnix", "scopeOriginalRef", "verificationOriginalRefs")
        private val PRODUCER_CLASSES = setOf("USER_DECISION_DOCUMENT", "GITHUB_ACTIONS", "FIXED_HOST_ISSUER", "INDEPENDENT_REVIEWER", "PRODUCT_SOURCE")
        private val IDENTITY = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}

package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class D101CatalogOriginalCheckTest {
    @Test
    fun `actual original ten and their complete raw support bind as a catalog subcheck`() {
        val p = Packet()
        p.verify(p.catalog())
    }

    @Test
    fun `changed original bytes and lengths cannot inherit old catalog hash labels`() {
        for (id in IDS) {
            val p = Packet(); val catalog = p.catalog()
            p.fixed[id] = p.f.wire(p.f.obj("changed" to id))
            assertThrows<D101RequestInvalid>(id) { p.verify(catalog) }
        }
    }

    @Test
    fun `rebound actual original cannot hide future hashes behind a valid ten inventory`() {
        val p = Packet()
        p.fixed["configInventory"] = p.f.wire(p.f.obj("nested" to p.f.obj("outerManifestSha256" to "f".repeat(64))))
        assertThrows<D101RequestInvalid> { p.verify(p.catalog()) }
        val q = Packet()
        q.raw["raw:verification"] = q.f.wire(q.f.obj("kind" to "D101_PEP_EXECUTION_CARD"))
        assertThrows<D101RequestInvalid> { q.verify(q.catalog()) }
    }

    @Test
    fun `supporting original absence and disguised self descendants keep catalog closed`() {
        val p = Packet(); val catalog = p.catalog(); p.raw.remove("raw:scope")
        assertThrows<D101RequestInvalid> { p.verify(catalog) }
        for (id in listOf("evidenceCatalog", "reviewBasis", "deploymentCard", "approvedReceiptProvenance")) {
            val q = Packet(); val n = q.catalog()
            (n["entries"][0]["verificationOriginalRefs"][0] as ObjectNode).put("logicalId", id)
            assertThrows<D101RequestInvalid> { q.verify(n) }
        }
    }

    @Test
    fun `wrong inventory unknown null float producer media and verification shape reject`() {
        val changes = listOf<(ObjectNode) -> Unit>(
            { it.put("extra", true) }, { it.putNull("entries") },
            { (it["entries"] as ArrayNode).remove(0); Unit },
            { (it["entries"][1]["originalRef"] as ObjectNode).put("logicalId", IDS[0]) },
            { (it["entries"][0]["originalRef"] as ObjectNode).put("mediaType", "text/plain") },
            { (it["entries"][0] as ObjectNode).put("producerClass", "CALLER_APPROVED") },
            { (it["entries"][0] as ObjectNode).put("observedAtUnix", 1.0) },
            { (it["entries"][0]["verificationOriginalRefs"] as ArrayNode).removeAll(); Unit },
        )
        for (change in changes) { val p = Packet(); val n = p.catalog(); change(n)
            assertThrows<D101RequestInvalid> { p.verify(n) } }
    }

    private class Packet {
        val f = D101RawEvidenceFixture()
        val fixed = IDS.associateWith { f.wire(f.obj("original" to it)) }.toMutableMap()
        val raw = listOf("raw:source", "raw:scope", "raw:verification").associateWith {
            f.wire(f.obj("synthetic" to it)) }.toMutableMap()
        fun catalog(): ObjectNode = f.obj("schemaVersion" to 1, "kind" to "D101_EVIDENCE_CATALOG_V1",
            "scope" to emptyMap<String, String>(), "sourceAuthority" to "UPSTREAM_RAW_INVENTORY", "inventoryAssemblerId" to "synthetic-collector",
            "entries" to IDS.map { id -> f.obj("originalRef" to f.ref(id, fixed.getValue(id)), "producerClass" to "PRODUCT_SOURCE",
                "producerId" to "synthetic-source", "producerSourceRef" to f.ref("raw:source", raw.getValue("raw:source")),
                "observedAtUnix" to 1, "scopeOriginalRef" to f.ref("raw:scope", raw.getValue("raw:scope")),
                "verificationOriginalRefs" to listOf(f.ref("raw:verification", raw.getValue("raw:verification")))) })
        fun verify(node: JsonNode) = D101CatalogOriginalCheck().verify(f.wire(node), fixed, raw)
    }
    companion object {
        private val IDS = listOf("approvalReceipt", "combinedCiReceipt", "selectedSourceReceipt", "isolatedSeedTickReceipt",
            "spaceInventoryReceipt", "approvalIntent", "configInventory", "commandPlan", "recoveryPlan", "readerBindings")
    }
}

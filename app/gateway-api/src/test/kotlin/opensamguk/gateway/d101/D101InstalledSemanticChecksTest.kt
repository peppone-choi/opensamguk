package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.node.ObjectNode
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI

/** Synthetic format/binding fixtures only; never actual production evidence. */
class D101InstalledSemanticChecksTest {
    @Test
    fun `known intent and card consume exact scoped bytes but do not approve other originals`() {
        val packet = Packet()
        val checks = packet.checks()
        assertEquals(D101ApprovedPurposeAuthority.ORIGINAL_IDS, checks.keys)
        for (id in listOf("approvalIntent", "deploymentCard")) {
            checks.getValue(id).verify(packet.originals.getValue(id).copyOf(), packet.intent)
        }
        for (id in D101ApprovedPurposeAuthority.ORIGINAL_IDS - setOf("approvalIntent", "deploymentCard")) {
            assertThrows<D101PurposeAuthorityUnavailable>(id) {
                checks.getValue(id).verify(packet.originals.getValue(id).copyOf(), packet.intent)
            }
        }
    }

    @Test
    fun `complete registry and matching raw hashes cannot make synthetic upstream evidence succeed`() {
        val packet = Packet()
        val verifier = D101HostSemanticVerifier(packet.f.codec, packet.intent.sha256, packet.checks())
        assertThrows<D101PurposeAuthorityUnavailable> {
            verifier.verifyOriginals(packet.verified())
        }
    }

    @Test
    fun `card semantic drifts reject even after its raw hash and host manifest are rebound`() {
        val changes: List<(ObjectNode) -> Unit> = listOf<(ObjectNode) -> Unit>(
            { it.put("schemaVersion", 2) },
            { it.put("schemaVersion", "1") },
            { it.put("kind", "UNKNOWN") },
            { it.put("operationId", "f".repeat(32)) },
            { it.put("approvalIntentSha256", "f".repeat(64)) },
            { it.put("appSourceSha", "f".repeat(40)) },
            { it.put("dockerSourceSha", "f".repeat(40)) },
            { it.put("unknown", "ignored") },
            { it.putNull("commandPlanSha256") },
            { it.remove("recoveryPlanSha256"); Unit },
            { (it["oldImageDigests"] as ObjectNode).put("game-api", "sha256:" + "f".repeat(64)) },
            { (it["newImageDigests"] as ObjectNode).put("web-game", "sha256:" + "f".repeat(64)) },
        ) + REFERENCES.map { id -> { node: ObjectNode -> node.put(id + "Sha256", "f".repeat(64)); Unit } }
        for ((index, change) in changes.withIndex()) {
            val packet = Packet(changeCard = change)
            assertThrows<D101PurposeAuthorityUnavailable>("card case $index") {
                packet.checks().getValue("deploymentCard").verify(packet.originals.getValue("deploymentCard"), packet.intent)
            }
        }
    }

    @Test
    fun `valid independently decoded intent from another source cannot substitute for pinned scope`() {
        val packet = Packet()
        val otherTree = packet.f.mapper.readTree(packet.originals.getValue("approvalIntent")) as ObjectNode
        otherTree.put("initialPublicRevision", "2")
        val other = packet.f.intent(otherTree)
        for (id in listOf("approvalIntent", "deploymentCard")) {
            assertThrows<D101PurposeAuthorityUnavailable>(id) {
                packet.checks().getValue(id).verify(packet.originals.getValue(id), other)
            }
        }
    }

    @Test
    fun `raw original mismatch and mutable caller data cannot replace the frozen source`() {
        val packet = Packet()
        val checks = packet.checks()
        val intentOriginal = packet.originals.getValue("approvalIntent").copyOf()
        packet.originals.getValue("approvalIntent").fill(0)
        checks.getValue("approvalIntent").verify(intentOriginal, packet.intent)
        assertThrows<D101PurposeAuthorityUnavailable> {
            checks.getValue("approvalIntent").verify(packet.originals.getValue("approvalIntent"), packet.intent)
        }
        assertThrows<UnsupportedOperationException> {
            (checks as MutableMap<String, D101HostOriginalSemanticCheck>).remove("reviewBasis")
        }
    }

    @Test
    fun `intent receipt labels must equal actual original bytes even under a rebound manifest`() {
        val packet = Packet(changeIntent = { it.put("spaceInventoryReceiptSha256", "f".repeat(64)) })
        assertThrows<D101PurposeAuthorityUnavailable> {
            packet.checks().getValue("approvalIntent").verify(packet.originals.getValue("approvalIntent"), packet.intent)
        }
    }

    @Test
    fun `missing host manifest or an original never creates the fixed registry`() {
        val packet = Packet()
        for (id in D101ApprovedPurposeAuthority.ORIGINAL_IDS + "trustManifest") {
            val candidate = packet.originals + mapOf("trustManifest" to packet.manifestWire)
            assertThrows<D101PurposeAuthorityUnavailable>(id) {
                D101InstalledSemanticChecks(D101VerifiedHostOriginals(candidate - id), packet.pins())
            }
        }
    }

    @Test
    fun `duplicate trailing unknown and null card fields reject despite exact raw hash binding`() {
        val malformed: List<(String) -> String> = listOf<(String) -> String>(
            { it.replaceFirst("{", "{\"schemaVersion\":1,") },
            { it + " {}" },
            { it.replaceFirst("{", "{\"extra\":true,") },
            { it.replace("\"schemaVersion\":1", "\"schemaVersion\":null") },
        )
        for ((index, change) in malformed.withIndex()) {
            val packet = Packet(changeCardWire = { change(it.toString(Charsets.UTF_8)).toByteArray() })
            assertThrows<D101PurposeAuthorityUnavailable>("wire case $index") {
                packet.checks().getValue("deploymentCard").verify(packet.originals.getValue("deploymentCard"), packet.intent)
            }
        }
    }

    @Test
    fun `provenance can bind completed intent and card without upstream descendant hashes`() {
        val packet = Packet(provenanceAfterCard = true)
        val checks = packet.checks()
        for (id in listOf("approvalIntent", "deploymentCard")) {
            checks.getValue(id).verify(packet.originals.getValue(id), packet.intent)
        }
        val provenance = packet.f.mapper.readTree(packet.originals.getValue("approvedReceiptProvenance"))
        assertEquals(packet.intent.sha256, provenance["syntheticIntentOriginalSha256"].textValue())
        assertEquals(D101Fixture.hash(packet.originals.getValue("deploymentCard")),
            provenance["syntheticCardOriginalSha256"].textValue())
        // This is an assembly fixture, not an approved provenance schema or
        // real issuer. Matching final hashes cannot install its validator.
        assertThrows<D101PurposeAuthorityUnavailable> {
            checks.getValue("approvedReceiptProvenance").verify(packet.originals.getValue("approvedReceiptProvenance"), packet.intent)
        }
        assertThrows<D101PurposeAuthorityUnavailable> {
            D101HostSemanticVerifier(packet.f.codec, packet.intent.sha256, checks).verifyOriginals(packet.verified())
        }
    }

    private class Packet(
        changeCard: (ObjectNode) -> Unit = {},
        changeIntent: (ObjectNode) -> Unit = {},
        changeCardWire: (ByteArray) -> ByteArray = { it },
        provenanceAfterCard: Boolean = false,
    ) {
        val f = D101Fixture()
        val originals = D101ApprovedPurposeAuthority.ORIGINAL_IDS.associateWith { id ->
            f.mapper.writeValueAsBytes(mapOf("syntheticNotProduced" to id))
        }.toMutableMap()
        val intent: D101ApprovalIntent
        val manifestWire: ByteArray

        init {
            val tree = f.intentTree()
            for (id in RECEIPTS) tree.put(id + "Sha256", D101Fixture.hash(originals.getValue(id)))
            changeIntent(tree)
            originals["approvalIntent"] = f.mapper.writeValueAsBytes(tree)
            intent = f.codec.decode(originals.getValue("approvalIntent"), D101Fixture.hash(originals.getValue("approvalIntent")))
            val card: ObjectNode = f.mapper.valueToTree(linkedMapOf(
                "schemaVersion" to 1, "kind" to "D101_PEP_EXECUTION_CARD", "operationId" to f.operation,
                "approvalIntentSha256" to intent.sha256, "appSourceSha" to f.app, "dockerSourceSha" to "c".repeat(40),
                "oldImageDigests" to f.pins, "newImageDigests" to f.pins,
            ))
            for (id in REFERENCES) card.put(id + "Sha256", D101Fixture.hash(originals.getValue(id)))
            changeCard(card)
            originals["deploymentCard"] = changeCardWire(f.mapper.writeValueAsBytes(card))
            if (provenanceAfterCard) {
                originals["approvedReceiptProvenance"] = f.mapper.writeValueAsBytes(linkedMapOf(
                    "syntheticNotProduced" to "approvedReceiptProvenance",
                    "syntheticIntentOriginalSha256" to intent.sha256,
                    "syntheticCardOriginalSha256" to D101Fixture.hash(originals.getValue("deploymentCard")),
                ))
            }
            manifestWire = f.mapper.writeValueAsBytes(linkedMapOf(
                "schemaVersion" to 1, "kind" to "D101_HOST_TRUST_V1", "operationId" to f.operation,
                "approvalIntentSha256" to intent.sha256,
                "deploymentCardSha256" to D101Fixture.hash(originals.getValue("deploymentCard")),
                "approvedReceiptProvenanceSha256" to D101Fixture.hash(originals.getValue("approvedReceiptProvenance")),
                "keyId" to "rfc8032-fixture", "publicKeySpkiSha256" to D101Fixture.hash(f.publicDer),
                "signingKeyEnvelopeSha256" to "e".repeat(64), "rootPrivateOrigin" to "http://deployer:8080",
                "appSourceSha" to f.app, "dockerSourceSha" to "c".repeat(40),
                "oldImageDigests" to f.pins, "newImageDigests" to f.pins,
            ))
        }

        fun verified() = D101VerifiedHostOriginals(originals + mapOf("trustManifest" to manifestWire))
        fun pins() = D101DeploymentTrustPins(f.operation, intent.sha256, D101Fixture.hash(manifestWire),
            URI("http://deployer:8080"), "rfc8032-fixture", f.publicDer, D101Fixture.hash(f.publicDer),
            f.publicDer, D101Fixture.hash(f.publicDer), "e".repeat(64))
        fun checks() = D101InstalledSemanticChecks(verified(), pins(), f.mapper).fixedChecks()
    }

    companion object {
        private val REFERENCES = listOf("configInventory", "commandPlan", "recoveryPlan", "readerBindings", "evidenceCatalog", "reviewBasis")
        private val RECEIPTS = listOf("approvalReceipt", "combinedCiReceipt", "selectedSourceReceipt", "isolatedSeedTickReceipt", "spaceInventoryReceipt")
    }
}

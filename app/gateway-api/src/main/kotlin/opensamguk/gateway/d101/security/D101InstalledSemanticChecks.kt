package opensamguk.gateway.d101.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.*
import java.util.Collections

/** Fixed partial consumer of the originals already authenticated by the host
 * authority. This does not authenticate a host, issue approval, or install a
 * production provider. Twelve missing producer contracts deliberately deny.
 * No caller registry, Boolean callback, environment switch or success default.
 */
internal class D101InstalledSemanticChecks(
    originals: D101VerifiedHostOriginals,
    private val pins: D101DeploymentTrustPins,
    mapper: ObjectMapper = ObjectMapper(),
) {
    private val json = D101StrictJson(mapper)
    private val codec = D101ApprovalIntentCodec(json)
    private val frozen = D101ApprovedPurposeAuthority.ORIGINAL_IDS.associateWith { id ->
        originals.original(id).also { if (it.isEmpty() || it.size > 64 * 1024) unavailable() }
    }
    private val manifestWire = originals.original("trustManifest")

    /** Exact registry shape is necessary but never sufficient for readiness. */
    fun fixedChecks(): Map<String, D101HostOriginalSemanticCheck> = Collections.unmodifiableMap(
        D101ApprovedPurposeAuthority.ORIGINAL_IDS.associateWith { id ->
            D101HostOriginalSemanticCheck { original, intent ->
                try {
                    if (!original.contentEquals(frozen.getValue(id))) unavailable()
                    when (id) {
                        "approvalIntent" -> verifyIntent(original, intent)
                        "deploymentCard" -> verifyCard(original, intent)
                        else -> unavailable()
                    }
                } catch (_: Exception) {
                    unavailable()
                }
            }
        },
    )

    private fun verifyIntent(original: ByteArray, supplied: D101ApprovalIntent) {
        val decoded = codec.decode(original, pins.approvalIntentSha256)
        requireSameIntent(decoded, supplied)
        manifest(decoded)
        val receipts = mapOf(
            "approvalReceipt" to decoded.approvalReceiptSha256,
            "combinedCiReceipt" to decoded.combinedCiReceiptSha256,
            "selectedSourceReceipt" to decoded.selectedSourceReceiptSha256,
            "isolatedSeedTickReceipt" to decoded.isolatedSeedTickReceiptSha256,
            "spaceInventoryReceipt" to decoded.spaceInventoryReceiptSha256,
        )
        for ((id, expected) in receipts) {
            if (D101StrictJson.hash(frozen.getValue(id)) != expected) unavailable()
        }
        // Receipt hashes establish byte binding only. Each receipt's separate
        // fixed validator must still prove its actual producer and semantics.
    }

    private fun verifyCard(original: ByteArray, supplied: D101ApprovalIntent) {
        val intent = codec.decode(frozen.getValue("approvalIntent"), pins.approvalIntentSha256)
        requireSameIntent(intent, supplied)
        val manifest = manifest(intent)
        if (D101StrictJson.hash(original) != json.sha(manifest["deploymentCardSha256"])) unavailable()
        val card = json.objectBytes(original, CARD_KEYS, 32 * 1024)
        if (json.positiveLong(card["schemaVersion"]) != 1L ||
            json.text(card["kind"]) != "D101_PEP_EXECUTION_CARD" ||
            json.text(card["operationId"]) != pins.operationId ||
            json.sha(card["approvalIntentSha256"]) != intent.sha256 ||
            json.text(card["appSourceSha"]) != intent.appSourceSha ||
            json.text(card["dockerSourceSha"]) != json.text(manifest["dockerSourceSha"]) ||
            json.stringMap(card["oldImageDigests"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.oldImageDigests ||
            json.stringMap(card["newImageDigests"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.newImageDigests) unavailable()
        for (id in CARD_REFERENCES) {
            if (json.sha(card[id + "Sha256"]) != D101StrictJson.hash(frozen.getValue(id))) unavailable()
        }
    }

    private fun manifest(intent: D101ApprovalIntent): JsonNode {
        if (D101StrictJson.hash(manifestWire) != pins.manifestSha256 ||
            intent.operationId != pins.operationId) unavailable()
        val node = json.objectBytes(manifestWire, MANIFEST_KEYS, 32 * 1024)
        if (json.positiveLong(node["schemaVersion"]) != 1L ||
            json.text(node["kind"]) != "D101_HOST_TRUST_V1" ||
            json.text(node["operationId"]) != intent.operationId ||
            json.sha(node["approvalIntentSha256"]) != intent.sha256 ||
            json.text(node["appSourceSha"]) != intent.appSourceSha ||
            !D101StrictJson.SHA40.matches(json.text(node["dockerSourceSha"])) ||
            json.text(node["rootPrivateOrigin"]) != pins.fixedPrivateOrigin.toString() ||
            json.text(node["keyId"]) != pins.keyId ||
            json.sha(node["publicKeySpkiSha256"]) != pins.purposeSpkiSha256 ||
            json.sha(node["signingKeyEnvelopeSha256"]) != pins.signingKeyEnvelopeSha256 ||
            json.sha(node["approvedReceiptProvenanceSha256"]) != D101StrictJson.hash(frozen.getValue("approvedReceiptProvenance")) ||
            json.stringMap(node["oldImageDigests"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.oldImageDigests ||
            json.stringMap(node["newImageDigests"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.newImageDigests) unavailable()
        return node
    }

    private fun requireSameIntent(actual: D101ApprovalIntent, supplied: D101ApprovalIntent) {
        if (actual.sha256 != supplied.sha256 || actual.operationId != supplied.operationId ||
            actual.targetFingerprint != supplied.targetFingerprint || actual.appSourceSha != supplied.appSourceSha ||
            actual.initialPublicRevision != supplied.initialPublicRevision ||
            actual.windowOpensAtUnix != supplied.windowOpensAtUnix ||
            actual.destructiveCutoffUnix != supplied.destructiveCutoffUnix || actual.recoveryDeadlineUnix != supplied.recoveryDeadlineUnix ||
            actual.approvalReceiptSha256 != supplied.approvalReceiptSha256 ||
            actual.combinedCiReceiptSha256 != supplied.combinedCiReceiptSha256 ||
            actual.selectedSourceReceiptSha256 != supplied.selectedSourceReceiptSha256 ||
            actual.isolatedSeedTickReceiptSha256 != supplied.isolatedSeedTickReceiptSha256 ||
            actual.spaceInventoryReceiptSha256 != supplied.spaceInventoryReceiptSha256 ||
            actual.spaceBudget != supplied.spaceBudget || actual.oldImageDigests != supplied.oldImageDigests ||
            actual.newImageDigests != supplied.newImageDigests ||
            !actual.target.originalBytes().contentEquals(supplied.target.originalBytes()) ||
            actual.target.imageDigests != supplied.target.imageDigests ||
            actual.target.storageImageDigests != supplied.target.storageImageDigests ||
            actual.target.updates != supplied.target.updates) unavailable()
    }

    private fun unavailable(): Nothing = throw D101PurposeAuthorityUnavailable()

    companion object {
        private val CARD_REFERENCES = setOf(
            "configInventory", "commandPlan", "recoveryPlan", "readerBindings", "evidenceCatalog", "reviewBasis",
        )
        private val CARD_KEYS = setOf(
            "schemaVersion", "kind", "operationId", "approvalIntentSha256", "appSourceSha", "dockerSourceSha",
            "oldImageDigests", "newImageDigests", "configInventorySha256", "commandPlanSha256", "recoveryPlanSha256",
            "readerBindingsSha256", "evidenceCatalogSha256", "reviewBasisSha256",
        )
        private val MANIFEST_KEYS = setOf(
            "schemaVersion", "kind", "operationId", "approvalIntentSha256", "deploymentCardSha256",
            "approvedReceiptProvenanceSha256", "keyId", "publicKeySpkiSha256", "signingKeyEnvelopeSha256",
            "rootPrivateOrigin", "appSourceSha", "dockerSourceSha", "oldImageDigests", "newImageDigests",
        )
    }
}

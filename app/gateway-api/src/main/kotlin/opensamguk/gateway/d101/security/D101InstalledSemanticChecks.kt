package opensamguk.gateway.d101.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.*
import java.nio.file.Path
import java.util.Collections

/** Fixed partial consumer of the originals already authenticated by the host
 * authority. This does not authenticate a host, issue approval, or install a
 * production provider. Twelve unresolved producer/custody consumers deliberately deny.
 * No caller registry, Boolean callback, environment switch or success default.
 * Receipt5 -> intent; intent/reference6 -> card; intent/card/provenance ->
 * signed manifest. References point to already frozen originals. Scope is
 * checked independently; this consumer never requires descendant hashes in
 * an upstream leaf. Missing leaf schemas remain closed, not inferred here.
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
                        "commandPlan" -> {
                            validateCommandPlanScope(original, intent)
                            // Native Compose originals, the actual DB reader and
                            // signed selected envelope have not been supplied.
                            // Shape/scope/SHA labels cannot open this validator.
                            unavailable()
                        }
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

    /** Scope-only subcheck of Root source 65d97c15 exact15. A successful
     * return is not semantic approval: fixedChecks still denies commandPlan
     * until actual native supporting originals have an approved consumer.
     */
    internal fun validateCommandPlanScope(original: ByteArray, supplied: D101ApprovalIntent) {
        try {
            if (!original.contentEquals(frozen.getValue("commandPlan"))) unavailable()
            val intent = codec.decode(frozen.getValue("approvalIntent"), pins.approvalIntentSha256)
            requireSameIntent(intent, supplied)
            verifyCard(frozen.getValue("deploymentCard"), intent)
            val node = json.objectBytes(original, COMMAND_KEYS, 32 * 1024)
            if (json.positiveLong(node["schemaVersion"]) != 1L ||
                json.text(node["kind"]) != "D101_ROOT_CANDIDATE_COMMAND_PLAN_V1" ||
                json.text(node["operationId"]) != intent.operationId ||
                json.sha(node["approvalIntentSha256"]) != intent.sha256 ||
                json.sha(node["targetFingerprint"]) != intent.targetFingerprint ||
                json.text(node["appSourceSha"]) != intent.appSourceSha ||
                json.text(node["dockerSourceSha"]) != json.text(manifest(intent)["dockerSourceSha"]) ||
                json.stringMap(node["newImageDigests"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.newImageDigests ||
                json.sha(node["selectedSourceReceiptSha256"]) != intent.selectedSourceReceiptSha256 ||
                json.text(node["seedEntrypoint"]) != "opensamguk.engine.boot.D101SeedOnlyCli" ||
                json.positiveLong(node["destructiveCutoffUnix"]) != intent.destructiveCutoffUnix) unavailable()
            // These are only labels until separately verified actual originals
            // and native custody are supplied. They never become success facts.
            json.sha(node["selectedEnvelopeSha256"])
            json.sha(node["capsReaderSha256"])
            val stages = node["stages"]
            if (!stages.isArray || stages.map { json.text(it) } != COMMAND_STAGES) unavailable()
            commandResources(node["resources"], intent.operationId)
        } catch (_: Exception) {
            unavailable()
        }
    }

    private fun commandResources(node: JsonNode, operationId: String) {
        json.requireKeys(node, RESOURCE_KEYS)
        val prefix = "d101-candidate-" + operationId
        val fixed = mapOf("project" to prefix, "network" to prefix + "-net",
            "postgresVolume" to prefix + "-pgdata", "redisVolume" to prefix + "-redisdata")
        if (fixed.any { (key, value) -> json.text(node[key]) != value }) unavailable()
        fun absoluteClean(key: String): String {
            val value = json.text(node[key])
            val path = Path.of(value)
            if (!path.isAbsolute || path.normalize().toString() != value) unavailable()
            return value
        }
        if (absoluteClean("candidateComposeFile") == absoluteClean("liveComposeFile")) unavailable()
        json.sha(node["candidateComposeSha256"])
        json.sha(node["liveComposeSha256"])
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
        private val COMMAND_KEYS = setOf(
            "schemaVersion", "kind", "operationId", "approvalIntentSha256", "targetFingerprint", "appSourceSha",
            "dockerSourceSha", "newImageDigests", "selectedSourceReceiptSha256", "selectedEnvelopeSha256",
            "capsReaderSha256", "seedEntrypoint", "stages", "destructiveCutoffUnix", "resources",
        )
        private val RESOURCE_KEYS = setOf(
            "project", "network", "postgresVolume", "redisVolume", "candidateComposeFile",
            "candidateComposeSha256", "liveComposeFile", "liveComposeSha256",
        )
        private val COMMAND_STAGES = listOf(
            "verify-current-authority-dispatch-freeze-space", "pull-pinned-candidate", "journal-before-env-down",
            "down-original-stack-once", "candidate-postgres-redis", "seed-only-child-exit-zero",
            "independent-both-db-numeric-50", "immutable-promotion-proof", "live-api-engine-web", "actual-runtime-observation",
        )
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

package opensamguk.gateway.d101.security

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.*
import java.net.URI
import java.time.Clock
import java.util.Collections

/** Fixed installation inputs, supplied outside request/env configuration.
 * Original DER pins originate in independent approved installation custody. */
internal class D101DeploymentTrustPins(
    val operationId: String,
    val approvalIntentSha256: String,
    val manifestSha256: String,
    val fixedPrivateOrigin: URI,
    val keyId: String,
    approvalAnchorSpki: ByteArray,
    val approvalAnchorSpkiSha256: String,
    purposeSpki: ByteArray,
    val purposeSpkiSha256: String,
    val signingKeyEnvelopeSha256: String,
) {
    private val anchor = approvalAnchorSpki.copyOf()
    private val purpose = purposeSpki.copyOf()
    fun anchorSpki(): ByteArray = anchor.copyOf()
    fun purposeSpki(): ByteArray = purpose.copyOf()
}

/** Fixed host reader must enforce native custody/bounded transport. A source
 * cannot select a path, origin, key or token from a request's intent SHA. */
internal interface D101FixedHostTrustSource {
    fun readOriginals(): D101HostTrustOriginals
    fun rootToken(): String
}

internal class D101HostTrustOriginals(
    manifestEnvelope: ByteArray,
    clockEnvelope: ByteArray,
    originals: Map<String, ByteArray>,
) {
    private val manifest = manifestEnvelope.copyOf()
    private val clock = clockEnvelope.copyOf()
    private val bytes = originals.mapValues { it.value.copyOf() }
    fun manifestEnvelope(): ByteArray = manifest.copyOf()
    fun clockEnvelope(): ByteArray = clock.copyOf()
    fun originals(): Map<String, ByteArray> = bytes.mapValues { it.value.copyOf() }
}

/** Actual producer checks remain mandatory: approval lineage, selected raw5,
 * options, runtime source/images and C4/card reference semantics. No success
 * default; signatures and matching SHA labels do not substitute for these. */
internal fun interface D101HostEvidenceVerifier {
    fun verifyOriginals(originals: D101VerifiedHostOriginals)
}

internal class D101VerifiedHostOriginals(originals: Map<String, ByteArray>) {
    private val bytes = originals.mapValues { it.value.copyOf() }
    fun original(id: String): ByteArray = (bytes[id] ?: unavailable()).copyOf()
    private fun unavailable(): Nothing = throw D101PurposeAuthorityUnavailable()
}

/** Implements crypto/raw bindings and fresh clock checks, retaining the closed
 * default while the actual fixed host reader or semantic producer is absent. */
internal class D101ApprovedPurposeAuthority(
    private val pins: D101DeploymentTrustPins,
    private val source: D101FixedHostTrustSource,
    private val evidenceVerifier: D101HostEvidenceVerifier?,
    private val clock: Clock = Clock.systemUTC(),
    mapper: ObjectMapper = ObjectMapper(),
) : D101PurposeAuthority {
    private val json = D101StrictJson(mapper)
    private val intentCodec = D101ApprovalIntentCodec(json)

    override fun readVerified(approvalIntentSha256: String): D101VerifiedPurposeAuthority {
        try {
            val verifier = evidenceVerifier ?: unavailable()
            if (approvalIntentSha256 != pins.approvalIntentSha256 ||
                !D101StrictJson.OPERATION.matches(pins.operationId) ||
                !D101StrictJson.SHA.matches(pins.manifestSha256) ||
                !D101StrictJson.KEY_ID.matches(pins.keyId) ||
                !D101StrictJson.SHA.matches(pins.signingKeyEnvelopeSha256)) unavailable()
            requireD101FixedPrivateOrigin(pins.fixedPrivateOrigin)
            val anchor = D101Ed25519.decodePublicKey(pins.anchorSpki(), pins.approvalAnchorSpkiSha256)
            D101Ed25519.decodePublicKey(pins.purposeSpki(), pins.purposeSpkiSha256)
            val started = clock.instant().epochSecond
            val startedNanos = System.nanoTime()
            val supplied = source.readOriginals()
            val originals = supplied.originals()
            if (originals.keys != ORIGINAL_IDS) unavailable()
            val intentWire = originals.getValue("approvalIntent")
            val intent = intentCodec.decode(intentWire, pins.approvalIntentSha256)
            if (intent.operationId != pins.operationId) unavailable()
            fun signedOriginal(envelope: ByteArray, domain: String): ByteArray {
                val fields = json.objectBytes(envelope, ENVELOPE_KEYS, 64 * 1024)
                if (json.positiveLong(fields["schemaVersion"]) != 1L) unavailable()
                val original = json.base64url(json.text(fields["originalBytesBase64url"]), 32 * 1024)
                val signature = json.base64url(json.text(fields["signatureBase64url"]), 64)
                if (signature.size != 64 ||
                    !D101Ed25519.verify(anchor, domain.toByteArray(Charsets.US_ASCII) + original, signature)) unavailable()
                return original
            }
            val manifestWire = signedOriginal(supplied.manifestEnvelope(), TRUST_DOMAIN)
            if (D101StrictJson.hash(manifestWire) != pins.manifestSha256) unavailable()
            val manifest = json.objectBytes(manifestWire, MANIFEST_KEYS, 32 * 1024)
            if (json.positiveLong(manifest["schemaVersion"]) != 1L ||
                json.text(manifest["kind"]) != "D101_HOST_TRUST_V1" ||
                json.text(manifest["operationId"]) != intent.operationId ||
                json.sha(manifest["approvalIntentSha256"]) != intent.sha256 ||
                json.text(manifest["rootPrivateOrigin"]) != pins.fixedPrivateOrigin.toString() ||
                json.text(manifest["appSourceSha"]) != intent.appSourceSha ||
                json.text(manifest["keyId"]) != pins.keyId ||
                json.sha(manifest["publicKeySpkiSha256"]) != pins.purposeSpkiSha256 ||
                json.sha(manifest["signingKeyEnvelopeSha256"]) != pins.signingKeyEnvelopeSha256 ||
                json.stringMap(manifest["oldImageDigests"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.oldImageDigests ||
                json.stringMap(manifest["newImageDigests"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.newImageDigests) unavailable()
            val docker = json.text(manifest["dockerSourceSha"])
            if (!D101StrictJson.SHA40.matches(docker)) unavailable()
            val cardSha = json.sha(manifest["deploymentCardSha256"])
            val cardWire = originals.getValue("deploymentCard")
            if (D101StrictJson.hash(cardWire) != cardSha) unavailable()
            val card = json.objectBytes(cardWire, CARD_KEYS, 32 * 1024)
            if (json.positiveLong(card["schemaVersion"]) != 1L ||
                json.text(card["kind"]) != "D101_PEP_EXECUTION_CARD" ||
                json.text(card["operationId"]) != intent.operationId ||
                json.sha(card["approvalIntentSha256"]) != intent.sha256 ||
                json.text(card["appSourceSha"]) != intent.appSourceSha ||
                json.text(card["dockerSourceSha"]) != docker ||
                json.stringMap(card["oldImageDigests"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.oldImageDigests ||
                json.stringMap(card["newImageDigests"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.newImageDigests) unavailable()
            val provenanceSha = json.sha(manifest["approvedReceiptProvenanceSha256"])
            val expected = mapOf(
                "approvalIntent" to intent.sha256, "deploymentCard" to cardSha,
                "approvalReceipt" to intent.approvalReceiptSha256,
                "combinedCiReceipt" to intent.combinedCiReceiptSha256,
                "selectedSourceReceipt" to intent.selectedSourceReceiptSha256,
                "isolatedSeedTickReceipt" to intent.isolatedSeedTickReceiptSha256,
                "spaceInventoryReceipt" to intent.spaceInventoryReceiptSha256,
                "configInventory" to json.sha(card["configInventorySha256"]),
                "commandPlan" to json.sha(card["commandPlanSha256"]),
                "recoveryPlan" to json.sha(card["recoveryPlanSha256"]),
                "readerBindings" to json.sha(card["readerBindingsSha256"]),
                "evidenceCatalog" to json.sha(card["evidenceCatalogSha256"]),
                "reviewBasis" to json.sha(card["reviewBasisSha256"]),
                "approvedReceiptProvenance" to provenanceSha,
            )
            for ((id, sha) in expected) {
                val original = originals.getValue(id)
                if (original.isEmpty() || original.size > 64 * 1024 || D101StrictJson.hash(original) != sha) unavailable()
            }
            val clockWire = signedOriginal(supplied.clockEnvelope(), CLOCK_DOMAIN)
            val receipt = json.objectBytes(clockWire, CLOCK_KEYS, 32 * 1024)
            fun requireClock(now: Long) {
                val root = json.positiveLong(receipt["rootObservedAtUnix"])
                val gateway = json.positiveLong(receipt["gatewayObservedAtUnix"])
                val expires = json.positiveLong(receipt["expiresAtUnix"])
                if (json.positiveLong(receipt["schemaVersion"]) != 1L ||
                    json.text(receipt["kind"]) != "D101_CLOCK_AGREEMENT_V1" ||
                    json.text(receipt["operationId"]) != intent.operationId ||
                    json.sha(receipt["approvalIntentSha256"]) != intent.sha256 ||
                    now <= 0 || root > now || gateway > now || now - root >= 30 || now - gateway >= 30 ||
                    root - gateway > 1 || gateway - root > 1 || expires <= root || expires <= gateway ||
                    expires - root > 30 || expires - gateway > 30 || now >= expires) unavailable()
            }
            requireClock(clock.instant().epochSecond)
            verifier.verifyOriginals(D101VerifiedHostOriginals(originals + mapOf(
                "trustManifest" to manifestWire, "clockAgreement" to clockWire)))
            val completed = clock.instant().epochSecond
            if (started <= 0 || completed < started || completed - started >= 30 ||
                System.nanoTime() - startedNanos >= java.util.concurrent.TimeUnit.SECONDS.toNanos(30)) unavailable()
            requireClock(completed)
            // Reparse original bytes after the verifier. No mutable intent maps
            // or provider-normalized key bytes are retained from its callback.
            return D101VerifiedPurposeAuthority(pins.keyId, pins.purposeSpki(), pins.purposeSpkiSha256,
                cardSha, provenanceSha, D101StrictJson.hash(clockWire),
                intentCodec.decode(intentWire, pins.approvalIntentSha256))
        } catch (_: Exception) {
            unavailable()
        }
    }

    private fun unavailable(): Nothing = throw D101PurposeAuthorityUnavailable()

    companion object {
        const val TRUST_DOMAIN = "OPENSAMGUK-D101-HOST-TRUST-V1\n"
        const val CLOCK_DOMAIN = "OPENSAMGUK-D101-CLOCK-AGREEMENT-V1\n"
        val ORIGINAL_IDS = Collections.unmodifiableSet(setOf("approvalIntent", "deploymentCard", "approvalReceipt", "combinedCiReceipt",
            "selectedSourceReceipt", "isolatedSeedTickReceipt", "spaceInventoryReceipt", "configInventory", "commandPlan",
            "recoveryPlan", "readerBindings", "evidenceCatalog", "reviewBasis", "approvedReceiptProvenance"))
        private val ENVELOPE_KEYS = setOf("schemaVersion", "originalBytesBase64url", "signatureBase64url")
        private val MANIFEST_KEYS = setOf("schemaVersion", "kind", "operationId", "approvalIntentSha256", "deploymentCardSha256",
            "approvedReceiptProvenanceSha256", "keyId", "publicKeySpkiSha256", "signingKeyEnvelopeSha256", "rootPrivateOrigin",
            "appSourceSha", "dockerSourceSha", "oldImageDigests", "newImageDigests")
        private val CARD_KEYS = setOf("schemaVersion", "kind", "operationId", "approvalIntentSha256", "appSourceSha", "dockerSourceSha",
            "oldImageDigests", "newImageDigests", "configInventorySha256", "commandPlanSha256", "recoveryPlanSha256",
            "readerBindingsSha256", "evidenceCatalogSha256", "reviewBasisSha256")
        private val CLOCK_KEYS = setOf("schemaVersion", "kind", "operationId", "approvalIntentSha256",
            "rootObservedAtUnix", "gatewayObservedAtUnix", "expiresAtUnix")
    }
}

internal fun requireD101FixedPrivateOrigin(origin: URI) {
    if (!origin.isAbsolute || origin.scheme !in setOf("http", "https") ||
        origin.host !in setOf("deployer", "opensamguk-deployer", "localhost", "127.0.0.1", "[::1]") ||
        origin.port !in 1..65535 || origin.rawUserInfo != null || origin.rawQuery != null ||
        origin.rawFragment != null || origin.rawPath !in setOf("", "/")) throw D101PurposeAuthorityUnavailable()
}

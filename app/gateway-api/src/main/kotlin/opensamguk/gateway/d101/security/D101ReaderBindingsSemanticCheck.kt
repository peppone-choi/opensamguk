package opensamguk.gateway.d101.security

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.infra.D101NativeHostTrustSource
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.TimeUnit

/** Consumes the existing native installation7. Logical references are not
 * custody facts: the fixed native source must reread the same original14 and
 * actual signed manifest/clock before and after this check. Never opens paths
 * or token files, installs a helper, or replaces the independent host pins. */
internal class D101ReaderBindingsSemanticCheck(
    originals: D101VerifiedHostOriginals,
    private val pins: D101DeploymentTrustPins,
    private val fixedSource: D101NativeHostTrustSource?,
    mapper: ObjectMapper = ObjectMapper(),
    private val clock: Clock = Clock.systemUTC(),
) : D101HostOriginalSemanticCheck {
    private val json = D101StrictJson(mapper)
    private val frozen = D101ApprovedPurposeAuthority.ORIGINAL_IDS.associateWith { originals.original(it) }
    private val manifest = originals.original("trustManifest")
    private val agreement = originals.original("clockAgreement")

    override fun verify(original: ByteArray, intent: D101ApprovalIntent) {
        try {
            val source = fixedSource ?: unavailable()
            val started = clock.instant().epochSecond
            val monotonic = System.nanoTime()
            if (!original.contentEquals(frozen.getValue("readerBindings")) ||
                intent.sha256 != pins.approvalIntentSha256 || intent.operationId != pins.operationId ||
                D101StrictJson.hash(frozen.getValue("approvalIntent")) != intent.sha256 ||
                D101StrictJson.hash(manifest) != pins.manifestSha256) unavailable()
            val binding = json.objectBytes(original, KEYS, 64 * 1024)
            if (json.positiveLong(binding["schemaVersion"]) != 1L ||
                json.text(binding["kind"]) != "D101_NATIVE_READER_BINDINGS_V1") unavailable()
            val files = json.stringMap(binding["originalFiles"], D101ApprovedPurposeAuthority.ORIGINAL_IDS, false)
            // Native Go pins the installed bytes/SHA, not a pathname alias.
            // A separate private copy with identical bytes is allowed.
            for (path in files.values) requirePrivateFileReference(path)
            for (key in PATH_KEYS) requirePrivateFileReference(json.text(binding[key]))

            fun sameNativeSnapshot() {
                val actual = source.readOriginals()
                val wires = actual.originals()
                if (wires.keys != frozen.keys || wires.any { (id, wire) ->
                        !wire.contentEquals(frozen.getValue(id)) }) unavailable()
                requireSignedOriginal(actual.manifestEnvelope(), D101ApprovedPurposeAuthority.TRUST_DOMAIN, manifest)
                requireSignedOriginal(actual.clockEnvelope(), D101ApprovedPurposeAuthority.CLOCK_DOMAIN, agreement)
                requireClock(intent)
            }
            sameNativeSnapshot()
            // Revalidate after the native read; no mutable caller map is kept.
            sameNativeSnapshot()
            val completed = clock.instant().epochSecond
            if (started <= 0 || completed < started || completed - started >= 30 ||
                System.nanoTime() - monotonic >= TimeUnit.SECONDS.toNanos(30)) unavailable()
            requireClock(intent)
        } catch (_: Exception) {
            unavailable()
        }
    }

    private fun requirePrivateFileReference(value: String) {
        val path = Path.of(value)
        if (!path.isAbsolute || path.normalize().toString() != value || path.fileName == null) unavailable()
        // UID/mode/nlink/FD/NOFOLLOW are the actual fixed native reader's job.
        // A clean pathname alone never makes a custody or existence assertion.
    }

    private fun requireSignedOriginal(envelope: ByteArray, domain: String, expected: ByteArray) {
        val node = json.objectBytes(envelope, ENVELOPE_KEYS, 64 * 1024)
        if (json.positiveLong(node["schemaVersion"]) != 1L) unavailable()
        val wire = json.base64url(json.text(node["originalBytesBase64url"]), 32 * 1024)
        val signature = json.base64url(json.text(node["signatureBase64url"]), 64)
        val anchor = D101Ed25519.decodePublicKey(pins.anchorSpki(), pins.approvalAnchorSpkiSha256)
        if (!wire.contentEquals(expected) || signature.size != 64 ||
            !D101Ed25519.verify(anchor, domain.toByteArray(Charsets.US_ASCII) + wire, signature)) unavailable()
    }

    private fun requireClock(intent: D101ApprovalIntent) {
        val node = json.objectBytes(agreement, CLOCK_KEYS, 32 * 1024)
        val root = json.positiveLong(node["rootObservedAtUnix"])
        val gateway = json.positiveLong(node["gatewayObservedAtUnix"])
        val expires = json.positiveLong(node["expiresAtUnix"])
        val now = clock.instant().epochSecond
        // Same 30-second/one-second bounds as the existing host authority;
        // checking a second snapshot cannot renew that original agreement.
        if (json.positiveLong(node["schemaVersion"]) != 1L ||
            json.text(node["kind"]) != "D101_CLOCK_AGREEMENT_V1" ||
            json.text(node["operationId"]) != intent.operationId ||
            json.sha(node["approvalIntentSha256"]) != intent.sha256 ||
            now <= 0 || root > now || gateway > now || now - root >= 30 || now - gateway >= 30 ||
            root - gateway > 1 || gateway - root > 1 || expires <= root || expires <= gateway ||
            expires - root > 30 || expires - gateway > 30 || now >= expires) unavailable()
    }

    private fun unavailable(): Nothing = throw D101PurposeAuthorityUnavailable()

    companion object {
        private val PATH_KEYS = setOf("manifestFile", "clockFile", "rootTokenFile", "selectedEnvelopeFile")
        private val KEYS = setOf("schemaVersion", "kind", "manifestFile", "clockFile", "originalFiles", "rootTokenFile", "selectedEnvelopeFile")
        private val ENVELOPE_KEYS = setOf("schemaVersion", "originalBytesBase64url", "signatureBase64url")
        private val CLOCK_KEYS = setOf("schemaVersion", "kind", "operationId", "approvalIntentSha256", "rootObservedAtUnix", "gatewayObservedAtUnix", "expiresAtUnix")
    }
}

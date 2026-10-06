package opensamguk.gateway.d101.security

import opensamguk.gateway.d101.domain.D101ApprovalIntent
import opensamguk.gateway.d101.domain.D101PurposeAction
import opensamguk.gateway.d101.domain.D101PurposeAuthorityUnavailable
import opensamguk.gateway.d101.domain.D101PurposeGrantInvalid
import opensamguk.gateway.d101.domain.D101RequestInvalid
import opensamguk.gateway.d101.domain.D101StrictJson
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.EdECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Clock

/**
 * The provider must verify approved-intent provenance, pinned deployment trust
 * and real clock agreement. Returning a caller key or SHA labels is insufficient.
 * Selected raw bytes and effective seed-option provenance must match the intent
 * and Root/C4 importer inputs; code defaults are not observed option provenance.
 * No production provider is implemented in slice A.
 */
internal fun interface D101PurposeAuthority {
    fun readVerified(approvalIntentSha256: String): D101VerifiedPurposeAuthority
}

internal class UnavailableD101PurposeAuthority : D101PurposeAuthority {
    override fun readVerified(approvalIntentSha256: String): D101VerifiedPurposeAuthority =
        throw D101PurposeAuthorityUnavailable()
}

internal class D101VerifiedPurposeAuthority(
    val keyId: String,
    publicKeySpki: ByteArray,
    val publicKeySpkiSha256: String,
    val deploymentCardSha256: String,
    val approvedReceiptProvenanceSha256: String,
    val clockAgreementReceiptSha256: String,
    val approvedIntent: D101ApprovalIntent,
) {
    private val originalSpki = publicKeySpki.copyOf()
    fun publicKeySpki(): ByteArray = originalSpki.copyOf()
}

/** Ingress token equality remains the existing filter's separate responsibility. */
internal class D101PurposeRequest(
    val action: D101PurposeAction,
    val operationId: String,
    val targetFingerprint: String,
    val approvalIntentSha256: String,
    val gatewayPayloadSha256: String,
    val initialPublicRevision: Long,
    val method: String,
    val path: String,
    body: ByteArray,
    val authorizationHeaderCount: Int = 1,
) {
    private val originalBody = body.copyOf()
    fun body(): ByteArray = originalBody.copyOf()
}

/**
 * Cryptographic purpose proof only, not a successful admission or state CAS.
 * Application code must validate durable state/replay and invoke the appropriate
 * window guard immediately before mutations. No API/worker calls this in slice A.
 */
internal class D101VerifiedPurposeGrant(
    val action: D101PurposeAction,
    val operationId: String,
    val targetFingerprint: String,
    val approvalIntentSha256: String,
    val gatewayPayloadSha256: String,
    val grantSha256: String,
    val initialPublicRevision: Long,
    private val approvedIntent: D101ApprovalIntent,
    private val issuedAtUnix: Long,
    private val expiresAtUnix: Long,
    private val clock: Clock,
) {
    fun requireNewExecutionWindow() {
        if (action != D101PurposeAction.PREPARE && action != D101PurposeAction.DISPATCH_INTENT) invalid()
        val now = freshNow()
        if (now < approvedIntent.windowOpensAtUnix || now >= approvedIntent.destructiveCutoffUnix) invalid()
    }

    fun requireSettlementWindow() {
        if (action != D101PurposeAction.SETTLE_REGISTRY) invalid()
        val now = freshNow()
        if (now < approvedIntent.windowOpensAtUnix || now >= approvedIntent.recoveryDeadlineUnix) invalid()
    }

    fun requireRecoveryWindow() {
        if (action !in setOf(D101PurposeAction.RECOVERY_BEGIN, D101PurposeAction.RECOVERY_CLOSE)) invalid()
        val now = freshNow()
        if (now < approvedIntent.windowOpensAtUnix || now >= approvedIntent.recoveryDeadlineUnix) invalid()
    }

    private fun freshNow(): Long {
        val now = clock.instant().epochSecond
        if (now <= 0 || now < issuedAtUnix || now >= expiresAtUnix) invalid()
        return now
    }

    private fun invalid(): Nothing = throw D101PurposeGrantInvalid()
}

internal class D101PurposeGrantVerifier(
    private val json: D101StrictJson,
    private val authority: D101PurposeAuthority = UnavailableD101PurposeAuthority(),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun verify(headers: List<String>, request: D101PurposeRequest): D101VerifiedPurposeGrant {
        try {
            if (headers.size != 1 || request.authorizationHeaderCount != 1) invalid()
            val header = headers.single()
            if (header.length > 8192 || header.any { it.code > 127 }) invalid()
            val parts = header.split('.')
            if (parts.size != 2) invalid()
            val claimsWire = json.base64url(parts[0], 4096)
            val signature = json.base64url(parts[1], 64)
            if (signature.size != 64) invalid()
            val claims = json.objectBytes(claimsWire, CLAIM_KEYS, 4096)
            val body = request.body()
            if (body.size > (if (request.action == D101PurposeAction.PREPARE) 64 * 1024 else 16 * 1024) ||
                request.method != request.action.method || request.path != request.action.path(request.operationId) ||
                (request.action == D101PurposeAction.QUERY && body.isNotEmpty()) ||
                !D101StrictJson.OPERATION.matches(request.operationId) ||
                !D101StrictJson.SHA.matches(request.targetFingerprint) ||
                !D101StrictJson.SHA.matches(request.approvalIntentSha256) ||
                !D101StrictJson.SHA.matches(request.gatewayPayloadSha256) || request.initialPublicRevision <= 0) invalid()
            val bodySha = D101StrictJson.hash(body)
            if (request.action == D101PurposeAction.PREPARE && request.gatewayPayloadSha256 != bodySha) invalid()
            if (json.positiveLong(claims["schemaVersion"]) != 1L ||
                json.text(claims["issuer"]) != "opensamguk-root-issuer" ||
                json.text(claims["subject"]) != "opensamguk-d101-root-executor" ||
                json.text(claims["audience"]) != "opensamguk-gateway-d101-v1" ||
                json.text(claims["purpose"]) != "D101_PEP_RESET" || json.text(claims["serverId"]) != "pep" ||
                json.text(claims["action"]) != request.action.name ||
                json.text(claims["operationId"]) != request.operationId ||
                json.sha(claims["targetFingerprint"]) != request.targetFingerprint ||
                json.sha(claims["approvalIntentSha256"]) != request.approvalIntentSha256 ||
                json.sha(claims["gatewayPayloadSha256"]) != request.gatewayPayloadSha256 ||
                json.revision(claims["initialPublicRevision"]) != request.initialPublicRevision ||
                json.text(claims["method"]) != request.method || json.text(claims["path"]) != request.path ||
                json.sha(claims["requestBodySha256"]) != bodySha ||
                !D101StrictJson.OPERATION.matches(json.text(claims["nonce"]))) invalid()
            val issued = json.positiveLong(claims["issuedAtUnix"])
            val expires = json.positiveLong(claims["expiresAtUnix"])
            if (expires <= issued || expires - issued > 60) invalid()
            requireFresh(issued, expires)
            val keyId = json.text(claims["keyId"])
            if (!D101StrictJson.KEY_ID.matches(keyId)) invalid()
            val trusted = try {
                authority.readVerified(request.approvalIntentSha256)
            } catch (_: Exception) {
                throw D101PurposeAuthorityUnavailable()
            }
            if (!D101StrictJson.KEY_ID.matches(trusted.keyId) ||
                !listOf(trusted.publicKeySpkiSha256, trusted.deploymentCardSha256,
                    trusted.approvedReceiptProvenanceSha256, trusted.clockAgreementReceiptSha256)
                    .all { D101StrictJson.SHA.matches(it) }) {
                throw D101PurposeAuthorityUnavailable()
            }
            val key = D101Ed25519.decodePublicKey(trusted.publicKeySpki(), trusted.publicKeySpkiSha256)
            val intent = trusted.approvedIntent
            if (trusted.keyId != keyId || intent.sha256 != request.approvalIntentSha256 ||
                intent.operationId != request.operationId || intent.targetFingerprint != request.targetFingerprint ||
                intent.initialPublicRevision != request.initialPublicRevision) invalid()
            if (!D101Ed25519.verify(key, DOMAIN + claimsWire, signature)) invalid()
            // The source/crypto checks cannot outlive the grant, even if a provider blocks.
            requireFresh(issued, expires)
            return D101VerifiedPurposeGrant(
                request.action, request.operationId, request.targetFingerprint, request.approvalIntentSha256,
                request.gatewayPayloadSha256, D101StrictJson.hash(claimsWire), request.initialPublicRevision, intent, issued, expires, clock,
            ).also {
                when (request.action) {
                    D101PurposeAction.PREPARE, D101PurposeAction.DISPATCH_INTENT -> it.requireNewExecutionWindow()
                    D101PurposeAction.SETTLE_REGISTRY -> it.requireSettlementWindow()
                    D101PurposeAction.RECOVERY_BEGIN, D101PurposeAction.RECOVERY_CLOSE -> it.requireRecoveryWindow()
                    D101PurposeAction.QUERY -> Unit
                }
            }
        } catch (_: D101RequestInvalid) {
            invalid()
        }
    }

    private fun requireFresh(issued: Long, expires: Long) {
        val now = clock.instant().epochSecond
        if (now <= 0 || issued > now || now >= expires) invalid()
    }

    private fun invalid(): Nothing = throw D101PurposeGrantInvalid()

    companion object {
        val DOMAIN: ByteArray get() = "OPENSAMGUK-D101-GRANT-V1\n".toByteArray(Charsets.US_ASCII)
        val CLAIM_KEYS = setOf(
            "schemaVersion", "keyId", "issuer", "subject", "audience", "purpose", "action", "serverId",
            "operationId", "targetFingerprint", "approvalIntentSha256", "gatewayPayloadSha256",
            "initialPublicRevision", "method", "path", "requestBodySha256", "issuedAtUnix", "expiresAtUnix", "nonce",
        )
    }
}

internal object D101Ed25519 {
    // RFC 8410 Ed25519 OID 1.3.101.112; absent parameters and 32 raw key bytes.
    // Compare/hash the original DER, never a provider's normalized re-encoding.
    private val SPKI_PREFIX = java.util.HexFormat.of().parseHex("302a300506032b6570032100")

    fun decodePublicKey(originalDer: ByteArray, expectedSha256: String): PublicKey {
        val wire = originalDer.copyOf()
        if (!D101StrictJson.SHA.matches(expectedSha256) || D101StrictJson.hash(wire) != expectedSha256 ||
            wire.size != SPKI_PREFIX.size + 32 || !wire.copyOfRange(0, SPKI_PREFIX.size).contentEquals(SPKI_PREFIX)) {
            throw D101PurposeAuthorityUnavailable()
        }
        return try {
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(wire)).also {
                if ((it as? EdECPublicKey)?.params?.name != "Ed25519") throw D101PurposeAuthorityUnavailable()
            }
        } catch (_: Exception) {
            throw D101PurposeAuthorityUnavailable()
        }
    }

    fun verify(key: PublicKey, message: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance("Ed25519").run {
            initVerify(key)
            update(message)
            verify(signature)
        }
    } catch (_: Exception) {
        false
    }
}

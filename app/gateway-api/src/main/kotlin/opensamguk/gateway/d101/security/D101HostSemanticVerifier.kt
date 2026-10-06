package opensamguk.gateway.d101.security

import opensamguk.gateway.d101.domain.*

/** A fixed approved decoder checks actual producer originals and scope.
 * This is never a caller-provided Boolean or a dynamic registry/env switch. */
internal fun interface D101HostOriginalSemanticCheck {
    fun verify(original: ByteArray, intent: D101ApprovalIntent)
}

/** All fourteen upstream semantics must have an actual installed validator.
 * Strict intent/card/signature/raw checks are also performed by the authority.
 * Unknown C9/C4/private-reference schemas cannot become a success default. */
internal class D101HostSemanticVerifier(
    private val intentCodec: D101ApprovalIntentCodec,
    private val approvalIntentSha256: String,
    fixedChecks: Map<String, D101HostOriginalSemanticCheck>,
): D101HostEvidenceVerifier {
    private val checks = fixedChecks.toMap()
    override fun verifyOriginals(originals: D101VerifiedHostOriginals) {
        if (checks.keys != D101ApprovedPurposeAuthority.ORIGINAL_IDS) throw D101PurposeAuthorityUnavailable()
        val wire=originals.original("approvalIntent")
        val intent=intentCodec.decode(wire,approvalIntentSha256)
        for (id in D101ApprovedPurposeAuthority.ORIGINAL_IDS.sorted()) {
            checks.getValue(id).verify(originals.original(id),intent)
        }
        // Validate again after potentially slow observers; no callback-mutated intent.
        intentCodec.decode(wire,approvalIntentSha256)
    }
}

package opensamguk.gateway.d101.security

import opensamguk.gateway.d101.domain.*
import java.time.Clock

/** Must read actual immutable plan/preflight, current V and Root phase provenance outside the DB lock. */
internal fun interface D101DispatchAuthority {
    fun readVerified(execution: D101Execution, candidate: D101DispatchIntentCandidate): D101VerifiedDispatch
}

internal class UnavailableD101DispatchAuthority : D101DispatchAuthority {
    override fun readVerified(execution: D101Execution, candidate: D101DispatchIntentCandidate): D101VerifiedDispatch =
        throw D101ObservationUnavailable()
}

internal data class D101VerifiedDispatch(
    val operationId: String,
    val intentSha256: String,
    val gatewayPayloadSha256: String,
    val targetFingerprint: String,
    val initialPublicRevision: Long,
    val candidate: D101DispatchIntentCandidate,
    val observedAtUnix: Long,
    val expiresAtUnix: Long,
    private val clock: Clock,
) {
    fun requireMatches(execution: D101Execution, request: D101DispatchIntentCandidate) {
        val now = clock.instant().epochSecond
        if (observedAtUnix <= 0 || observedAtUnix > now || expiresAtUnix <= observedAtUnix ||
            expiresAtUnix - observedAtUnix > 30 || now >= expiresAtUnix) throw D101ObservationUnavailable()
        if (operationId != execution.intent.operationId || intentSha256 != execution.intent.sha256 ||
            gatewayPayloadSha256 != execution.gatewayPayloadSha256 || targetFingerprint != execution.intent.targetFingerprint ||
            initialPublicRevision != execution.intent.initialPublicRevision || candidate != request ||
            candidate.verifyingRevision != execution.verifyingRevision) throw D101OperationConflict()
    }
}

package opensamguk.gateway.d101.infra

import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.D101DispatchAuthority
import opensamguk.gateway.d101.security.D101VerifiedDispatch
import java.time.Clock

/** Only independently verified Root PREPARED produces the DB dispatch projection. */
internal class D101VerifiedDispatchAuthorityAdapter(
    private val client: D101RootPreparedProofClient,
    private val clock: Clock = Clock.systemUTC(),
) : D101DispatchAuthority {
    override fun readVerified(execution: D101Execution, candidate: D101DispatchIntentCandidate): D101VerifiedDispatch {
        val proof = client.readVerified(execution, candidate)
        val intent = execution.intent
        return D101VerifiedDispatch(intent.operationId, intent.sha256, execution.gatewayPayloadSha256,
            intent.targetFingerprint, intent.initialPublicRevision, proof.candidate,
            proof.preparedAtUtc.epochSecond, minOf(proof.preparedAtUtc.epochSecond + 30, intent.destructiveCutoffUnix), clock)
            .also { it.requireMatches(execution, candidate) }
    }
}

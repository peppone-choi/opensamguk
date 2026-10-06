package opensamguk.gateway.d101.infra

import opensamguk.gateway.d101.domain.D101Execution
import opensamguk.gateway.d101.domain.D101ObservationUnavailable
import opensamguk.gateway.d101.domain.D101TerminalCandidate
import opensamguk.gateway.d101.domain.D101RootExecutionResult
import opensamguk.gateway.d101.security.D101TerminalAuthority
import opensamguk.gateway.d101.security.D101VerifiedTerminalEvidence

/** Projects a signed, bound Root success into the canonical terminal transaction. */
internal class D101VerifiedTerminalAuthorityAdapter(
    private val resultClient: D101RootExecutionResultClient,
) : D101TerminalAuthority {
    override fun readVerified(execution: D101Execution, candidate: D101TerminalCandidate): D101VerifiedTerminalEvidence {
        val result = resultClient.readVerified(execution, candidate.rootResultReceiptSha256)
        if (result.status != D101RootExecutionResult.Status.SUCCEEDED ||
            result.rawSha256 != candidate.rootResultReceiptSha256) throw D101ObservationUnavailable()
        return D101VerifiedTerminalEvidence(execution, result.rawSha256, result.originalBytes()).also {
            it.requireMatches(execution, candidate)
        }
    }
}

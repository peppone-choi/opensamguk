package opensamguk.gateway.d101.security

import opensamguk.gateway.d101.domain.*

/** The adapter must consume the independently verified C3 Root result outside
 * the DB lock, including original hash/signature and a SUCCEEDED physical result.
 * A caller receipt reference or ordinary ADMIN authority cannot construct proof. */
internal fun interface D101TerminalAuthority {
    fun readVerified(execution: D101Execution, candidate: D101TerminalCandidate): D101VerifiedTerminalEvidence
}

internal class UnavailableD101TerminalAuthority : D101TerminalAuthority {
    override fun readVerified(execution: D101Execution, candidate: D101TerminalCandidate): D101VerifiedTerminalEvidence =
        throw D101ObservationUnavailable()
}

/** Transaction projection of verified consumer output, not another Root DTO or
 * transport decoder. Its provider is responsible for cryptographic provenance. */
internal class D101VerifiedTerminalEvidence(
    execution: D101Execution,
    val receiptSha256: String,
    originalResult: ByteArray,
) {
    private val intent = execution.intentBytes()
    private val prepare = execution.preparePayload()
    private val operation = execution.intent.operationId
    private val target = execution.intent.targetFingerprint
    private val verifying = execution.verifyingRevision
    private val dispatch = execution.dispatch ?: throw D101OperationConflict()
    private val result = originalResult.copyOf()
    fun originalBytes(): ByteArray = result.copyOf()

    init {
        require(D101StrictJson.SHA.matches(receiptSha256) && result.isNotEmpty() && result.size <= 16 * 1024)
        require(D101StrictJson.hash(result) == receiptSha256)
    }

    fun requireMatches(execution: D101Execution, candidate: D101TerminalCandidate) {
        if (operation != execution.intent.operationId || target != execution.intent.targetFingerprint ||
            verifying != execution.verifyingRevision || dispatch != execution.dispatch ||
            !intent.contentEquals(execution.intentBytes()) || !prepare.contentEquals(execution.preparePayload()) ||
            candidate.verifyingRevision != verifying || candidate.rootResultReceiptSha256 != receiptSha256) {
            throw D101OperationConflict()
        }
    }
}

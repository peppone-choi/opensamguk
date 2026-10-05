package opensamguk.gateway.d101.application

import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.infra.JdbcD101ExecutionStore
import opensamguk.gateway.d101.security.*

internal class D101ExecutionService(
    private val json: D101StrictJson,
    private val codec: D101RequestCodec,
    private val verifier: D101PurposeGrantVerifier,
    private val store: JdbcD101ExecutionStore,
    private val dispatchAuthority: D101DispatchAuthority = UnavailableD101DispatchAuthority(),
    private val terminalAuthority: D101TerminalAuthority = UnavailableD101TerminalAuthority(),
) {
    fun prepare(operationId: String, body: ByteArray, headers: List<String>, authorizationCount: Int): D101ExecutionWrite {
        val candidate = codec.prepare(body)
        if (candidate.intent.operationId != operationId) throw D101RequestInvalid()
        val grant = verifier.verify(headers, D101PurposeRequest(
            D101PurposeAction.PREPARE, operationId, candidate.intent.targetFingerprint, candidate.intent.sha256,
            candidate.gatewayPayloadSha256, candidate.intent.initialPublicRevision, "POST",
            D101PurposeAction.PREPARE.path(operationId), body, authorizationCount,
        ))
        return store.prepare(body, candidate, grant)
    }

    fun query(operationId: String, headers: List<String>, authorizationCount: Int): D101Execution {
        val grant = purpose(D101PurposeAction.QUERY, operationId, byteArrayOf(), headers, authorizationCount)
        return existing(grant)
    }

    fun dispatch(operationId: String, body: ByteArray, headers: List<String>, authorizationCount: Int): D101ExecutionWrite {
        val candidate = codec.dispatchIntent(body)
        val grant = purpose(D101PurposeAction.DISPATCH_INTENT, operationId, body, headers, authorizationCount)
        val execution = existing(grant)
        if (execution.state in setOf(D101ExecutionState.RECOVERY_REQUIRED, D101ExecutionState.RECOVERED)) throw D101OperationConflict()
        // An exact stored dispatch replay performs no physical action and never renews phase evidence.
        if (execution.dispatch != null) return store.dispatch(candidate, grant, null)
        // Actual external sources are fetched outside the database transaction.
        val source = try {
            dispatchAuthority.readVerified(execution, candidate)
        } catch (_: Exception) {
            throw D101ObservationUnavailable()
        }
        source.requireMatches(execution, candidate)
        return store.dispatch(candidate, grant, source)
    }

    fun terminal(operationId: String, body: ByteArray, headers: List<String>, authorizationCount: Int): D101ExecutionWrite {
        val candidate = codec.terminal(body)
        val grant = purpose(D101PurposeAction.SETTLE_REGISTRY, operationId, body, headers, authorizationCount)
        val execution = existing(grant)
        if (candidate.verifyingRevision != execution.verifyingRevision) throw D101OperationConflict()
        val source = try {
            terminalAuthority.readVerified(execution, candidate)
        } catch (_: Exception) {
            throw D101ObservationUnavailable()
        }
        source.requireMatches(execution, candidate)
        store.remoteSucceeded(candidate, grant, source)
        return store.settleRegistry(candidate, grant)
    }

    private fun existing(grant: D101VerifiedPurposeGrant): D101Execution {
        val execution = store.query(grant.operationId) ?: throw D101OperationNotFound()
        if (grant.targetFingerprint != execution.intent.targetFingerprint || grant.approvalIntentSha256 != execution.intent.sha256 ||
            grant.gatewayPayloadSha256 != execution.gatewayPayloadSha256 ||
            grant.initialPublicRevision != execution.intent.initialPublicRevision) throw D101OperationConflict()
        return execution
    }

    private fun purpose(
        action: D101PurposeAction, operationId: String, body: ByteArray, headers: List<String>, authorizationCount: Int,
    ): D101VerifiedPurposeGrant {
        if (headers.size != 1 || headers.single().length > 8192) throw D101PurposeGrantInvalid()
        val parts = headers.single().split('.')
        if (parts.size != 2) throw D101PurposeGrantInvalid()
        val claims = try {
            json.objectBytes(json.base64url(parts[0], 4096), D101PurposeGrantVerifier.CLAIM_KEYS, 4096)
        } catch (_: D101RequestInvalid) {
            throw D101PurposeGrantInvalid()
        }
        // Untrusted claims select a candidate authority, never a caller key or a successful grant.
        // verify() checks every claim, signature, approved intent and original body before DB lookup.
        val request = try {
            D101PurposeRequest(
                action, operationId, json.sha(claims["targetFingerprint"]), json.sha(claims["approvalIntentSha256"]),
                json.sha(claims["gatewayPayloadSha256"]), json.revision(claims["initialPublicRevision"]),
                action.method, action.path(operationId), body, authorizationCount,
            )
        } catch (_: D101RequestInvalid) {
            throw D101PurposeGrantInvalid()
        }
        return verifier.verify(headers, request)
    }
}

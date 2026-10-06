package opensamguk.gateway.d101.application

import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.infra.*
import opensamguk.gateway.d101.security.*

/** External evidence is fetched before JDBC locking; the store rechecks it under CAS. */
internal class D101RecoveryService(
    private val json: D101StrictJson,
    private val codec: D101RecoveryRequestCodec,
    private val verifier: D101PurposeGrantVerifier,
    private val executions: JdbcD101ExecutionStore,
    private val recovery: JdbcD101RecoveryStore,
    private val authority: D101RecoveryAuthority = UnavailableD101RecoveryAuthority(),
) {
    fun begin(operationId: String, body: ByteArray, headers: List<String>, authorizationCount: Int): D101RecoveryWrite {
        val original = body.copyOf()
        val candidate = codec.begin(original)
        val grant = purpose(D101PurposeAction.RECOVERY_BEGIN, operationId, original, headers, authorizationCount)
        val execution = existing(grant)
        if (candidate.verifyingRevision != execution.verifyingRevision) throw D101OperationConflict()
        if (execution.state in setOf(D101ExecutionState.RECOVERY_REQUIRED, D101ExecutionState.RECOVERED)) {
            return recovery.begin(original, candidate, grant, null)
        }
        if (execution.state !in setOf(D101ExecutionState.DISPATCH_INTENT, D101ExecutionState.REMOTE_SUCCEEDED,
                D101ExecutionState.REGISTRY_SETTLED)) throw D101OperationConflict()
        val source = try {
            authority.readBegin(execution, candidate.rootResultReceiptSha256)
        } catch (conflict: D101OperationConflict) {
            throw conflict
        } catch (_: Exception) {
            throw D101ObservationUnavailable()
        }
        source.requireMatches(execution, candidate.rootResultReceiptSha256)
        return recovery.begin(original, candidate, grant, source)
    }

    fun close(operationId: String, body: ByteArray, headers: List<String>, authorizationCount: Int): D101RecoveryCloseWrite {
        val original = body.copyOf()
        val candidate = codec.close(original)
        val grant = purpose(D101PurposeAction.RECOVERY_CLOSE, operationId, original, headers, authorizationCount)
        val execution = existing(grant)
        if (candidate.verifyingRevision != execution.verifyingRevision) throw D101OperationConflict()
        if (execution.state == D101ExecutionState.RECOVERED) {
            return recovery.close(original, candidate, grant, null)
        }
        if (execution.state != D101ExecutionState.RECOVERY_REQUIRED) throw D101OperationConflict()
        val source = try {
            authority.readClose(execution, candidate.recoveryBeginReceiptSha256, candidate.recoveryResultReceiptSha256)
        } catch (conflict: D101OperationConflict) {
            throw conflict
        } catch (_: Exception) {
            throw D101ObservationUnavailable()
        }
        source.requireMatches(execution, candidate.recoveryBeginReceiptSha256, candidate.recoveryResultReceiptSha256)
        return recovery.close(original, candidate, grant, source)
    }

    private fun existing(grant: D101VerifiedPurposeGrant): D101Execution {
        val execution = executions.query(grant.operationId) ?: throw D101OperationNotFound()
        if (grant.targetFingerprint != execution.intent.targetFingerprint ||
            grant.approvalIntentSha256 != execution.intent.sha256 ||
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

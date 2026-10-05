package opensamguk.gateway.d101.infra

import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.D101VerifiedPurposeGrant
import opensamguk.gateway.d101.security.D101VerifiedRecoveryBegin
import opensamguk.gateway.publication.domain.*
import opensamguk.gateway.service.ServerDef
import opensamguk.gateway.service.ServerRegistry
import opensamguk.gateway.service.ServerRegistryTransitionConflict
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** Recovery shares the original D101_RESET identity, but has its own immutable phase receipt. */
internal class JdbcD101RecoveryStore(
    jdbc: JdbcTemplate,
    private val publication: ServerPublicationRepository,
    private val registry: ServerRegistry,
    private val executions: JdbcD101ExecutionStore,
) {
    private val jdbc = JdbcTemplate(requireNotNull(jdbc.dataSource)).apply { queryTimeout = 1 }
    private val transactions = TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.dataSource))).apply { timeout = 2 }

    fun begin(
        body: ByteArray, candidate: D101RecoveryBeginCandidate, grant: D101VerifiedPurposeGrant,
        source: D101VerifiedRecoveryBegin?,
    ): D101RecoveryWrite = transaction {
        val original = body.copyOf()
        val requestSha = D101StrictJson.hash(original)
        val canonical = lockParent()
        val current = lockPublication()
        lockExecution(grant.operationId)
        val execution = executions.query(grant.operationId) ?: throw D101OperationNotFound()
        matchGrant(execution, grant, D101PurposeAction.RECOVERY_BEGIN)
        grant.requireRecoveryWindow()
        requireVerifying(execution, current)
        if (candidate.verifyingRevision != execution.verifyingRevision) conflict()
        if (execution.state == D101ExecutionState.RECOVERY_REQUIRED) {
            val prior = beginReceipt(execution.intent.operationId) ?: unavailable()
            if (prior.requestSha != requestSha || !prior.requestBytes.contentEquals(original) ||
                prior.rootResultSha != candidate.rootResultReceiptSha256 ||
                D101StrictJson.hash(prior.receiptBytes) != prior.receiptSha) conflict()
            requireRegistry(execution, canonical)
            grant.requireRecoveryWindow()
            return@transaction D101RecoveryWrite(execution, prior.receiptSha, false)
        }
        if (execution.state !in setOf(D101ExecutionState.DISPATCH_INTENT, D101ExecutionState.REMOTE_SUCCEEDED,
                D101ExecutionState.REGISTRY_SETTLED)) conflict()
        requireRegistry(execution, canonical)
        val verified = source ?: unavailable()
        verified.requireMatches(execution, candidate.rootResultReceiptSha256)
        val failureCode = when (verified.rootStatus) {
            "SUCCEEDED", "RECOVERY_REQUIRED" -> "RECOVERY_REQUIRED"
            "FAILED" -> "ROOT_FAILED"
            "CANCELLED" -> "ROOT_CANCELLED"
            else -> unavailable()
        }
        // The execution row can contain a Root result only after its separate
        // terminal transaction; in both cases the verified original is retained.
        if (execution.rootResultReceiptSha256 != null) {
            if (execution.rootResultReceiptSha256 != verified.rootResultReceiptSha256 ||
                verified.rootStatus != "SUCCEEDED" || !storedRootMatches(execution.intent.operationId, verified.originalBytes())) conflict()
        }
        if (execution.state != D101ExecutionState.DISPATCH_INTENT && verified.rootStatus != "SUCCEEDED") conflict()
        val receiptBytes = beginReceiptBytes(execution, requestSha, candidate.rootResultReceiptSha256, failureCode)
        val receiptSha = D101StrictJson.hash(receiptBytes)
        verified.requireMatches(execution, candidate.rootResultReceiptSha256)
        grant.requireRecoveryWindow()
        if (jdbc.update(
                """UPDATE game_server_d101_execution SET state='RECOVERY_REQUIRED', failure_code=?, updated_at=CURRENT_TIMESTAMP
                    WHERE operation_id=? AND state=? AND last_safe_state=? AND verifying_revision=?""".trimIndent(),
                failureCode, execution.intent.operationId, execution.state.name,
                execution.lastSafeState.name, execution.verifyingRevision,
            ) != 1) conflict()
        if (jdbc.update(
                """INSERT INTO game_server_d101_recovery
                    (operation_id, begin_request_sha, begin_request_bytes, begin_receipt_sha, begin_receipt_bytes,
                     root_result_sha, root_result_bytes)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                execution.intent.operationId, requestSha, original, receiptSha, receiptBytes,
                verified.rootResultReceiptSha256, verified.originalBytes(),
            ) != 1) conflict()
        grant.requireRecoveryWindow()
        D101RecoveryWrite(requireNotNull(executions.query(execution.intent.operationId)), receiptSha, true)
    }

    private fun requireRegistry(execution: D101Execution, canonical: ServerDef) {
        if (execution.lastSafeState == D101ExecutionState.REGISTRY_SETTLED) {
            registry.requireD101Settled(canonical)
        } else {
            registry.requireD101Pending(reset(canonical), execution.intent.operationId,
                execution.gatewayPayloadSha256, dispatched = true)
        }
    }

    private fun reset(canonical: ServerDef) = canonical.copy(name = "빼섭", generation = 0, scenarioCode = "scenario_3190")

    private fun requireVerifying(execution: D101Execution, current: ServerPublication) {
        if (current.state != ServerPublicationState.VERIFYING || current.revision != execution.verifyingRevision ||
            current.target != ServerPublicationTarget(execution.intent.operationId, 0, "scenario_3190", execution.intent.targetFingerprint)) conflict()
    }

    private fun matchGrant(execution: D101Execution, grant: D101VerifiedPurposeGrant, action: D101PurposeAction) {
        if (grant.action != action || grant.operationId != execution.intent.operationId ||
            grant.targetFingerprint != execution.intent.targetFingerprint ||
            grant.approvalIntentSha256 != execution.intent.sha256 ||
            grant.gatewayPayloadSha256 != execution.gatewayPayloadSha256 ||
            grant.initialPublicRevision != execution.intent.initialPublicRevision) conflict()
    }

    private fun beginReceipt(operationId: String): BeginReceipt? = jdbc.query(
        """SELECT begin_request_sha, begin_request_bytes, begin_receipt_sha, begin_receipt_bytes, root_result_sha
            FROM game_server_d101_recovery WHERE operation_id=?""".trimIndent(),
        { rs, _ -> BeginReceipt(rs.getString(1), rs.getBytes(2), rs.getString(3), rs.getBytes(4), rs.getString(5)) }, operationId,
    ).singleOrNull()

    private fun beginReceiptBytes(execution: D101Execution, requestSha: String, rootSha: String, failureCode: String): ByteArray =
        """{"schemaVersion":1,"kind":"D101_RECOVERY_BEGIN_V1","operationId":"${execution.intent.operationId}","verifyingRevision":"${execution.verifyingRevision}","lastSafeState":"${execution.lastSafeState.name}","rootResultReceiptSha256":"$rootSha","failureCode":"$failureCode","requestBodySha256":"$requestSha"}"""
            .toByteArray(Charsets.UTF_8)

    private fun storedRootMatches(operationId: String, original: ByteArray): Boolean = jdbc.query(
        "SELECT root_result_bytes FROM game_server_d101_execution WHERE operation_id=?",
        { rs, _ -> rs.getBytes(1) }, operationId,
    ).singleOrNull()?.contentEquals(original) == true

    private fun lockParent(): ServerDef = jdbc.query(
        """SELECT server_id, display_name, game_api_url, game_engine_url, deploy_project, generation, scenario_code
            FROM game_server WHERE server_id='pep' FOR UPDATE""".trimIndent(),
        { rs, _ -> ServerDef(
            rs.getString("server_id"), rs.getString("display_name"), rs.getString("game_api_url"),
            rs.getString("game_engine_url"), rs.getString("deploy_project"),
            rs.getObject("generation", Integer::class.java)?.toInt(), rs.getString("scenario_code"),
        ) },
    ).singleOrNull() ?: unavailable()

    private fun lockPublication(): ServerPublication {
        if (jdbc.query("SELECT server_id FROM game_server_publication WHERE server_id='pep' FOR UPDATE",
                { rs, _ -> rs.getString(1) }).size != 1) unavailable()
        return publication.find("pep") ?: unavailable()
    }

    private fun lockExecution(operationId: String) {
        if (jdbc.query("SELECT operation_id FROM game_server_d101_execution WHERE operation_id=? FOR UPDATE",
                { rs, _ -> rs.getString(1) }, operationId).isEmpty()) throw D101OperationNotFound()
    }

    private fun <T : Any> transaction(body: () -> T): T = try {
        requireNotNull(transactions.execute { body() })
    } catch (_: DuplicateKeyException) {
        conflict()
    } catch (_: ServerRegistryTransitionConflict) {
        conflict()
    } catch (_: DataAccessException) {
        unavailable()
    } catch (_: ServerPublicationSourceUnavailable) {
        unavailable()
    } catch (_: D101RequestInvalid) {
        unavailable()
    } catch (_: IllegalArgumentException) {
        unavailable()
    }

    private fun conflict(): Nothing = throw D101OperationConflict()
    private fun unavailable(): Nothing = throw D101ObservationUnavailable()
    private data class BeginReceipt(
        val requestSha: String, val requestBytes: ByteArray, val receiptSha: String,
        val receiptBytes: ByteArray, val rootResultSha: String,
    )
}

internal data class D101RecoveryWrite(val execution: D101Execution, val beginReceiptSha256: String, val created: Boolean)

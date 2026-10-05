package opensamguk.gateway.d101.infra

import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.D101VerifiedPurposeGrant
import opensamguk.gateway.d101.security.D101VerifiedRecoveryBegin
import opensamguk.gateway.d101.security.D101VerifiedRecoveryClose
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
    private val codec: D101RecoveryRequestCodec,
    private val json: D101StrictJson,
) : D101RecoveryBeginReader {
    private val jdbc = JdbcTemplate(requireNotNull(jdbc.dataSource)).apply { queryTimeout = 1 }
    private val transactions = TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.dataSource))).apply { timeout = 2 }

    fun begin(
        body: ByteArray, candidate: D101RecoveryBeginCandidate, grant: D101VerifiedPurposeGrant,
        source: D101VerifiedRecoveryBegin?,
    ): D101RecoveryWrite = transaction {
        val original = body.copyOf()
        val requestSha = D101StrictJson.hash(original)
        if (codec.begin(original) != candidate) conflict()
        val canonical = lockParent()
        val current = lockPublication()
        lockExecution(grant.operationId)
        val execution = executions.query(grant.operationId) ?: throw D101OperationNotFound()
        matchGrant(execution, grant, D101PurposeAction.RECOVERY_BEGIN)
        grant.requireRecoveryWindow()
        requireVerifying(execution, current)
        if (candidate.verifyingRevision != execution.verifyingRevision) conflict()
        if (execution.state in setOf(D101ExecutionState.RECOVERY_REQUIRED, D101ExecutionState.RECOVERED)) {
            val prior = beginReceipt(execution.intent.operationId) ?: unavailable()
            if (prior.requestSha != requestSha || !prior.requestBytes.contentEquals(original) ||
                prior.rootResultSha != candidate.rootResultReceiptSha256 ||
                D101StrictJson.hash(prior.receiptBytes) != prior.receiptSha) conflict()
            if (execution.state == D101ExecutionState.RECOVERED) {
                val closed = closeReceipt(execution.intent.operationId) ?: unavailable()
                registry.requireD101Recovered(canonical, closed.oldCanonical)
            } else requireRegistry(execution, canonical)
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

    /** Existing QUERY purpose must be checked by the caller first. This method
     * accepts that execution only as a binding and returns a fresh locked view. */
    override fun readForQuery(execution: D101Execution): D101RecoveryBeginRead = transaction {
        if (execution.state != D101ExecutionState.RECOVERY_REQUIRED) conflict()
        val canonical = lockParent()
        val publication = lockPublication()
        lockExecution(execution.intent.operationId)
        val stored = executions.query(execution.intent.operationId) ?: throw D101OperationNotFound()
        if (stored.state != D101ExecutionState.RECOVERY_REQUIRED ||
            stored.lastSafeState != execution.lastSafeState ||
            stored.verifyingRevision != execution.verifyingRevision ||
            stored.intent.sha256 != execution.intent.sha256 ||
            stored.gatewayPayloadSha256 != execution.gatewayPayloadSha256 ||
            stored.dispatch != execution.dispatch ||
            stored.rootResultReceiptSha256 != execution.rootResultReceiptSha256 ||
            !stored.intentBytes().contentEquals(execution.intentBytes()) ||
            !stored.preparePayload().contentEquals(execution.preparePayload())) conflict()
        requireVerifying(stored, publication)
        requireRegistry(stored, canonical)
        val receipt = beginReceipt(stored.intent.operationId) ?: unavailable()
        if (D101StrictJson.hash(receipt.requestBytes) != receipt.requestSha ||
            D101StrictJson.hash(receipt.receiptBytes) != receipt.receiptSha ||
            D101StrictJson.hash(receipt.rootResultBytes) != receipt.rootResultSha) unavailable()
        val raw = json.objectBytes(receipt.receiptBytes, BEGIN_RECEIPT_KEYS, 16 * 1024)
        val failure = jdbc.query("SELECT failure_code FROM game_server_d101_execution WHERE operation_id=?",
            { rs, _ -> rs.getString(1) }, stored.intent.operationId).singleOrNull() ?: unavailable()
        if (json.positiveLong(raw["schemaVersion"]) != 1L ||
            json.text(raw["kind"]) != "D101_RECOVERY_BEGIN_V1" ||
            json.text(raw["operationId"]) != stored.intent.operationId ||
            json.revision(raw["verifyingRevision"]) != stored.verifyingRevision ||
            json.text(raw["lastSafeState"]) != stored.lastSafeState.name ||
            json.sha(raw["rootResultReceiptSha256"]) != receipt.rootResultSha ||
            json.text(raw["failureCode"]) != failure ||
            json.sha(raw["requestBodySha256"]) != receipt.requestSha) unavailable()
        if (stored.rootResultReceiptSha256 != null) {
            if (stored.rootResultReceiptSha256 != receipt.rootResultSha ||
                !storedRootMatches(stored.intent.operationId, receipt.rootResultBytes)) conflict()
        } else if (stored.lastSafeState != D101ExecutionState.DISPATCH_INTENT) conflict()
        D101RecoveryBeginRead(stored.intent.operationId, stored.verifyingRevision,
            receipt.receiptSha, receipt.receiptBytes)
    }

    /** Closes the same operation after C8's signed old canonical and physical
     * world snapshots. Publication remains VERIFYING; this does not publish. */
    fun close(
        body: ByteArray, candidate: D101RecoveryCloseCandidate, grant: D101VerifiedPurposeGrant,
        source: D101VerifiedRecoveryClose?,
    ): D101RecoveryCloseWrite = transaction {
        val original = body.copyOf()
        val requestSha = D101StrictJson.hash(original)
        if (codec.close(original) != candidate) conflict()
        val canonical = lockParent()
        val current = lockPublication()
        lockExecution(grant.operationId)
        val execution = executions.query(grant.operationId) ?: throw D101OperationNotFound()
        matchGrant(execution, grant, D101PurposeAction.RECOVERY_CLOSE)
        grant.requireRecoveryWindow()
        requireVerifying(execution, current)
        if (candidate.verifyingRevision != execution.verifyingRevision) conflict()
        val begin = beginReceipt(execution.intent.operationId) ?: unavailable()
        if (begin.receiptSha != candidate.recoveryBeginReceiptSha256 ||
            D101StrictJson.hash(begin.receiptBytes) != begin.receiptSha ||
            D101StrictJson.hash(begin.rootResultBytes) != begin.rootResultSha) conflict()
        if (execution.state == D101ExecutionState.RECOVERED) {
            val prior = closeReceipt(execution.intent.operationId) ?: unavailable()
            if (prior.requestSha != requestSha || !prior.requestBytes.contentEquals(original) ||
                prior.resultSha != candidate.recoveryResultReceiptSha256 ||
                D101StrictJson.hash(prior.resultBytes) != prior.resultSha ||
                D101StrictJson.hash(prior.registryBytes) != prior.registrySha ||
                D101StrictJson.hash(prior.worldBytes) != prior.worldSha) conflict()
            registry.requireD101Recovered(canonical, prior.oldCanonical)
            grant.requireRecoveryWindow()
            return@transaction D101RecoveryCloseWrite(execution, begin.receiptSha, prior.resultSha, false)
        }
        if (execution.state != D101ExecutionState.RECOVERY_REQUIRED) conflict()
        requireRegistry(execution, canonical)
        val verified = source ?: unavailable()
        verified.requireMatches(execution, candidate.recoveryBeginReceiptSha256, candidate.recoveryResultReceiptSha256)
        if (verified.originalRootResultSha256 != begin.rootResultSha ||
            verified.recoveryResultReceiptSha256 != candidate.recoveryResultReceiptSha256) conflict()
        if (execution.rootResultReceiptSha256 != null) {
            if (execution.rootResultReceiptSha256 != verified.originalRootResultSha256 ||
                !storedRootMatches(execution.intent.operationId, begin.rootResultBytes)) conflict()
        } else if (execution.lastSafeState != D101ExecutionState.DISPATCH_INTENT) conflict()
        val old = verified.oldCanonicalRegistry
        val world = verified.restoredWorld
        val oldRegistryBytes = verified.oldRegistryOriginalBytes()
        val oldWorldBytes = verified.oldWorldOriginalBytes()
        if (old.id != "pep" || old.generation != verified.oldGeneration || old.scenarioCode != verified.oldScenarioCode ||
            D101StrictJson.hash(oldRegistryBytes) != verified.oldRegistryReceiptSha256 ||
            D101StrictJson.hash(oldWorldBytes) != verified.oldWorldReceiptSha256 ||
            world.worldId != 1 || world.generation != old.generation || world.scenarioCode != old.scenarioCode ||
            world.originalReceiptSha256 != verified.oldWorldReceiptSha256) conflict()
        verified.requireMatches(execution, candidate.recoveryBeginReceiptSha256, candidate.recoveryResultReceiptSha256)
        grant.requireRecoveryWindow()
        if (jdbc.update(
                """UPDATE game_server_d101_recovery SET close_request_sha=?, close_request_bytes=?,
                    close_result_sha=?, close_result_bytes=?, old_registry_sha=?, old_registry_bytes=?,
                    old_world_sha=?, old_world_bytes=?, old_publication_sha=?, backup_manifest_sha=?,
                    old_name=?, old_game_api_url=?, old_game_engine_url=?, old_deploy_project=?,
                    old_generation=?, old_scenario_code=?
                    WHERE operation_id=? AND close_request_sha IS NULL""".trimIndent(),
                requestSha, original, verified.recoveryResultReceiptSha256, verified.originalBytes(),
                verified.oldRegistryReceiptSha256, oldRegistryBytes, verified.oldWorldReceiptSha256, oldWorldBytes,
                verified.oldPublicationReceiptSha256, verified.backupManifestSha256,
                old.name, old.gameApiUrl, old.gameEngineUrl, old.deployProject, old.generation, old.scenarioCode,
                execution.intent.operationId,
            ) != 1) conflict()
        registry.recoverD101Reset(canonical, old, execution.intent.operationId, execution.gatewayPayloadSha256,
            settled = execution.lastSafeState == D101ExecutionState.REGISTRY_SETTLED)
        if (jdbc.update(
                """UPDATE game_server_d101_execution SET state='RECOVERED', updated_at=CURRENT_TIMESTAMP
                    WHERE operation_id=? AND state='RECOVERY_REQUIRED' AND last_safe_state=? AND failure_code IS NOT NULL""".trimIndent(),
                execution.intent.operationId, execution.lastSafeState.name,
            ) != 1) conflict()
        grant.requireRecoveryWindow()
        D101RecoveryCloseWrite(requireNotNull(executions.query(execution.intent.operationId)),
            begin.receiptSha, verified.recoveryResultReceiptSha256, true)
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
        """SELECT begin_request_sha, begin_request_bytes, begin_receipt_sha, begin_receipt_bytes,
                  root_result_sha, root_result_bytes
            FROM game_server_d101_recovery WHERE operation_id=?""".trimIndent(),
        { rs, _ -> BeginReceipt(rs.getString(1), rs.getBytes(2), rs.getString(3), rs.getBytes(4), rs.getString(5), rs.getBytes(6)) }, operationId,
    ).singleOrNull()

    private fun closeReceipt(operationId: String): CloseReceipt? = jdbc.query(
        """SELECT close_request_sha, close_request_bytes, close_result_sha, close_result_bytes,
                  old_registry_sha, old_registry_bytes, old_world_sha, old_world_bytes,
                  old_name, old_game_api_url, old_game_engine_url, old_deploy_project,
                  old_generation, old_scenario_code
            FROM game_server_d101_recovery WHERE operation_id=? AND close_request_sha IS NOT NULL""".trimIndent(),
        { rs, _ -> CloseReceipt(
            rs.getString(1), rs.getBytes(2), rs.getString(3), rs.getBytes(4),
            rs.getString(5), rs.getBytes(6), rs.getString(7), rs.getBytes(8),
            ServerDef("pep", rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                rs.getObject(13, Integer::class.java)?.toInt(), rs.getString(14)),
        ) }, operationId,
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
        val receiptBytes: ByteArray, val rootResultSha: String, val rootResultBytes: ByteArray,
    )
    private data class CloseReceipt(
        val requestSha: String, val requestBytes: ByteArray, val resultSha: String, val resultBytes: ByteArray,
        val registrySha: String, val registryBytes: ByteArray, val worldSha: String, val worldBytes: ByteArray,
        val oldCanonical: ServerDef,
    )
    private companion object {
        val BEGIN_RECEIPT_KEYS = setOf(
            "schemaVersion", "kind", "operationId", "verifyingRevision", "lastSafeState",
            "rootResultReceiptSha256", "failureCode", "requestBodySha256",
        )
    }
}

internal data class D101RecoveryWrite(val execution: D101Execution, val beginReceiptSha256: String, val created: Boolean)
internal data class D101RecoveryCloseWrite(
    val execution: D101Execution, val beginReceiptSha256: String,
    val recoveryResultReceiptSha256: String, val created: Boolean,
)

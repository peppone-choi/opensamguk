package opensamguk.gateway.d101.infra

import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.D101VerifiedDispatch
import opensamguk.gateway.d101.security.D101VerifiedPurposeGrant
import opensamguk.gateway.publication.application.ServerPublicationRegistrationMissing
import opensamguk.gateway.publication.domain.*
import opensamguk.gateway.service.ServerDef
import opensamguk.gateway.service.ServerRegistry
import opensamguk.gateway.service.ServerRegistryTransitionConflict
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** No Docker, HTTP or cryptographic provider call occurs inside these transactions. */
internal class JdbcD101ExecutionStore(
    jdbc: JdbcTemplate,
    private val publication: ServerPublicationRepository,
    private val publisher: ServerPublicationWriter,
    private val registry: ServerRegistry,
    private val codec: D101RequestCodec,
) {
    private val jdbc = JdbcTemplate(requireNotNull(jdbc.dataSource)).apply { queryTimeout = 1 }
    private val transactions = TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.dataSource))).apply { timeout = 2 }
    private val reservations = D101OperationReservations(this.jdbc)

    fun query(operationId: String): D101Execution? = observed {
        require(D101StrictJson.OPERATION.matches(operationId))
        read(operationId)
    }

    fun prepare(wire: ByteArray, candidate: D101PrepareCandidate, grant: D101VerifiedPurposeGrant): D101ExecutionWrite = transaction {
        val original = wire.copyOf()
        val intent = candidate.intent
        match(grant, D101PurposeAction.PREPARE, intent, candidate.gatewayPayloadSha256)
        // Decode the original again under the lock, rather than trusting a mutable caller object.
        val decoded = codec.prepare(original)
        if (decoded.intent.sha256 != intent.sha256 || decoded.gatewayPayloadSha256 != candidate.gatewayPayloadSha256) conflict()
        val canonical = lockParent()
        val current = lockPublication()
        val existing = read(intent.operationId, true)
        grant.requireNewExecutionWindow()
        if (existing != null) {
            matchStored(existing, grant)
            if (existing.state in setOf(D101ExecutionState.RECOVERY_REQUIRED, D101ExecutionState.RECOVERED)) conflict()
            if (!existing.preparePayload().contentEquals(original) || !existing.intentBytes().contentEquals(candidate.intentBytes())) conflict()
            requireReplaySource(existing, current, canonical)
            return@transaction D101ExecutionWrite(existing, false)
        }
        if (reservations.find(intent.operationId) != null ||
            jdbc.queryForObject("SELECT COUNT(*) FROM game_server_publication_operation WHERE operation_id=?", Int::class.java, intent.operationId) != 0) conflict()
        if (current.state != ServerPublicationState.PUBLIC || current.revision != intent.initialPublicRevision ||
            intent.initialPublicRevision == Long.MAX_VALUE) conflict()
        if (jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_server_d101_execution WHERE server_id='pep' AND state NOT IN ('PUBLISHED','RECOVERED')",
                Int::class.java,
            ) != 0) conflict()
        val target = ServerPublicationTarget(intent.operationId, 0, "scenario_3190", intent.targetFingerprint)
        grant.requireNewExecutionWindow()
        // Nested publisher TransactionTemplate joins this same datasource transaction.
        val closed = publisher.verifyingD101(VerifyServerPublication("pep", intent.initialPublicRevision, target))
        if (closed.state != ServerPublicationState.VERIFYING || closed.target != target ||
            closed.revision != intent.initialPublicRevision + 1) conflict()
        grant.requireNewExecutionWindow()
        jdbc.update(
            """INSERT INTO game_server_d101_execution
                (operation_id, server_id, world_id, state, last_safe_state, intent_sha, intent_bytes,
                 gateway_payload_sha, prepare_payload, target_fingerprint, initial_public_revision, verifying_revision)
                VALUES (?, 'pep', 1, 'PREPARED', 'PREPARED', ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
            intent.operationId, intent.sha256, candidate.intentBytes(), candidate.gatewayPayloadSha256, original,
            intent.targetFingerprint, intent.initialPublicRevision, closed.revision,
        )
        registry.prepareD101Reset(reset(canonical), intent.operationId, candidate.gatewayPayloadSha256)
        grant.requireNewExecutionWindow()
        D101ExecutionWrite(requireNotNull(read(intent.operationId)), true)
    }

    fun dispatch(
        candidate: D101DispatchIntentCandidate,
        grant: D101VerifiedPurposeGrant,
        source: D101VerifiedDispatch?,
    ): D101ExecutionWrite = transaction {
        val canonical = lockParent()
        val current = lockPublication()
        val existing = read(grant.operationId, true) ?: throw D101OperationNotFound()
        matchStored(existing, grant)
        if (grant.action != D101PurposeAction.DISPATCH_INTENT) conflict()
        grant.requireNewExecutionWindow()
        if (existing.state in setOf(D101ExecutionState.RECOVERY_REQUIRED, D101ExecutionState.RECOVERED)) conflict()
        if (existing.dispatch != null) {
            if (existing.dispatch != candidate) conflict()
            requireReplaySource(existing, current, canonical)
            return@transaction D101ExecutionWrite(existing, false)
        }
        val verifiedSource = source ?: throw D101ObservationUnavailable()
        verifiedSource.requireMatches(existing, candidate)
        if (existing.state != D101ExecutionState.PREPARED || current.state != ServerPublicationState.VERIFYING ||
            current.revision != existing.verifyingRevision ||
            current.target != ServerPublicationTarget(existing.intent.operationId, 0, "scenario_3190", existing.intent.targetFingerprint)) conflict()
        grant.requireNewExecutionWindow()
        registry.dispatchD101Reset(reset(canonical), existing.intent.operationId, existing.gatewayPayloadSha256)
        grant.requireNewExecutionWindow()
        verifiedSource.requireMatches(existing, candidate)
        if (jdbc.update(
                """UPDATE game_server_d101_execution
                    SET state='DISPATCH_INTENT', last_safe_state='DISPATCH_INTENT',
                        approval_plan_sha=?, execution_receipt_sha=?, root_request_fingerprint=?, updated_at=CURRENT_TIMESTAMP
                    WHERE operation_id=? AND state='PREPARED'""".trimIndent(),
                candidate.approvalPlanSha256, candidate.executionReceiptSha256, candidate.rootRequestFingerprint,
                existing.intent.operationId,
            ) != 1) conflict()
        grant.requireNewExecutionWindow()
        verifiedSource.requireMatches(existing, candidate)
        D101ExecutionWrite(requireNotNull(read(existing.intent.operationId)), true)
    }

    private fun matchStored(execution: D101Execution, grant: D101VerifiedPurposeGrant) {
        match(grant, grant.action, execution.intent, execution.gatewayPayloadSha256)
        val binding = reservations.find(execution.intent.operationId) ?: unavailable()
        if (binding != D101OperationReservations.Binding(
                D101OperationKind.D101_RESET, "pep", execution.intent.targetFingerprint, execution.intent.initialPublicRevision,
            )) conflict()
    }

    private fun requireReplaySource(execution: D101Execution, current: ServerPublication, canonical: ServerDef) {
        if (execution.state == D101ExecutionState.PUBLISHED) return
        if (current.state != ServerPublicationState.VERIFYING || current.revision != execution.verifyingRevision ||
            current.target != ServerPublicationTarget(execution.intent.operationId, 0, "scenario_3190", execution.intent.targetFingerprint)) conflict()
        if (execution.state in setOf(D101ExecutionState.PREPARED, D101ExecutionState.DISPATCH_INTENT, D101ExecutionState.REMOTE_SUCCEEDED)) {
            registry.requireD101Pending(reset(canonical), execution.intent.operationId, execution.gatewayPayloadSha256,
                execution.state != D101ExecutionState.PREPARED)
        }
    }

    private fun match(grant: D101VerifiedPurposeGrant, action: D101PurposeAction, intent: D101ApprovalIntent, payloadSha: String) {
        if (grant.action != action || grant.operationId != intent.operationId ||
            grant.targetFingerprint != intent.targetFingerprint || grant.approvalIntentSha256 != intent.sha256 ||
            grant.gatewayPayloadSha256 != payloadSha || grant.initialPublicRevision != intent.initialPublicRevision) conflict()
    }

    private fun lockParent(): ServerDef {
        val rows = jdbc.query(
            """SELECT server_id, display_name, game_api_url, game_engine_url, deploy_project, generation, scenario_code
                FROM game_server WHERE server_id='pep' FOR UPDATE""".trimIndent(),
            { rs, _ -> ServerDef(
                rs.getString("server_id"), rs.getString("display_name"), rs.getString("game_api_url"),
                rs.getString("game_engine_url"), rs.getString("deploy_project"),
                rs.getObject("generation", Integer::class.java)?.toInt(), rs.getString("scenario_code"),
            ) },
        )
        if (rows.isEmpty()) throw D101OperationConflict()
        return rows.singleOrNull() ?: unavailable()
    }

    private fun lockPublication(): ServerPublication {
        if (jdbc.query("SELECT server_id FROM game_server_publication WHERE server_id='pep' FOR UPDATE", { rs, _ -> rs.getString(1) }).size != 1) unavailable()
        return publication.find("pep") ?: unavailable()
    }

    private fun reset(canonical: ServerDef) = canonical.copy(name = "빼섭", generation = 0, scenarioCode = "scenario_3190")

    private fun read(operationId: String, forUpdate: Boolean = false): D101Execution? {
        val rows = jdbc.query(
            "SELECT * FROM game_server_d101_execution WHERE operation_id=?" + if (forUpdate) " FOR UPDATE" else "",
            { rs, _ ->
                val prepare = rs.getBytes("prepare_payload") ?: unavailable()
                val candidate = codec.prepare(prepare)
                val intent = candidate.intent
                if (rs.getString("server_id") != "pep" || rs.getInt("world_id") != 1 ||
                    intent.operationId != operationId || intent.sha256 != rs.getString("intent_sha") ||
                    candidate.gatewayPayloadSha256 != rs.getString("gateway_payload_sha") ||
                    intent.targetFingerprint != rs.getString("target_fingerprint") ||
                    intent.initialPublicRevision != rs.getLong("initial_public_revision") ||
                    !candidate.intentBytes().contentEquals(rs.getBytes("intent_bytes"))) unavailable()
                val v = rs.getLong("verifying_revision")
                val refs = listOf(rs.getString("approval_plan_sha"), rs.getString("execution_receipt_sha"), rs.getString("root_request_fingerprint"))
                val dispatch = if (refs.all { it == null }) null else {
                    if (!refs.all { it != null && D101StrictJson.SHA.matches(it) }) unavailable()
                    D101DispatchIntentCandidate(v, refs[0]!!, refs[1]!!, refs[2]!!)
                }
                val result = rs.getString("root_result_sha")
                val resultBytes = rs.getBytes("root_result_bytes")
                if ((result == null) != (resultBytes == null) || resultBytes != null &&
                    (resultBytes.size > 16 * 1024 || D101StrictJson.hash(resultBytes) != result)) unavailable()
                D101Execution(
                    intent, candidate.intentBytes(), prepare, candidate.gatewayPayloadSha256,
                    D101ExecutionState.valueOf(rs.getString("state")), D101ExecutionState.valueOf(rs.getString("last_safe_state")),
                    v, dispatch, result, rs.getObject("published_revision", java.lang.Long::class.java)?.toLong(),
                    rs.getString("validation_receipt_sha"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                )
            }, operationId,
        )
        if (rows.size > 1) unavailable()
        return rows.singleOrNull()
    }

    private fun <T> observed(body: () -> T): T = try {
        body()
    } catch (_: DataAccessException) {
        unavailable()
    } catch (_: D101RequestInvalid) {
        unavailable()
    } catch (_: IllegalArgumentException) {
        unavailable()
    }

    private fun <T : Any> transaction(body: () -> T): T = try {
        requireNotNull(transactions.execute { body() })
    } catch (_: DuplicateKeyException) {
        conflict()
    } catch (_: ServerPublicationConflict) {
        conflict()
    } catch (_: ServerRegistryTransitionConflict) {
        conflict()
    } catch (_: DataAccessException) {
        unavailable()
    } catch (_: ServerPublicationSourceUnavailable) {
        unavailable()
    } catch (_: ServerPublicationRegistrationMissing) {
        conflict()
    } catch (_: D101RequestInvalid) {
        unavailable()
    } catch (_: IllegalArgumentException) {
        unavailable()
    }

    private fun conflict(): Nothing = throw D101OperationConflict()
    private fun unavailable(): Nothing = throw D101ObservationUnavailable()
}

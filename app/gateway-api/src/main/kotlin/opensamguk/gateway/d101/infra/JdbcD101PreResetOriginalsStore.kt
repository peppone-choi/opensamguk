package opensamguk.gateway.d101.infra

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.publication.domain.ServerPublication
import opensamguk.gateway.publication.domain.ServerPublicationState
import opensamguk.gateway.publication.domain.ServerPublicationTarget
import opensamguk.gateway.service.ServerDef
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/** Called by PREPARE after its parent -> publication locks, before the publication write.
 * The insert commits or rolls back with PREPARE; this class never starts a capture transaction. */
internal class JdbcD101PreResetOriginalsStore(jdbc: JdbcTemplate, private val mapper: ObjectMapper) : D101PreResetOriginalsReader {
    private val jdbc = JdbcTemplate(requireNotNull(jdbc.dataSource)).apply { queryTimeout = 1 }
    private val json = D101StrictJson(mapper)
    private val transactions = TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.dataSource))).apply { timeout = 2 }

    fun captureLockedForPrepare(
        candidate: D101PrepareCandidate, lockedCanonical: ServerDef, lockedPublication: ServerPublication,
    ): D101PreResetOriginalsRead = observed {
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            TransactionSynchronizationManager.isCurrentTransactionReadOnly()) conflict()
        val canonical = lockParent()
        val publication = lockPublication()
        if (canonical != lockedCanonical || publication != lockedPublication) conflict()
        val intent = candidate.intent
        if (!D101StrictJson.OPERATION.matches(intent.operationId) ||
            !listOf(intent.sha256, intent.targetFingerprint, candidate.gatewayPayloadSha256)
                .all { D101StrictJson.SHA.matches(it) } ||
            intent.initialPublicRevision <= 0) conflict()
        val existing = read(intent.operationId)
        if (existing != null) {
            requireBinding(existing, intent.operationId, intent.sha256, intent.targetFingerprint,
                candidate.gatewayPayloadSha256, intent.initialPublicRevision)
            return@observed existing
        }
        if (publication.state != ServerPublicationState.PUBLIC || publication.revision != intent.initialPublicRevision ||
            count("game_server_d101_execution", intent.operationId) != 0 ||
            count("game_server_operation_reservation", intent.operationId) != 0 ||
            count("game_server_publication_operation", intent.operationId) != 0) conflict()
        val original = encode(candidate, canonical, publication)
        val capture = validated(original)
        requireBinding(capture, intent.operationId, intent.sha256, intent.targetFingerprint,
            candidate.gatewayPayloadSha256, intent.initialPublicRevision)
        if (jdbc.update(
                """INSERT INTO game_server_d101_pre_reset_originals
                    (operation_id,intent_sha,target_fingerprint,gateway_payload_sha,initial_public_revision,original_sha,original_bytes)
                    VALUES (?,?,?,?,?,?,?)""".trimIndent(),
                intent.operationId, intent.sha256, intent.targetFingerprint, candidate.gatewayPayloadSha256,
                intent.initialPublicRevision, capture.originalSha256, original,
            ) != 1) unavailable()
        capture
    }

    /** Existing QUERY purpose must be checked by the caller first. This locked reread
     * never substitutes current post-PREPARE rows for the captured pre-reset bytes. */
    override fun readForQuery(execution: D101Execution): D101PreResetOriginalsRead = transaction {
        lockParent()
        lockPublication()
        val rows = jdbc.query(
            """SELECT intent_sha,gateway_payload_sha,target_fingerprint,initial_public_revision,
                      verifying_revision,state,prepare_payload,intent_bytes
                 FROM game_server_d101_execution WHERE operation_id=? FOR UPDATE""".trimIndent(),
            { rs, _ -> CurrentExecution(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
                rs.getString(6), rs.getBytes(7), rs.getBytes(8),
            ) }, execution.intent.operationId,
        )
        val current = rows.singleOrNull() ?: unavailable()
        if (current.intentSha != execution.intent.sha256 || current.payloadSha != execution.gatewayPayloadSha256 ||
            current.targetFingerprint != execution.intent.targetFingerprint ||
            current.initialRevision != execution.intent.initialPublicRevision ||
            current.verifyingRevision != execution.verifyingRevision || current.state != execution.state.name ||
            !current.preparePayload.contentEquals(execution.preparePayload()) ||
            !current.intentBytes.contentEquals(execution.intentBytes())) conflict()
        val original = read(execution.intent.operationId) ?: unavailable()
        requireBinding(original, execution.intent.operationId, execution.intent.sha256,
            execution.intent.targetFingerprint, execution.gatewayPayloadSha256, execution.intent.initialPublicRevision)
        original
    }

    private fun lockParent(): ServerDef = jdbc.query(
        """SELECT server_id,display_name,game_api_url,game_engine_url,deploy_project,generation,scenario_code
             FROM game_server WHERE server_id='pep' FOR UPDATE""".trimIndent(),
        { rs, _ -> ServerDef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getObject(6, Integer::class.java)?.toInt(), rs.getString(7)) },
    ).singleOrNull() ?: unavailable()

    private fun lockPublication(): ServerPublication = jdbc.query(
        """SELECT server_id,state,revision,operation_id,expected_generation,expected_scenario_code,target_fingerprint
             FROM game_server_publication WHERE server_id='pep' FOR UPDATE""".trimIndent(),
        { rs, _ ->
            val op = rs.getString(4)
            val generation = rs.getObject(5, Integer::class.java)?.toInt()
            val scenario = rs.getString(6)
            val fingerprint = rs.getString(7)
            val target = if (listOf(op, generation, scenario, fingerprint).all { it == null }) null
                else ServerPublicationTarget(requireNotNull(op), requireNotNull(generation),
                    requireNotNull(scenario), requireNotNull(fingerprint))
            ServerPublication(rs.getString(1), ServerPublicationState.valueOf(rs.getString(2)), rs.getLong(3), target)
        },
    ).singleOrNull() ?: unavailable()

    private fun count(table: String, operationId: String): Int =
        requireNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE operation_id=?", Int::class.java, operationId))

    private fun read(operationId: String): D101PreResetOriginalsRead? = jdbc.query(
        """SELECT intent_sha,target_fingerprint,gateway_payload_sha,initial_public_revision,original_sha,original_bytes
             FROM game_server_d101_pre_reset_originals WHERE operation_id=?""".trimIndent(),
        { rs, _ ->
            val value = validated(rs.getBytes(6) ?: unavailable())
            if (value.originalSha256 != rs.getString(5)) unavailable()
            requireBinding(value, operationId, rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4))
            value
        }, operationId,
    ).singleOrNull()

    private fun encode(candidate: D101PrepareCandidate, canonical: ServerDef, publication: ServerPublication): ByteArray {
        val target = publication.target
        val bytes = mapper.writeValueAsBytes(linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "kind" to "D101_PRE_RESET_ORIGINALS_V1",
            "operationId" to candidate.intent.operationId, "approvalIntentSha256" to candidate.intent.sha256,
            "targetFingerprint" to candidate.intent.targetFingerprint,
            "gatewayPayloadSha256" to candidate.gatewayPayloadSha256,
            "initialPublicRevision" to candidate.intent.initialPublicRevision.toString(),
            "oldRegistry" to linkedMapOf<String, Any?>(
                "id" to canonical.id, "name" to canonical.name, "gameApiUrl" to canonical.gameApiUrl,
                "gameEngineUrl" to canonical.gameEngineUrl, "deployProject" to canonical.deployProject,
                "generation" to canonical.generation, "scenarioCode" to canonical.scenarioCode,
            ),
            "oldPublication" to linkedMapOf<String, Any?>(
                "state" to publication.state.name, "revision" to publication.revision.toString(),
                "operationId" to target?.operationId, "expectedGeneration" to target?.expectedGeneration,
                "expectedScenarioCode" to target?.expectedScenarioCode, "targetFingerprint" to target?.fingerprint,
            ),
        ))
        if (bytes.isEmpty() || bytes.size > 16 * 1024) unavailable()
        return bytes
    }

    private fun validated(bytes: ByteArray): D101PreResetOriginalsRead {
        val root = json.objectBytes(bytes, ROOT_KEYS, 16 * 1024)
        if (json.positiveLong(root["schemaVersion"]) != 1L ||
            json.text(root["kind"]) != "D101_PRE_RESET_ORIGINALS_V1") unavailable()
        val op = json.text(root["operationId"])
        if (!D101StrictJson.OPERATION.matches(op)) unavailable()
        val intent = json.sha(root["approvalIntentSha256"])
        val target = json.sha(root["targetFingerprint"])
        val payload = json.sha(root["gatewayPayloadSha256"])
        val revision = json.revision(root["initialPublicRevision"])
        val registry = exactObject(root["oldRegistry"], REGISTRY_KEYS)
        if (json.text(registry["id"]) != "pep" || json.text(registry["name"]).isBlank() ||
            json.text(registry["gameApiUrl"]).isBlank() || json.text(registry["gameEngineUrl"]).isBlank() ||
            json.text(registry["deployProject"]).isBlank()) unavailable()
        nullableNonNegative(registry["generation"])
        nullableText(registry["scenarioCode"])
        val publication = exactObject(root["oldPublication"], PUBLICATION_KEYS)
        if (json.text(publication["state"]) != "PUBLIC" || json.revision(publication["revision"]) != revision) unavailable()
        val oldOp = nullableText(publication["operationId"])
        val oldGeneration = nullableNonNegative(publication["expectedGeneration"])
        val oldScenario = nullableText(publication["expectedScenarioCode"])
        val oldFingerprint = nullableText(publication["targetFingerprint"])
        if (listOf(oldOp, oldGeneration, oldScenario, oldFingerprint).any { it != null } &&
            (oldOp == null || !D101StrictJson.OPERATION.matches(oldOp) || oldGeneration == null ||
                oldScenario == null || oldFingerprint == null || !D101StrictJson.SHA.matches(oldFingerprint))) unavailable()
        return D101PreResetOriginalsRead(op, intent, target, payload, revision, D101StrictJson.hash(bytes), bytes)
    }

    private fun exactObject(node: JsonNode?, keys: Set<String>): JsonNode {
        if (node == null || !node.isObject || node.fieldNames().asSequence().toSet() != keys) unavailable()
        return node
    }

    private fun nullableText(node: JsonNode?): String? = when {
        node == null -> unavailable()
        node.isNull -> null
        node.isTextual && node.textValue().isNotBlank() -> node.textValue()
        else -> unavailable()
    }

    private fun nullableNonNegative(node: JsonNode?): Int? = when {
        node == null -> unavailable()
        node.isNull -> null
        node.isIntegralNumber && node.canConvertToInt() && node.intValue() >= 0 -> node.intValue()
        else -> unavailable()
    }

    private fun requireBinding(value: D101PreResetOriginalsRead, op: String, intent: String, target: String,
                               payload: String, revision: Long) {
        if (value.operationId != op || value.approvalIntentSha256 != intent || value.targetFingerprint != target ||
            value.gatewayPayloadSha256 != payload || value.initialPublicRevision != revision) conflict()
    }

    private fun <T : Any> transaction(body: () -> T): T = observed { requireNotNull(transactions.execute { body() }) }
    private fun <T : Any> observed(body: () -> T): T = try {
        body()
    } catch (_: DuplicateKeyException) {
        conflict()
    } catch (_: DataAccessException) {
        unavailable()
    } catch (_: D101RequestInvalid) {
        unavailable()
    } catch (_: JsonProcessingException) {
        unavailable()
    } catch (_: IllegalArgumentException) {
        unavailable()
    } catch (_: IllegalStateException) {
        unavailable()
    }

    private fun conflict(): Nothing = throw D101OperationConflict()
    private fun unavailable(): Nothing = throw D101ObservationUnavailable()

    private data class CurrentExecution(
        val intentSha: String, val payloadSha: String, val targetFingerprint: String,
        val initialRevision: Long, val verifyingRevision: Long, val state: String,
        val preparePayload: ByteArray, val intentBytes: ByteArray,
    )

    private companion object {
        val ROOT_KEYS = setOf("schemaVersion", "kind", "operationId", "approvalIntentSha256",
            "targetFingerprint", "gatewayPayloadSha256", "initialPublicRevision", "oldRegistry", "oldPublication")
        val REGISTRY_KEYS = setOf("id", "name", "gameApiUrl", "gameEngineUrl", "deployProject",
            "generation", "scenarioCode")
        val PUBLICATION_KEYS = setOf("state", "revision", "operationId", "expectedGeneration",
            "expectedScenarioCode", "targetFingerprint")
    }
}

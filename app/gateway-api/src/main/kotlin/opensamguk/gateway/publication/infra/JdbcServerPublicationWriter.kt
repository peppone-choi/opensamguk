package opensamguk.gateway.publication.infra

import opensamguk.gateway.publication.application.ServerPublicationRegistrationMissing
import opensamguk.gateway.publication.domain.*
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate

@Repository
class JdbcServerPublicationWriter(
    jdbc: JdbcTemplate,
    private val source: ServerPublicationRepository,
    private val verifier: ServerPublicationReceiptVerifier,
) : ServerPublicationWriter {
    private val jdbc = JdbcTemplate(requireNotNull(jdbc.dataSource)).apply { queryTimeout = 1 }
    private val transactions = TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.dataSource)))

    override fun verifying(command: VerifyServerPublication): ServerPublication = transaction {
        require(command.expectedRevision > 0)
        val current = locked(command.serverId)
        val prior = operation(command.target.operationId)
        if (prior != null) {
            if (prior.serverId != command.serverId || prior.target != command.target ||
                prior.expectedRevision != command.expectedRevision || current.target != command.target) conflict()
            // Return the current recorded state. A completed operation never
            // closes again and can never be presented as a new VERIFYING CAS.
            if (current.state == ServerPublicationState.VERIFYING) {
                if (prior.publishedRevision != null || current.revision != prior.verifyingRevision) conflict()
            } else {
                if (prior.publishedRevision == null || current.revision != prior.publishedRevision ||
                    prior.receiptSha256?.matches(Regex("[a-f0-9]{64}")) != true) conflict()
            }
            return@transaction current
        }
        if (current.state != ServerPublicationState.PUBLIC || current.revision != command.expectedRevision) conflict()
        val next = nextRevision(current.revision)
        jdbc.update(
            """INSERT INTO game_server_publication_operation
                (operation_id, server_id, expected_generation, expected_scenario_code, target_fingerprint,
                 expected_revision, verifying_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
            command.target.operationId, command.serverId, command.target.expectedGeneration,
            command.target.expectedScenarioCode, command.target.fingerprint, command.expectedRevision, next,
        )
        val changed = jdbc.update(
            """UPDATE game_server_publication SET state='VERIFYING', revision=?, operation_id=?,
                expected_generation=?, expected_scenario_code=?, target_fingerprint=?
                WHERE server_id=? AND revision=? AND state='PUBLIC'""".trimIndent(),
            next, command.target.operationId, command.target.expectedGeneration,
            command.target.expectedScenarioCode, command.target.fingerprint, command.serverId, command.expectedRevision,
        )
        if (changed != 1) conflict()
        ServerPublication(command.serverId, ServerPublicationState.VERIFYING, next, command.target)
    }

    override fun publish(command: PublishServerPublication): ServerPublication = transaction {
        require(command.expectedRevision > 0 && command.operationId.matches(Regex("[a-f0-9]{32}")) &&
            command.receiptSha256.matches(Regex("[a-f0-9]{64}")))
        val current = locked(command.serverId)
        val prior = operation(command.operationId) ?: conflict()
        if (prior.serverId != command.serverId || prior.target != current.target ||
            prior.verifyingRevision != command.expectedRevision) conflict()
        if (prior.publishedRevision != null) {
            if (prior.receiptSha256 != command.receiptSha256 || current.state != ServerPublicationState.PUBLIC ||
                current.revision != prior.publishedRevision) conflict()
            // Auth and identity were checked; expired evidence cannot cause
            // an already completed PUBLIC operation to be executed a second time.
            return@transaction current
        }
        if (current.state != ServerPublicationState.VERIFYING || current.revision != command.expectedRevision) conflict()
        verifier.verify(command.serverId, current, command.receiptSha256)
        val next = nextRevision(current.revision)
        val changed = jdbc.update(
            """UPDATE game_server_publication SET state='PUBLIC', revision=?
                WHERE server_id=? AND revision=? AND state='VERIFYING' AND operation_id=?""".trimIndent(),
            next, command.serverId, command.expectedRevision, command.operationId,
        )
        if (changed != 1) conflict()
        if (jdbc.update(
                """UPDATE game_server_publication_operation SET published_revision=?, validation_receipt_sha256=?
                    WHERE operation_id=? AND published_revision IS NULL""".trimIndent(),
                next, command.receiptSha256, command.operationId,
            ) != 1) conflict()
        ServerPublication(command.serverId, ServerPublicationState.PUBLIC, next, current.target)
    }

    private fun locked(serverId: String): ServerPublication {
        require(serverId.matches(Regex("[a-z0-9]{1,48}")))
        // Membership and publication use the same transaction. Missing publication
        // cannot become a success-shaped absent registration or a new default row.
        // Match Registry's parent-before-publication order. Keep the canonical
        // metadata stable while the exact receipt verifier checks it and CAS
        // commits; unregister/reset cannot move it between read and PUBLIC.
        val registered = jdbc.query("SELECT server_id FROM game_server WHERE server_id=? FOR UPDATE", { rs, _ -> rs.getString(1) }, serverId)
        if (registered.isEmpty()) throw ServerPublicationRegistrationMissing()
        if (registered.size != 1) throw ServerPublicationSourceUnavailable()
        val locked = jdbc.query("SELECT server_id FROM game_server_publication WHERE server_id=? FOR UPDATE", { rs, _ -> rs.getString(1) }, serverId)
        if (locked.size != 1) throw ServerPublicationSourceUnavailable()
        return source.find(serverId) ?: throw ServerPublicationSourceUnavailable()
    }

    private fun operation(operationId: String): RecordedOperation? {
        val rows = try {
            jdbc.query(
            """SELECT server_id, expected_generation, expected_scenario_code, target_fingerprint,
                expected_revision, verifying_revision, published_revision, validation_receipt_sha256
                FROM game_server_publication_operation WHERE operation_id=?""".trimIndent(),
            { rs, _ -> RecordedOperation(
                rs.getString("server_id"),
                ServerPublicationTarget(operationId, rs.getInt("expected_generation"), rs.getString("expected_scenario_code"), rs.getString("target_fingerprint")),
                rs.getLong("expected_revision"), rs.getLong("verifying_revision"),
                rs.getObject("published_revision", java.lang.Long::class.java)?.toLong(),
                rs.getString("validation_receipt_sha256"),
            ) }, operationId,
            )
        } catch (_: IllegalArgumentException) {
            throw ServerPublicationSourceUnavailable()
        }
        if (rows.size > 1) throw ServerPublicationSourceUnavailable()
        return rows.singleOrNull()
    }

    private fun nextRevision(current: Long): Long {
        if (current == Long.MAX_VALUE) conflict()
        return current + 1
    }

    private fun <T : Any> transaction(body: () -> T): T = try {
        requireNotNull(transactions.execute { body() })
    } catch (_: DuplicateKeyException) {
        conflict()
    } catch (_: DataAccessException) {
        throw ServerPublicationSourceUnavailable()
    }

    private fun conflict(): Nothing = throw ServerPublicationConflict()
    private data class RecordedOperation(
        val serverId: String, val target: ServerPublicationTarget,
        val expectedRevision: Long, val verifyingRevision: Long,
        val publishedRevision: Long?, val receiptSha256: String?,
    ) {
        init {
            require(serverId.matches(Regex("[a-z0-9]{1,48}")) && expectedRevision > 0 && verifyingRevision > expectedRevision)
            require(
                (publishedRevision == null && receiptSha256 == null) ||
                    (publishedRevision != null && publishedRevision > verifyingRevision &&
                        receiptSha256?.matches(Regex("[a-f0-9]{64}")) == true),
            )
        }
    }
}

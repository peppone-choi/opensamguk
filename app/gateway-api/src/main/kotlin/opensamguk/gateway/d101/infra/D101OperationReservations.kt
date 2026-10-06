package opensamguk.gateway.d101.infra

import opensamguk.gateway.publication.domain.ServerPublicationConflict
import opensamguk.gateway.publication.domain.ServerPublicationSourceUnavailable
import opensamguk.gateway.publication.domain.ServerPublicationTarget
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager

internal enum class D101OperationKind { PUBLICATION_ONLY, D101_RESET, RECOVERY }

/** All publication and D101 callers participate in the same durable ID namespace. */
internal class D101OperationReservations(private val jdbc: JdbcTemplate) {
    fun reserve(kind: D101OperationKind, server: String, target: ServerPublicationTarget, revision: Long) {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        require(revision > 0)
        val prior = find(target.operationId)
        if (prior != null) {
            if (prior != Binding(kind, server, target.fingerprint, revision)) throw ServerPublicationConflict()
            return
        }
        jdbc.update(
            """INSERT INTO game_server_operation_reservation
                (operation_id, kind, server_id, target_fingerprint, initial_public_revision)
                VALUES (?, ?, ?, ?, ?)""".trimIndent(),
            target.operationId, kind.name, server, target.fingerprint, revision,
        )
    }

    fun find(operationId: String): Binding? {
        val rows = jdbc.query(
            """SELECT kind, server_id, target_fingerprint, initial_public_revision
                FROM game_server_operation_reservation WHERE operation_id=?""".trimIndent(),
            { rs, _ -> try {
                Binding(
                    D101OperationKind.valueOf(rs.getString("kind")), rs.getString("server_id"),
                    rs.getString("target_fingerprint"), rs.getLong("initial_public_revision"),
                ).also {
                    require(it.server.matches(Regex("[a-z0-9]{1,48}")) && it.targetFingerprint.matches(Regex("[a-f0-9]{64}")) && it.initialRevision > 0)
                }
            } catch (_: IllegalArgumentException) {
                throw ServerPublicationSourceUnavailable()
            } }, operationId,
        )
        if (rows.size > 1) throw ServerPublicationSourceUnavailable()
        return rows.singleOrNull()
    }

    data class Binding(val kind: D101OperationKind, val server: String, val targetFingerprint: String, val initialRevision: Long)
}

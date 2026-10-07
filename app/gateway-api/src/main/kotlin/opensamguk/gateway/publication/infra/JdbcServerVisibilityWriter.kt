package opensamguk.gateway.publication.infra

import opensamguk.gateway.publication.application.ServerPublicationRegistrationMissing
import opensamguk.gateway.publication.domain.*
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate

@Repository
class JdbcServerVisibilityWriter(jdbc: JdbcTemplate, private val source: ServerPublicationRepository) : ServerVisibilityWriter {
    private val jdbc = JdbcTemplate(requireNotNull(jdbc.dataSource)).apply { queryTimeout = 1 }
    private val transaction = TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.dataSource)))

    override fun change(command: ChangeServerVisibility): ServerPublication = try {
        requireNotNull(transaction.execute {
            // Same parent -> publication order as lifecycle/reset. No operation may
            // interleave its generation change with an operator visibility decision.
            val member = jdbc.query("SELECT server_id FROM game_server WHERE server_id=? FOR UPDATE",
                { rs, _ -> rs.getString(1) }, command.serverId)
            if (member.isEmpty()) throw ServerPublicationRegistrationMissing()
            if (member.size != 1) throw ServerPublicationSourceUnavailable()
            val locked = jdbc.query("SELECT server_id FROM game_server_publication WHERE server_id=? FOR UPDATE",
                { rs, _ -> rs.getString(1) }, command.serverId)
            if (locked.size != 1) throw ServerPublicationSourceUnavailable()
            val current = source.find(command.serverId) ?: throw ServerPublicationSourceUnavailable()
            if (current.revision != command.expectedRevision || current.state != ServerPublicationState.PUBLIC ||
                jdbc.queryForObject("SELECT COUNT(*) FROM game_server_registry_transition WHERE server_id=?",
                    Int::class.java, command.serverId) != 0) throw ServerPublicationConflict()
            if (current.publiclyVisible == command.publiclyVisible) return@execute current
            if (current.revision == Long.MAX_VALUE) throw ServerPublicationConflict()
            val changed = jdbc.update("""UPDATE game_server_publication SET publicly_visible=?, revision=revision+1
                WHERE server_id=? AND revision=? AND state='PUBLIC'""".trimIndent(),
                command.publiclyVisible, command.serverId, command.expectedRevision)
            if (changed != 1) throw ServerPublicationConflict()
            current.copy(publiclyVisible = command.publiclyVisible, revision = current.revision + 1)
        })
    } catch (_: DataAccessException) {
        throw ServerPublicationSourceUnavailable()
    }
}

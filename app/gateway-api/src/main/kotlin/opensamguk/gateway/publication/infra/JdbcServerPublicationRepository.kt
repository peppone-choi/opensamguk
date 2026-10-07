package opensamguk.gateway.publication.infra

import opensamguk.gateway.publication.domain.RegisteredPublicServer
import opensamguk.gateway.publication.domain.ServerPublication
import opensamguk.gateway.publication.domain.ServerPublicationRepository
import opensamguk.gateway.publication.domain.ServerPublicationSourceUnavailable
import opensamguk.gateway.publication.domain.ServerPublicationState
import opensamguk.gateway.publication.domain.ServerPublicationTarget
import opensamguk.gateway.service.ServerDef
import opensamguk.gateway.service.ServerRegistry
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class JdbcServerPublicationRepository(jdbc: JdbcTemplate, private val registry: ServerRegistry) : ServerPublicationRepository {
    // Do not change the shared template's timeout or membership/auth behavior.
    // Connection-pool acquisition is a separate budget from this statement timeout.
    private val publicationJdbc = JdbcTemplate(requireNotNull(jdbc.dataSource)).apply { queryTimeout = 1 }

    override fun find(serverId: String): ServerPublication? = readSource {
        require(serverId.matches(Regex("[a-z0-9]{1,48}")))
        val rows = publicationJdbc.query("$SELECT WHERE g.server_id = ?", { rs, _ -> mapRow(rs) }, serverId)
        if (rows.size > 1) throw ServerPublicationSourceUnavailable()
        rows.singleOrNull()?.publication
    }

    override fun listPublicServers(): List<RegisteredPublicServer> = readSource {
        // Read the left join before filtering, so a missing source row cannot
        // silently disappear into a success-shaped empty public list.
        val rows = publicationJdbc.query("$SELECT ORDER BY g.sort_order, g.server_id") { rs, _ -> mapRow(rs) }
        if (rows.map { it.publication.serverId }.toSet().size != rows.size) throw ServerPublicationSourceUnavailable()
        rows.filter { it.publication.state == ServerPublicationState.PUBLIC && it.publication.publiclyVisible }.map { it.server }
    }

    private fun mapRow(rs: ResultSet): PublicationRow {
        val state = rs.getString("publication_state") ?: throw ServerPublicationSourceUnavailable()
        val revision = rs.getObject("publication_revision", java.lang.Long::class.java)?.toLong()
            ?: throw ServerPublicationSourceUnavailable()
        val operationId = rs.getString("publication_operation_id")
        val generation = rs.getObject("publication_generation", Integer::class.java)?.toInt()
        val scenario = rs.getString("publication_scenario")
        val fingerprint = rs.getString("publication_fingerprint")
        val fields = listOf(operationId, generation, scenario, fingerprint)
        val target = if (fields.all { it == null }) null else ServerPublicationTarget(
            requireNotNull(operationId), requireNotNull(generation), requireNotNull(scenario), requireNotNull(fingerprint),
        )
        val id = rs.getString("server_id")
        val member = ServerDef(
            id = id, name = rs.getString("display_name"),
            gameApiUrl = rs.getString("game_api_url"), gameEngineUrl = rs.getString("game_engine_url"),
            deployProject = rs.getString("deploy_project"),
            generation = rs.getObject("generation", Integer::class.java)?.toInt(),
            scenarioCode = rs.getString("scenario_code"),
        )
        require(registry.acceptsCanonicalMembership(member))
        return PublicationRow(
            ServerPublication(id, ServerPublicationState.valueOf(state), revision, target,
                (rs.getObject("publicly_visible") as? Boolean)
                    ?: throw ServerPublicationSourceUnavailable()),
            RegisteredPublicServer(id, member.name, member.generation),
        )
    }

    private fun <T> readSource(read: () -> T): T = try {
        read()
    } catch (_: DataAccessException) {
        throw ServerPublicationSourceUnavailable()
    } catch (_: IllegalArgumentException) {
        throw ServerPublicationSourceUnavailable()
    }

    private data class PublicationRow(val publication: ServerPublication, val server: RegisteredPublicServer)

    private companion object {
        val SELECT = """
            SELECT g.server_id, g.display_name, g.generation, g.scenario_code,
                   g.game_api_url, g.game_engine_url, g.deploy_project,
                   p.state AS publication_state, p.revision AS publication_revision, p.publicly_visible,
                   p.operation_id AS publication_operation_id,
                   p.expected_generation AS publication_generation,
                   p.expected_scenario_code AS publication_scenario,
                   p.target_fingerprint AS publication_fingerprint
              FROM game_server g LEFT JOIN game_server_publication p ON p.server_id = g.server_id
        """.trimIndent()
    }
}

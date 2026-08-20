package opensamguk.gateway.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp

data class ServerDef(
    val id: String,
    val name: String,
    val gameApiUrl: String,
    val gameEngineUrl: String,
    val deployProject: String,
    val generation: Int? = null,
    val scenarioCode: String? = null,
)

enum class ServerRegistryTransitionAction {
    CREATE,
    CLOSE,
}

data class ServerRegistryTransition(
    val action: ServerRegistryTransitionAction,
    val server: ServerDef,
    val remoteApplied: Boolean,
    val dispatched: Boolean,
    val newlyCreated: Boolean,
    val ownerToken: String,
)

class ServerRegistryTransitionConflict(message: String) : IllegalStateException(message)

@Component
class ServerRegistry(
    @Value("\${SERVER_REGISTRY_JSON:}") private val registryJson: String,
    private val objectMapper: ObjectMapper,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(ServerRegistry::class.java)
    private val transactions = TransactionTemplate(
        DataSourceTransactionManager(requireNotNull(jdbc.dataSource) { "Server registry requires a DataSource" }),
    )

    init {
        seedEmptyRegistry()
    }

    fun all(): List<ServerDef> =
        try {
            val rows = jdbc.query(
                """
                SELECT server_id, display_name, game_api_url, game_engine_url, deploy_project,
                       generation, scenario_code
                  FROM game_server
                 ORDER BY sort_order, server_id
                """.trimIndent(),
            ) { rs, _ ->
                ServerDef(
                    id = rs.getString("server_id"),
                    name = rs.getString("display_name"),
                    gameApiUrl = rs.getString("game_api_url"),
                    gameEngineUrl = rs.getString("game_engine_url"),
                    deployProject = rs.getString("deploy_project"),
                    generation = rs.getObject("generation", Integer::class.java)?.toInt(),
                    scenarioCode = rs.getString("scenario_code"),
                )
            }
            validateCollection(rows) ?: invalidDatabaseRegistry()
        } catch (e: DataAccessException) {
            log.error("game_server read failed - returning an empty registry", e)
            emptyList()
        }

    fun find(id: String): ServerDef? = all().firstOrNull { it.id == id }

    fun default(): ServerDef? = all().firstOrNull()

    fun register(server: ServerDef) {
        require(validateCollection(listOf(server)) != null) { "Invalid canonical server: ${server.id}" }
        transactions.executeWithoutResult {
            registerWithinTransaction(server)
        }
    }

    fun unregister(serverId: String) {
        jdbc.update("DELETE FROM game_server WHERE server_id = ?", serverId)
    }

    fun beginTransition(action: ServerRegistryTransitionAction, server: ServerDef, ownerToken: String): ServerRegistryTransition {
        require(validateCollection(listOf(server)) != null) { "Invalid canonical server: ${server.id}" }
        return try {
            transactions.execute {
                val existing = findTransition(server.id, forUpdate = true)
                if (existing != null) {
                    if (existing.action != action || existing.server != server) {
                        throw ServerRegistryTransitionConflict("Another server registry transition is already pending for ${server.id}")
                    }
                    val claimed = jdbc.update(
                        """
                        UPDATE game_server_registry_transition
                           SET owner_token = ?, lease_until = ?
                         WHERE server_id = ?
                           AND lease_until <= CURRENT_TIMESTAMP
                        """.trimIndent(),
                        ownerToken,
                        leaseUntil(),
                        server.id,
                    )
                    if (claimed != 1) {
                        throw ServerRegistryTransitionConflict("Another server registry transition is already pending for ${server.id}")
                    }
                    return@execute existing.copy(newlyCreated = false, ownerToken = ownerToken)
                }
                val registered = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM game_server WHERE server_id = ?",
                    Long::class.java,
                    server.id,
                ) == 1L
                if ((action == ServerRegistryTransitionAction.CREATE && registered) ||
                    (action == ServerRegistryTransitionAction.CLOSE && !registered)
                ) {
                    throw ServerRegistryTransitionConflict("Server registry already reached the requested state for ${server.id}")
                }
                jdbc.update(
                    """
                    INSERT INTO game_server_registry_transition (
                        server_id, action, display_name, game_api_url, game_engine_url, deploy_project,
                        generation, scenario_code, dispatched, remote_applied, owner_token, lease_until
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE, FALSE, ?, ?)
                    """.trimIndent(),
                    server.id,
                    action.name,
                    server.name,
                    server.gameApiUrl,
                    server.gameEngineUrl,
                    server.deployProject,
                    server.generation,
                    server.scenarioCode,
                    ownerToken,
                    leaseUntil(),
                )
                ServerRegistryTransition(
                    action,
                    server,
                    remoteApplied = false,
                    dispatched = false,
                    newlyCreated = true,
                    ownerToken = ownerToken,
                )
            } ?: error("Server registry transition transaction returned no result")
        } catch (e: DuplicateKeyException) {
            throw ServerRegistryTransitionConflict("Another server registry transition is already pending for ${server.id}")
        }
    }

    fun markDispatched(serverId: String, action: ServerRegistryTransitionAction, ownerToken: String) {
        val updated = jdbc.update(
            """
            UPDATE game_server_registry_transition
               SET dispatched = TRUE
             WHERE server_id = ? AND action = ? AND owner_token = ? AND dispatched = FALSE
            """.trimIndent(),
            serverId,
            action.name,
            ownerToken,
        )
        check(updated == 1) { "No dispatchable server registry transition for $serverId" }
    }

    fun markRemoteApplied(serverId: String, action: ServerRegistryTransitionAction, ownerToken: String) {
        val updated = jdbc.update(
            """
            UPDATE game_server_registry_transition
               SET remote_applied = TRUE
             WHERE server_id = ? AND action = ? AND owner_token = ?
            """.trimIndent(),
            serverId,
            action.name,
            ownerToken,
        )
        check(updated == 1) { "No matching server registry transition for $serverId" }
    }

    fun releaseTransition(serverId: String, action: ServerRegistryTransitionAction, ownerToken: String) {
        jdbc.update(
            """
            UPDATE game_server_registry_transition
               SET lease_until = CURRENT_TIMESTAMP
             WHERE server_id = ? AND action = ? AND owner_token = ?
            """.trimIndent(),
            serverId,
            action.name,
            ownerToken,
        )
    }

    fun completeTransition(serverId: String, action: ServerRegistryTransitionAction, ownerToken: String) {
        transactions.executeWithoutResult {
            val transition = findTransition(serverId, forUpdate = true)
                ?: error("No server registry transition for $serverId")
            check(transition.action == action && transition.remoteApplied && transition.ownerToken == ownerToken) {
                "Server registry transition is not ready for completion: $serverId"
            }
            when (action) {
                ServerRegistryTransitionAction.CREATE -> registerWithinTransaction(transition.server)
                ServerRegistryTransitionAction.CLOSE -> jdbc.update("DELETE FROM game_server WHERE server_id = ?", serverId)
            }
            jdbc.update("DELETE FROM game_server_registry_transition WHERE server_id = ?", serverId)
        }
    }

    fun cancelTransition(serverId: String, action: ServerRegistryTransitionAction, ownerToken: String) {
        jdbc.update(
            """
            DELETE FROM game_server_registry_transition
             WHERE server_id = ? AND action = ? AND owner_token = ? AND remote_applied = FALSE
            """.trimIndent(),
            serverId,
            action.name,
            ownerToken,
        )
    }

    private fun seedEmptyRegistry() {
        if (registryJson.isBlank()) return
        transactions.executeWithoutResult {
            val count = jdbc.queryForObject("SELECT COUNT(*) FROM game_server", Long::class.java) ?: 0L
            if (count != 0L) return@executeWithoutResult
            val seed = parseSeed() ?: return@executeWithoutResult
            seed.forEach(::insert)
        }
    }

    private fun insert(server: ServerDef) {
        jdbc.update(
            """
            INSERT INTO game_server (
                server_id, display_name, game_api_url, game_engine_url, deploy_project,
                generation, scenario_code
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            server.id,
            server.name,
            server.gameApiUrl,
            server.gameEngineUrl,
            server.deployProject,
            server.generation,
            server.scenarioCode,
        )
    }

    private fun registerWithinTransaction(server: ServerDef) {
        val updated = jdbc.update(
            """
            UPDATE game_server
               SET display_name = ?, game_api_url = ?, game_engine_url = ?, deploy_project = ?,
                   generation = ?, scenario_code = ?
             WHERE server_id = ?
            """.trimIndent(),
            server.name,
            server.gameApiUrl,
            server.gameEngineUrl,
            server.deployProject,
            server.generation,
            server.scenarioCode,
            server.id,
        )
        if (updated == 0) insert(server)
    }

    private fun findTransition(serverId: String, forUpdate: Boolean = false): ServerRegistryTransition? =
        jdbc.query(
            """
            SELECT action, display_name, game_api_url, game_engine_url, deploy_project,
                   generation, scenario_code, dispatched, remote_applied, owner_token
              FROM game_server_registry_transition
             WHERE server_id = ?${if (forUpdate) " FOR UPDATE" else ""}
            """.trimIndent(),
            { rs, _ ->
                ServerRegistryTransition(
                    action = ServerRegistryTransitionAction.valueOf(rs.getString("action")),
                    server = ServerDef(
                        id = serverId,
                        name = rs.getString("display_name"),
                        gameApiUrl = rs.getString("game_api_url"),
                        gameEngineUrl = rs.getString("game_engine_url"),
                        deployProject = rs.getString("deploy_project"),
                        generation = rs.getObject("generation", Integer::class.java)?.toInt(),
                        scenarioCode = rs.getString("scenario_code"),
                    ),
                    remoteApplied = rs.getBoolean("remote_applied"),
                    dispatched = rs.getBoolean("dispatched"),
                    newlyCreated = false,
                    ownerToken = rs.getString("owner_token"),
                )
            },
            serverId,
        ).firstOrNull()

    private fun leaseUntil(): Timestamp {
        val databaseNow = requireNotNull(
            jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp::class.java),
        ) { "Database did not return CURRENT_TIMESTAMP" }
        return Timestamp.from(databaseNow.toInstant().plusSeconds(300))
    }

    private fun parseSeed(): List<ServerDef>? =
        try {
            parseCollection(objectMapper.readTree(registryJson)) ?: invalidSeedRegistry()
        } catch (e: Exception) {
            log.error("SERVER_REGISTRY_JSON parse failed - leaving game_server empty", e)
            null
        }

    private fun parseCollection(root: JsonNode): List<ServerDef>? =
        if (root.isArray) parseArray(root) else null

    private fun parseArray(root: JsonNode): List<ServerDef>? {
        val parsed = ArrayList<ServerDef>(root.size())
        val seenIds = HashSet<String>(root.size())
        for (node in root) {
            if (!node.isObject) return null
            val id = text(node, "id") ?: return null
            if (!isPublicServerId(id) || !seenIds.add(id)) return null
            parsed += parseObjectEntry(id, node) ?: return null
        }
        return parsed
    }

    private fun parseObjectEntry(id: String, node: JsonNode): ServerDef? {
        if (!hasExpectedCoordinate(node, defaultGameApiUrl(id), "gameApiUrl") ||
            !hasExpectedCoordinate(node, defaultGameEngineUrl(id), "gameEngineUrl") ||
            !hasExpectedCoordinate(node, defaultDeployProject(id), "deployProject", "project")
        ) {
            return null
        }
        val name = when {
            !node.has("name") -> id
            !node.path("name").isTextual -> return null
            else -> node.path("name").asText().trim().ifBlank { id }
        }
        val generation = intOrNull(node.path("generation"))
        if (node.has("generation") && generation == null) return null
        val scenarioCode = textOrNull(node, "scenarioCode", "scenario")
        return defaultServer(id).copy(name = name, generation = generation, scenarioCode = scenarioCode)
    }

    private fun validateCollection(servers: List<ServerDef>): List<ServerDef>? {
        val seenIds = HashSet<String>(servers.size)
        for (server in servers) {
            if (!isPublicServerId(server.id) || !seenIds.add(server.id)) return null
            if (server.gameApiUrl != defaultGameApiUrl(server.id) ||
                server.gameEngineUrl != defaultGameEngineUrl(server.id) ||
                server.deployProject != defaultDeployProject(server.id) ||
                server.name.isBlank() ||
                server.generation?.let { it < 0 } == true
            ) {
                return null
            }
        }
        return servers
    }

    private fun invalidSeedRegistry(): List<ServerDef>? {
        log.error("SERVER_REGISTRY_JSON violates the canonical server contract - leaving game_server empty")
        return null
    }

    private fun invalidDatabaseRegistry(): List<ServerDef> {
        log.error("game_server violates the canonical server contract - returning an empty registry")
        return emptyList()
    }

    private fun isPublicServerId(id: String): Boolean =
        PUBLIC_SERVER_ID.matches(id) && id !in RESERVED_SERVER_IDS

    private fun defaultServer(id: String): ServerDef =
        ServerDef(
            id = id,
            name = id,
            gameApiUrl = defaultGameApiUrl(id),
            gameEngineUrl = defaultGameEngineUrl(id),
            deployProject = defaultDeployProject(id),
        )

    private fun hasExpectedCoordinate(node: JsonNode, expected: String, vararg fields: String): Boolean =
        fields.all { field ->
            !node.has(field) || (node.path(field).isTextual && node.path(field).asText() == expected)
        }

    private fun defaultGameApiUrl(id: String): String = "http://s$id-game-api:8081"

    private fun defaultGameEngineUrl(id: String): String = "http://s$id-game-engine:8082"

    private fun defaultDeployProject(id: String): String = "opensamguk-s$id"

    private fun intOrNull(node: JsonNode): Int? =
        when {
            node.isInt || node.isLong -> node.asInt()
            node.isTextual -> node.asText().toIntOrNull()
            else -> null
        }

    private fun text(node: JsonNode, field: String): String? =
        node.path(field).takeIf { it.isTextual }?.asText()

    private fun textOrNull(node: JsonNode, vararg fields: String): String? =
        fields.asSequence()
            .mapNotNull { field ->
                if (!node.has(field)) null else node.path(field).takeIf { it.isTextual }?.asText()
            }
            .firstOrNull { it.isNotBlank() }

    private companion object {
        val PUBLIC_SERVER_ID = Regex("^[a-z0-9]{1,48}$")

        val RESERVED_SERVER_IDS = setOf(
            "all", "main", "admin1", "admin2", "admin5", "admin7", "admin8", "auction",
            "battle-center", "betting", "board", "chief-center", "city", "coming-soon", "diplomacy",
            "generals", "global-diplomacy", "history", "inherit", "join", "mailbox", "map", "my",
            "my-boss", "my-cities", "my-generals", "my-nation", "nation", "nation-betting",
            "nation-finance", "npc-control", "rankings", "register", "select-pool", "simulator",
            "tournament", "tournament-admin", "troop", "vote", "world-log",
        )
    }
}

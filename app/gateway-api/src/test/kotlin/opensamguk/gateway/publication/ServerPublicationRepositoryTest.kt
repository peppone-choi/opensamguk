package opensamguk.gateway.publication

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.service.ServerRegistry
import opensamguk.gateway.publication.domain.ServerPublicationSourceUnavailable
import opensamguk.gateway.publication.domain.ServerPublicationState
import opensamguk.gateway.publication.infra.JdbcServerPublicationRepository
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServerPublicationRepositoryTest {
    @Test
    fun `public list hides verifying while internal read preserves its operation and zero`() {
        val jdbc = fixture()
        register(jdbc, "pep", 0)
        register(jdbc, "uni", null)
        jdbc.update("INSERT INTO game_server_publication VALUES (?, 'VERIFYING', 2, ?, 0, 'scenario_3190', ?)", "pep", "a".repeat(32), "b".repeat(64))
        jdbc.update("INSERT INTO game_server_publication (server_id, state, revision) VALUES ('uni', 'PUBLIC', 1)")
        val repository = repository(jdbc)
        assertEquals(listOf("uni"), repository.listPublicServers().map { it.id })
        assertNull(repository.listPublicServers().single().generation)
        val hidden = repository.find("pep")!!
        assertEquals(ServerPublicationState.VERIFYING, hidden.state)
        assertEquals(0, hidden.target!!.expectedGeneration)
        assertEquals("a".repeat(32), hidden.target!!.operationId)
    }

    @Test
    fun `known empty differs from missing publication table row or invalid state`() {
        val jdbc = fixture()
        val repository = repository(jdbc)
        assertEquals(emptyList(), repository.listPublicServers())
        assertNull(repository.find("missing"))
        register(jdbc, "pep", 0)
        assertFailsWith<ServerPublicationSourceUnavailable> { repository.listPublicServers() }
        assertFailsWith<ServerPublicationSourceUnavailable> { repository.find("pep") }
        jdbc.update("INSERT INTO game_server_publication (server_id, state, revision) VALUES ('pep', 'UNKNOWN', 1)")
        assertFailsWith<ServerPublicationSourceUnavailable> { repository.listPublicServers() }
        jdbc.execute("DROP TABLE game_server_publication")
        assertFailsWith<ServerPublicationSourceUnavailable> { repository.listPublicServers() }
    }

    @Test
    fun `public generation zero is preserved and incomplete verifying target is unavailable`() {
        val jdbc = fixture()
        register(jdbc, "pep", 0)
        jdbc.update("INSERT INTO game_server_publication (server_id, state, revision) VALUES ('pep', 'PUBLIC', 1)")
        val repository = repository(jdbc)
        assertEquals(0, repository.listPublicServers().single().generation)
        jdbc.update("UPDATE game_server_publication SET state='VERIFYING', operation_id=? WHERE server_id='pep'", "a".repeat(32))
        assertFailsWith<ServerPublicationSourceUnavailable> { repository.find("pep") }
        assertFailsWith<ServerPublicationSourceUnavailable> { repository.find("../uni") }
    }

    @Test
    fun `wrong coordinates or reserved membership fail closed without rewriting registration`() {
        val jdbc = fixture()
        register(jdbc, "pep", 0)
        jdbc.update("INSERT INTO game_server_publication (server_id, state, revision) VALUES ('pep', 'PUBLIC', 1)")
        val repository = repository(jdbc)
        jdbc.update("UPDATE game_server SET game_api_url='http://untrusted.invalid/' WHERE server_id='pep'")
        assertFailsWith<ServerPublicationSourceUnavailable> { repository.listPublicServers() }
        assertEquals("http://untrusted.invalid/", jdbc.queryForObject("SELECT game_api_url FROM game_server WHERE server_id='pep'", String::class.java))
        jdbc.update("UPDATE game_server SET game_api_url='http://spep-game-api:8081' WHERE server_id='pep'")
        register(jdbc, "all", 0)
        jdbc.update("INSERT INTO game_server_publication (server_id, state, revision) VALUES ('all', 'PUBLIC', 1)")
        assertFailsWith<ServerPublicationSourceUnavailable> { repository.listPublicServers() }
    }

    @Test
    fun `duplicate joined source does not become known absent or a duplicate public entry`() {
        val jdbc = fixture()
        register(jdbc, "pep", 0)
        jdbc.execute("ALTER TABLE game_server_publication DROP PRIMARY KEY")
        jdbc.update("INSERT INTO game_server_publication (server_id, state, revision) VALUES ('pep', 'PUBLIC', 1), ('pep', 'PUBLIC', 2)")
        val repository = repository(jdbc)
        assertFailsWith<ServerPublicationSourceUnavailable> { repository.find("pep") }
        assertFailsWith<ServerPublicationSourceUnavailable> { repository.listPublicServers() }
    }

    private fun fixture(): JdbcTemplate {
        val jdbc = JdbcTemplate(DriverManagerDataSource("jdbc:h2:mem:publication-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
        jdbc.execute("CREATE TABLE game_server (sort_order BIGINT GENERATED ALWAYS AS IDENTITY, server_id VARCHAR(48) PRIMARY KEY, display_name TEXT NOT NULL, generation INTEGER, scenario_code TEXT, game_api_url TEXT, game_engine_url TEXT, deploy_project TEXT)")
        // The permissive fixture deliberately supplies corrupt rows; V75's actual
        // PostgreSQL constraints and Flyway sequence require separate integration QA.
        jdbc.execute("CREATE TABLE game_server_publication (server_id VARCHAR(48) PRIMARY KEY, state VARCHAR(16), revision BIGINT, operation_id VARCHAR(32), expected_generation INTEGER, expected_scenario_code TEXT, target_fingerprint VARCHAR(64))")
        jdbc.execute("CREATE TABLE game_server_registry_seed_state (id SMALLINT PRIMARY KEY, initialized BOOLEAN NOT NULL)")
        jdbc.update("INSERT INTO game_server_registry_seed_state VALUES (1, TRUE)")
        return jdbc
    }

    private fun repository(jdbc: JdbcTemplate) = JdbcServerPublicationRepository(jdbc, ServerRegistry("", ObjectMapper(), jdbc))

    private fun register(jdbc: JdbcTemplate, id: String, generation: Int?) {
        jdbc.update("INSERT INTO game_server (server_id, display_name, generation, game_api_url, game_engine_url, deploy_project) VALUES (?, ?, ?, ?, ?, ?)", id, id, generation, "http://s$id-game-api:8081", "http://s$id-game-engine:8082", "opensamguk-s$id")
    }
}

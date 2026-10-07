package opensamguk.gateway.publication

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.publication.domain.*
import opensamguk.gateway.publication.infra.JdbcServerPublicationRepository
import opensamguk.gateway.publication.infra.JdbcServerVisibilityWriter
import opensamguk.gateway.publication.api.ServerAdmissionDto
import opensamguk.gateway.service.ServerRegistry
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID
import kotlin.test.*

class ServerVisibilityWriterTest {
    @Test
    fun `private hides listing and admission while public restores the same generation`() {
        val (jdbc, source, writer) = fixture()
        val closed = writer.change(ChangeServerVisibility("pep", false, 1))
        assertEquals(2L, closed.revision)
        assertFalse(closed.publiclyVisible)
        assertEquals(ServerPublicationState.PUBLIC, closed.state)
        assertEquals(ServerPublicationState.VERIFYING, ServerAdmissionDto.from(closed).state)
        assertEquals(listOf("uni"), source.listPublicServers().map { it.id })
        val opened = writer.change(ChangeServerVisibility("pep", true, 2))
        assertTrue(opened.publiclyVisible)
        assertEquals(ServerPublicationState.PUBLIC, ServerAdmissionDto.from(opened).state)
        assertEquals(listOf("pep", "uni"), source.listPublicServers().map { it.id })
        assertEquals(0, jdbc.queryForObject("SELECT generation FROM game_server WHERE server_id='pep'", Int::class.java))
    }

    @Test
    fun `stale revision cannot undo another operators toggle and no-op keeps revision`() {
        val (_, source, writer) = fixture()
        assertEquals(1L, writer.change(ChangeServerVisibility("pep", true, 1)).revision)
        writer.change(ChangeServerVisibility("pep", false, 1))
        assertFailsWith<ServerPublicationConflict> { writer.change(ChangeServerVisibility("pep", true, 1)) }
        assertFalse(source.find("pep")!!.publiclyVisible)
    }

    @Test
    fun `validation and lifecycle transitions cannot be published by the toggle`() {
        val (jdbc, source, writer) = fixture()
        jdbc.update("""UPDATE game_server_publication SET state='VERIFYING', operation_id=?,
            expected_generation=0,expected_scenario_code='scenario_3190',target_fingerprint=? WHERE server_id='pep'""",
            "a".repeat(32), "b".repeat(64))
        assertFailsWith<ServerPublicationConflict> { writer.change(ChangeServerVisibility("pep", true, 1)) }
        assertEquals(ServerPublicationState.VERIFYING, source.find("pep")!!.state)
        jdbc.update("INSERT INTO game_server_registry_transition VALUES ('uni')")
        assertFailsWith<ServerPublicationConflict> { writer.change(ChangeServerVisibility("uni", false, 1)) }
        assertTrue(source.find("uni")!!.publiclyVisible)
    }

    @Test
    fun `missing source and overflow leave visibility unchanged`() {
        val (jdbc, source, writer) = fixture()
        jdbc.update("UPDATE game_server_publication SET revision=? WHERE server_id='pep'", Long.MAX_VALUE)
        assertFailsWith<ServerPublicationConflict> { writer.change(ChangeServerVisibility("pep", false, Long.MAX_VALUE)) }
        assertTrue(source.find("pep")!!.publiclyVisible)
        jdbc.update("DELETE FROM game_server_publication WHERE server_id='uni'")
        assertFailsWith<ServerPublicationSourceUnavailable> { writer.change(ChangeServerVisibility("uni", true, 1)) }
    }

    private fun fixture(): Triple<JdbcTemplate, JdbcServerPublicationRepository, JdbcServerVisibilityWriter> {
        val jdbc = JdbcTemplate(DriverManagerDataSource("jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
        jdbc.execute("""CREATE TABLE game_server (sort_order BIGINT, server_id VARCHAR(48) PRIMARY KEY, display_name TEXT,
            generation INTEGER, scenario_code TEXT, game_api_url TEXT, game_engine_url TEXT, deploy_project TEXT)""")
        jdbc.execute("""CREATE TABLE game_server_publication (server_id VARCHAR(48) PRIMARY KEY, state VARCHAR(16), revision BIGINT,
            publicly_visible BOOLEAN NOT NULL DEFAULT TRUE, operation_id VARCHAR(32), expected_generation INTEGER,
            expected_scenario_code TEXT, target_fingerprint VARCHAR(64))""")
        jdbc.execute("CREATE TABLE game_server_registry_transition (server_id VARCHAR(48) PRIMARY KEY)")
        for ((index, id) in listOf("pep", "uni").withIndex()) {
            jdbc.update("INSERT INTO game_server VALUES (?, ?, ?, 0, 'scenario_3190', ?, ?, ?)",
                index, id, id, "http://s$id-game-api:8081", "http://s$id-game-engine:8082", "opensamguk-s$id")
            jdbc.update("INSERT INTO game_server_publication (server_id,state,revision) VALUES (?, 'PUBLIC', 1)", id)
        }
        val source = JdbcServerPublicationRepository(jdbc, ServerRegistry("", ObjectMapper(), jdbc))
        return Triple(jdbc, source, JdbcServerVisibilityWriter(jdbc, source))
    }
}

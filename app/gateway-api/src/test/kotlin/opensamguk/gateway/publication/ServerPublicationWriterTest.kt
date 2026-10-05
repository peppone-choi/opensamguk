package opensamguk.gateway.publication

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.publication.domain.*
import opensamguk.gateway.publication.infra.JdbcServerPublicationRepository
import opensamguk.gateway.publication.infra.JdbcServerPublicationWriter
import opensamguk.gateway.publication.infra.UnavailableServerPublicationReceiptVerifier
import opensamguk.gateway.service.ServerRegistry
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerPublicationWriterTest {
    private val target = ServerPublicationTarget("a".repeat(32), 0, "scenario_3190", "b".repeat(64))
    private val close = VerifyServerPublication("pep", 1, target)
    private val publish = PublishServerPublication("pep", 2, target.operationId, "c".repeat(64))

    @Test
    fun `verifying CAS survives restart and rejects stale or changed operation`() {
        val jdbc = fixture()
        val writer = writer(jdbc)
        assertEquals(2L, writer.verifying(close).revision)
        assertEquals(0, source(jdbc).find("pep")!!.target!!.expectedGeneration)
        assertEquals(2L, writer(jdbc).verifying(close).revision)
        assertFailsWith<ServerPublicationConflict> { writer.verifying(close.copy(expectedRevision = 2)) }
        assertFailsWith<ServerPublicationConflict> { writer.verifying(close.copy(target = target.copy(fingerprint = "d".repeat(64)))) }
        assertFailsWith<ServerPublicationConflict> { writer.verifying(close.copy(serverId = "uni")) }
        assertEquals(2L, source(jdbc).find("pep")!!.revision)
        assertEquals(1L, source(jdbc).find("uni")!!.revision)
    }

    @Test
    fun `missing production receipt source cannot change publication or history`() {
        val jdbc = fixture()
        val writer = writer(jdbc)
        writer.verifying(close)
        assertFailsWith<ServerPublicationSourceUnavailable> { writer.publish(publish) }
        assertEquals(ServerPublicationState.VERIFYING, source(jdbc).find("pep")!!.state)
        assertEquals(2L, source(jdbc).find("pep")!!.revision)
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM game_server_publication_operation WHERE published_revision IS NOT NULL", Int::class.java))
    }

    @Test
    fun `verified transition replays without reverify or a second close`() {
        val jdbc = fixture()
        var verifications = 0
        val verifier = ServerPublicationReceiptVerifier { server, publication, receipt ->
            assertEquals("pep", server)
            assertEquals(target, publication.target)
            assertEquals(2L, publication.revision)
            assertEquals(publish.receiptSha256, receipt)
            verifications++
        }
        val writer = writer(jdbc, verifier)
        writer.verifying(close)
        assertEquals(3L, writer.publish(publish).revision)
        assertEquals(3L, writer(jdbc).publish(publish).revision)
        assertEquals(ServerPublicationState.PUBLIC, writer(jdbc).verifying(close).state)
        assertEquals(1, verifications)
        assertFailsWith<ServerPublicationConflict> { writer.publish(publish.copy(receiptSha256 = "d".repeat(64))) }
        assertFailsWith<ServerPublicationConflict> { writer.verifying(close.copy(expectedRevision = 3)) }
        assertEquals(3L, source(jdbc).find("pep")!!.revision)
    }

    @Test
    fun `history forbids operation reuse after membership deletion and re-registration`() {
        val jdbc = fixture()
        writer(jdbc).verifying(close)
        jdbc.update("DELETE FROM game_server WHERE server_id='pep'")
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM game_server_publication_operation", Int::class.java))
        register(jdbc, "pep")
        assertFailsWith<ServerPublicationConflict> { writer(jdbc).verifying(close) }
        assertEquals(ServerPublicationState.PUBLIC, source(jdbc).find("pep")!!.state)
        assertEquals(1L, source(jdbc).find("pep")!!.revision)
    }

    @Test
    fun `max revision refuses overflow without reserving operation`() {
        val jdbc = fixture()
        jdbc.update("UPDATE game_server_publication SET revision=? WHERE server_id='pep'", Long.MAX_VALUE)
        assertFailsWith<ServerPublicationConflict> { writer(jdbc).verifying(close.copy(expectedRevision = Long.MAX_VALUE)) }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM game_server_publication_operation", Int::class.java))
        assertEquals(Long.MAX_VALUE, source(jdbc).find("pep")!!.revision)
    }

    @Test
    fun `competing CAS and global operation reservations have one winner`() {
        for (sameOperation in listOf(false, true)) {
            val jdbc = fixture()
            val writer = writer(jdbc)
            val ready = java.util.concurrent.CountDownLatch(2)
            val start = java.util.concurrent.CountDownLatch(1)
            val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val commands = if (sameOperation) listOf(close, close.copy(serverId = "uni")) else
                    listOf(close, close.copy(target = target.copy(operationId = "d".repeat(32))))
                val results = commands.map { command ->
                    executor.submit<Boolean> {
                        ready.countDown()
                        check(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        try {
                            writer.verifying(command)
                            true
                        } catch (_: ServerPublicationConflict) {
                            false
                        }
                    }
                }
                check(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
                start.countDown()
                assertEquals(1, results.count { it.get(10, java.util.concurrent.TimeUnit.SECONDS) })
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM game_server_publication_operation", Int::class.java))
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM game_server_publication WHERE state='VERIFYING'", Int::class.java))
            } finally {
                start.countDown()
                executor.shutdownNow()
                check(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `canonical reset update cannot pass receipt verification before publication commits`() {
        val jdbc = fixture()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val verifier = ServerPublicationReceiptVerifier { _, _, _ ->
                val competingUpdate = executor.submit<Boolean> {
                    requireNotNull(jdbc.dataSource).connection.use { connection ->
                        connection.createStatement().use { statement ->
                            statement.execute("SET LOCK_TIMEOUT 100")
                            try {
                                statement.executeUpdate("UPDATE game_server SET generation=1 WHERE server_id='pep'")
                                false
                            } catch (error: java.sql.SQLException) {
                                error.errorCode == 50200
                            }
                        }
                    }
                }
                kotlin.test.assertTrue(competingUpdate.get(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(0, jdbc.queryForObject("SELECT generation FROM game_server WHERE server_id='pep'", Int::class.java))
            }
            val writer = writer(jdbc, verifier)
            writer.verifying(close)
            assertEquals(3L, writer.publish(publish).revision)
            assertEquals(0, jdbc.queryForObject("SELECT generation FROM game_server WHERE server_id='pep'", Int::class.java))
            assertEquals(1, jdbc.update("UPDATE game_server SET generation=1 WHERE server_id='pep'"))
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    private fun source(jdbc: JdbcTemplate) = JdbcServerPublicationRepository(jdbc, ServerRegistry("", ObjectMapper(), jdbc))
    private fun writer(jdbc: JdbcTemplate, verifier: ServerPublicationReceiptVerifier = UnavailableServerPublicationReceiptVerifier()) =
        JdbcServerPublicationWriter(jdbc, source(jdbc), verifier)

    private fun fixture(): JdbcTemplate {
        val jdbc = JdbcTemplate(DriverManagerDataSource("jdbc:h2:mem:publication-writer-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
        jdbc.execute("CREATE TABLE game_server (sort_order BIGINT GENERATED ALWAYS AS IDENTITY, server_id VARCHAR(48) PRIMARY KEY, display_name TEXT NOT NULL, generation INTEGER, scenario_code TEXT, game_api_url TEXT, game_engine_url TEXT, deploy_project TEXT)")
        jdbc.execute("CREATE TABLE game_server_publication (server_id VARCHAR(48) PRIMARY KEY REFERENCES game_server(server_id) ON DELETE CASCADE, state VARCHAR(16) NOT NULL, revision BIGINT NOT NULL, operation_id VARCHAR(32) UNIQUE, expected_generation INTEGER, expected_scenario_code TEXT, target_fingerprint VARCHAR(64))")
        jdbc.execute("CREATE TABLE game_server_publication_operation (operation_id VARCHAR(32) PRIMARY KEY, server_id VARCHAR(48) NOT NULL, expected_generation INTEGER NOT NULL, expected_scenario_code TEXT NOT NULL, target_fingerprint VARCHAR(64) NOT NULL, expected_revision BIGINT NOT NULL, verifying_revision BIGINT NOT NULL, published_revision BIGINT, validation_receipt_sha256 VARCHAR(64))")
        jdbc.execute("CREATE TABLE game_server_registry_seed_state (id SMALLINT PRIMARY KEY, initialized BOOLEAN NOT NULL)")
        jdbc.update("INSERT INTO game_server_registry_seed_state VALUES (1, TRUE)")
        register(jdbc, "pep")
        register(jdbc, "uni")
        return jdbc
    }

    private fun register(jdbc: JdbcTemplate, id: String) {
        jdbc.update("INSERT INTO game_server (server_id, display_name, generation, game_api_url, game_engine_url, deploy_project) VALUES (?, ?, 0, ?, ?, ?)", id, id, "http://s$id-game-api:8081", "http://s$id-game-engine:8082", "opensamguk-s$id")
        jdbc.update("INSERT INTO game_server_publication (server_id, state, revision) VALUES (?, 'PUBLIC', 1)", id)
    }
}

package opensamguk.gateway.publication

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.publication.domain.*
import opensamguk.gateway.publication.infra.JdbcServerPublicationRepository
import opensamguk.gateway.publication.infra.JdbcServerPublicationWriter
import opensamguk.gateway.publication.infra.UnavailableServerPublicationReceiptVerifier
import opensamguk.gateway.service.ServerDef
import opensamguk.gateway.service.ServerRegistry
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ServerPublicationPostgresIT {
    @Test
    fun `actual migration backfills PUBLIC and rejects partial PostgreSQL constraints`() = fixture { f ->
        assertEquals(listOf("pep", "uni"), f.source.listPublicServers().map { it.id })
        assertEquals(0, f.source.listPublicServers().first().generation)
        assertEquals(1L, f.source.find("pep")?.revision)
        assertFailsWith<DataIntegrityViolationException> {
            f.jdbc.update("UPDATE game_server_publication SET revision=0 WHERE server_id='pep'")
        }
        assertFailsWith<DataIntegrityViolationException> {
            f.jdbc.update("UPDATE game_server_publication SET state='PRIVATE' WHERE server_id='pep'")
        }
        assertFailsWith<DataIntegrityViolationException> {
            f.jdbc.update("UPDATE game_server_publication SET state='VERIFYING' WHERE server_id='pep'")
        }
        val closed = f.writer.verifying(VerifyServerPublication("pep", 1, target("a")))
        assertEquals(ServerPublicationState.VERIFYING, closed.state)
        assertEquals(listOf("uni"), f.source.listPublicServers().map { it.id })
        assertFailsWith<DataIntegrityViolationException> {
            f.jdbc.update(
                "UPDATE game_server_publication_operation SET validation_receipt_sha256=? WHERE operation_id=?",
                "d".repeat(64), "a".repeat(32),
            )
        }
        assertFailsWith<DataIntegrityViolationException> {
            f.jdbc.update(
                "UPDATE game_server_publication_operation SET published_revision=3 WHERE operation_id=?",
                "a".repeat(32),
            )
        }
    }

    @Test
    fun `independent PostgreSQL transactions reserve a global operation exactly once`() = fixture { f ->
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf("pep", "uni").map { id ->
                pool.submit<Boolean> {
                    start.await()
                    try {
                        f.writer.verifying(VerifyServerPublication(id, 1, target("b")))
                        true
                    } catch (_: ServerPublicationConflict) {
                        false
                    }
                }
            }
            start.countDown()
            assertEquals(1, results.count { it.get(15, TimeUnit.SECONDS) })
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM game_server_publication_operation", Int::class.java))
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM game_server_publication WHERE state='VERIFYING'", Int::class.java))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `history survives membership cascade and missing source remains unavailable`() = fixture { f ->
        val target = target("c")
        f.writer.verifying(VerifyServerPublication("pep", 1, target))
        assertFailsWith<ServerPublicationSourceUnavailable> {
            f.writer.publish(PublishServerPublication("pep", 2, target.operationId, "d".repeat(64)))
        }
        assertEquals(ServerPublicationState.VERIFYING, f.source.find("pep")?.state)
        f.jdbc.update("DELETE FROM game_server WHERE server_id='pep'")
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM game_server_publication_operation", Int::class.java))
        f.registry.register(server("pep"))
        assertNotNull(f.source.find("pep"))
        assertEquals(ServerPublicationState.PUBLIC, f.source.find("pep")?.state)
        assertFailsWith<ServerPublicationConflict> {
            f.writer.verifying(VerifyServerPublication("pep", 1, target))
        }
        f.jdbc.update("DELETE FROM game_server_publication WHERE server_id='uni'")
        assertFailsWith<ServerPublicationSourceUnavailable> { f.source.listPublicServers() }
    }

    private fun target(character: String) =
        ServerPublicationTarget(character.repeat(32), 0, "scenario_3190", "e".repeat(64))

    private fun server(id: String) = ServerDef(
        id = id, name = id, gameApiUrl = "http://s$id-game-api:8081",
        gameEngineUrl = "http://s$id-game-engine:8082", deployProject = "opensamguk-s$id",
        generation = 0, scenarioCode = "scenario_3190",
    )

    private fun fixture(test: (Fixture) -> Unit) {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable - publication PostgreSQL IT skipped",
        )
        // Normal PR CI requires zero skipped IT. This fixture never runs locally
        // during the C8 Mac-focused gate, and does not touch a production database.
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()
            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            val jdbc = JdbcTemplate(dataSource)
            jdbc.execute(
                """CREATE TABLE game_server (
                    sort_order BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
                    server_id VARCHAR(48) PRIMARY KEY, display_name TEXT NOT NULL,
                    game_api_url TEXT NOT NULL, game_engine_url TEXT NOT NULL, deploy_project TEXT NOT NULL,
                    generation INTEGER, scenario_code TEXT
                )""".trimIndent(),
            )
            listOf("pep", "uni").forEach { id ->
                val s = server(id)
                jdbc.update(
                    """INSERT INTO game_server (server_id, display_name, game_api_url, game_engine_url,
                        deploy_project, generation, scenario_code) VALUES (?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                    s.id, s.name, s.gameApiUrl, s.gameEngineUrl, s.deployProject, s.generation, s.scenarioCode,
                )
            }
            val resources = PathMatchingResourcePatternResolver().getResources(
                "classpath*:db/migration/V*__game_server_publication.sql",
            )
            assertEquals(1, resources.size, "exact unapplied publication migration source required")
            dataSource.connection.use { ScriptUtils.executeSqlScript(it, resources.single()) }
            val executionResources = PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__game_server_d101_execution.sql")
            assertEquals(1, executionResources.size)
            dataSource.connection.use { ScriptUtils.executeSqlScript(it, executionResources.single()) }
            jdbc.execute("CREATE TABLE game_server_registry_seed_state (id SMALLINT PRIMARY KEY, initialized BOOLEAN NOT NULL)")
            jdbc.update("INSERT INTO game_server_registry_seed_state (id, initialized) VALUES (1, TRUE)")
            val registry = ServerRegistry("", ObjectMapper(), jdbc)
            val source = JdbcServerPublicationRepository(jdbc, registry)
            test(Fixture(jdbc, registry, source, JdbcServerPublicationWriter(jdbc, source, UnavailableServerPublicationReceiptVerifier())))
        }
    }

    private data class Fixture(
        val jdbc: JdbcTemplate,
        val registry: ServerRegistry,
        val source: JdbcServerPublicationRepository,
        val writer: JdbcServerPublicationWriter,
    )
}

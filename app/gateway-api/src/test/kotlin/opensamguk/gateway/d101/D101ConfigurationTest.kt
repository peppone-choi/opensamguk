package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.application.D101ExecutionService
import opensamguk.gateway.d101.domain.D101PurposeAuthorityUnavailable
import opensamguk.gateway.d101.infra.D101Configuration
import opensamguk.gateway.d101.security.D101PurposeAuthority
import opensamguk.gateway.publication.domain.ServerPublicationRepository
import opensamguk.gateway.publication.domain.ServerPublicationWriter
import opensamguk.gateway.publication.domain.ServerPublicationReceiptVerifier
import opensamguk.gateway.publication.infra.JdbcServerPublicationRepository
import opensamguk.gateway.publication.infra.JdbcServerPublicationWriter
import opensamguk.gateway.service.ServerRegistry
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Actual Spring wiring retains the unavailable boundary without reserving
 * operations or changing publication, even with an unrelated purpose bean. */
class D101ConfigurationTest {
    @Test
    fun `no provider keeps PREPARE unavailable`() = checkUnavailable(false)

    @Test
    fun `unrelated purpose bean cannot enable removed trust installation`() = checkUnavailable(true)

    private fun checkUnavailable(withPurpose: Boolean) {
        val f = D101Fixture()
        val now = Instant.now().epochSecond
        val tree = f.intentTree().apply {
            put("windowOpensAtUnix", now - 1)
            put("destructiveCutoffUnix", now + 3600)
            put("recoveryDeadlineUnix", now + 7200)
        }
        val request = f.request(tree = tree)
        val claims = f.claims(request).apply {
            put("issuedAtUnix", now)
            put("expiresAtUnix", now + 60)
        }
        val header = f.header(claims)
        val authorityReads = AtomicInteger()
        val authority = D101PurposeAuthority { sha ->
            authorityReads.incrementAndGet()
            f.authority(tree).readVerified(sha)
        }
        val jdbc = fixtureDatabase()
        val registry = ServerRegistry("", f.mapper, jdbc)
        val source = JdbcServerPublicationRepository(jdbc, registry)
        val writer = JdbcServerPublicationWriter(jdbc, source, ServerPublicationReceiptVerifier { _, _, _ ->
            error("PREPARE must not publish")
        })
        AnnotationConfigApplicationContext().use { context ->
            context.registerBean(ObjectMapper::class.java, Supplier { f.mapper })
            context.registerBean(JdbcTemplate::class.java, Supplier { jdbc })
            context.registerBean(ServerRegistry::class.java, Supplier { registry })
            context.registerBean(ServerPublicationRepository::class.java, Supplier { source })
            context.registerBean(ServerPublicationWriter::class.java, Supplier { writer })
            if (withPurpose) context.registerBean(D101PurposeAuthority::class.java, Supplier { authority })
            context.register(D101Configuration::class.java)
            context.refresh()
            val service = context.getBean(D101ExecutionService::class.java)
            assertFailsWith<D101PurposeAuthorityUnavailable> {
                service.prepare(f.operation, f.prepareBody(tree), listOf(header), 1)
            }
            assertEquals("PUBLIC", jdbc.queryForObject("SELECT state FROM game_server_publication", String::class.java))
            assertEquals(1L, jdbc.queryForObject("SELECT revision FROM game_server_publication", Long::class.java))
            for (table in listOf("game_server_d101_execution", "game_server_operation_reservation", "game_server_publication_operation", "game_server_registry_transition")) {
                assertEquals(0, count(jdbc, table))
            }
            assertEquals(0, authorityReads.get())
            assertEquals("old-name", registry.find("pep")!!.name)
        }
    }

    private fun count(jdbc: JdbcTemplate, table: String) = jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)

    private fun fixtureDatabase(): JdbcTemplate {
        val jdbc = JdbcTemplate(DriverManagerDataSource("jdbc:h2:mem:d101-config-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
        jdbc.execute("""CREATE TABLE game_server (sort_order BIGINT GENERATED ALWAYS AS IDENTITY, server_id VARCHAR(48) PRIMARY KEY,
            display_name TEXT NOT NULL, generation INTEGER, scenario_code TEXT, game_api_url TEXT, game_engine_url TEXT, deploy_project TEXT)""")
        jdbc.execute("""CREATE TABLE game_server_publication (server_id VARCHAR(48) PRIMARY KEY REFERENCES game_server(server_id) ON DELETE CASCADE,
            publicly_visible BOOLEAN NOT NULL DEFAULT TRUE, state VARCHAR(16) NOT NULL, revision BIGINT NOT NULL, operation_id VARCHAR(32) UNIQUE,
            expected_generation INTEGER, expected_scenario_code TEXT, target_fingerprint VARCHAR(64))""")
        jdbc.execute("""CREATE TABLE game_server_publication_operation (operation_id VARCHAR(32) PRIMARY KEY, server_id VARCHAR(48) NOT NULL,
            expected_generation INTEGER NOT NULL, expected_scenario_code TEXT NOT NULL, target_fingerprint VARCHAR(64) NOT NULL,
            expected_revision BIGINT NOT NULL, verifying_revision BIGINT NOT NULL, published_revision BIGINT, validation_receipt_sha256 VARCHAR(64))""")
        jdbc.execute("CREATE TABLE game_server_registry_seed_state (id SMALLINT PRIMARY KEY, initialized BOOLEAN NOT NULL)")
        jdbc.update("INSERT INTO game_server_registry_seed_state VALUES (1, TRUE)")
        jdbc.execute("""CREATE TABLE game_server_registry_transition (server_id VARCHAR(48) PRIMARY KEY, action VARCHAR(16) NOT NULL,
            display_name TEXT NOT NULL, game_api_url TEXT NOT NULL, game_engine_url TEXT NOT NULL, deploy_project TEXT NOT NULL,
            generation INTEGER, scenario_code TEXT, operation_id VARCHAR(32) UNIQUE NOT NULL, request_fingerprint VARCHAR(64) NOT NULL,
            dispatched BOOLEAN NOT NULL, remote_applied BOOLEAN NOT NULL, owner_token TEXT NOT NULL,
            lease_until TIMESTAMP WITH TIME ZONE NOT NULL, created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)""")
        D101TestSchema.install(jdbc)
        D101TestSchema.installPreResetOriginals(jdbc)
        jdbc.update("""INSERT INTO game_server (server_id,display_name,generation,scenario_code,game_api_url,game_engine_url,deploy_project)
            VALUES ('pep','old-name',9,'old','http://spep-game-api:8081','http://spep-game-engine:8082','opensamguk-spep')""")
        jdbc.update("INSERT INTO game_server_publication VALUES ('pep','PUBLIC',1,NULL,NULL,NULL,NULL)")
        return jdbc
    }
}

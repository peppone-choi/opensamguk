package opensamguk.gateway.d101

import opensamguk.gateway.d101.api.D101ExecutionController
import opensamguk.gateway.d101.application.D101ExecutionService
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.infra.JdbcD101ExecutionStore
import opensamguk.gateway.d101.security.*
import opensamguk.gateway.publication.domain.ServerPublicationReceiptVerifier
import opensamguk.gateway.publication.infra.JdbcServerPublicationRepository
import opensamguk.gateway.publication.infra.JdbcServerPublicationWriter
import opensamguk.gateway.security.InternalServiceTokenFilter
import opensamguk.gateway.service.ServerRegistry
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Calls the real terminal service and H2 store. The fake Root proof is only a source boundary fixture. */
class D101TerminalServiceTest {
    private val f = D101Fixture()
    private val resultBytes = "verified synthetic Root success".toByteArray()
    private val candidate = D101TerminalCandidate(2, D101Fixture.hash(resultBytes))
    private val terminalBody = f.mapper.writeValueAsBytes(linkedMapOf(
        "schemaVersion" to 1, "verifyingRevision" to "2", "rootResultReceiptSha256" to candidate.rootResultReceiptSha256,
    ))

    @Test
    fun `default unavailable terminal authority returns 503 without settlement writes`() {
        val db = fixture()
        terminalMvc(db).perform(terminalRequest())
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("OBSERVATION_UNAVAILABLE"))
        assertNotSettled(db)
    }

    @Test
    fun `throwing terminal authority returns 503 without settlement writes`() {
        val db = fixture()
        val calls = AtomicInteger()
        val failing = D101TerminalAuthority { _, _ ->
            calls.incrementAndGet()
            throw IllegalStateException("synthetic unavailable Root source")
        }
        terminalMvc(db, failing).perform(terminalRequest())
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("OBSERVATION_UNAVAILABLE"))
        assertEquals(1, calls.get())
        assertNotSettled(db)
    }

    @Test
    fun `verified source is read before writes then terminal settles and exact replay is immutable`() {
        val db = fixture()
        val reads = AtomicInteger()
        val source = D101TerminalAuthority { execution, request ->
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            val expected = if (reads.get() == 0) D101ExecutionState.DISPATCH_INTENT else D101ExecutionState.REGISTRY_SETTLED
            assertEquals(expected, db.store.query(f.operation)!!.state)
            if (reads.get() == 0) assertNull(db.store.query(f.operation)!!.rootResultReceiptSha256)
            reads.incrementAndGet()
            D101VerifiedTerminalEvidence(execution, request.rootResultReceiptSha256, resultBytes)
        }
        val mvc = terminalMvc(db, source)
        mvc.perform(terminalRequest()).andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("REGISTRY_SETTLED"))
            .andExpect(jsonPath("$.rootResultReceiptSha256").value(candidate.rootResultReceiptSha256))
        val first = db.store.query(f.operation)!!
        assertEquals(D101ExecutionState.REGISTRY_SETTLED, first.state)
        assertEquals("빼섭", db.registry.find("pep")!!.name)
        assertEquals(0, count(db, "game_server_registry_transition"))
        assertEquals(1, count(db, "game_server_d101_execution"))
        assertContentEquals(resultBytes, db.jdbc.queryForObject(
            "SELECT root_result_bytes FROM game_server_d101_execution WHERE operation_id=?", ByteArray::class.java, f.operation))

        mvc.perform(terminalRequest()).andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("REGISTRY_SETTLED"))
        val replay = db.store.query(f.operation)!!
        assertEquals(2, reads.get())
        assertEquals(first.updatedAt, replay.updatedAt)
        assertEquals(first.rootResultReceiptSha256, replay.rootResultReceiptSha256)
        assertEquals(0, count(db, "game_server_registry_transition"))
        assertEquals(1, count(db, "game_server_d101_execution"))
    }

    private fun assertNotSettled(db: Fixture) {
        val execution = db.store.query(f.operation)!!
        assertEquals(D101ExecutionState.DISPATCH_INTENT, execution.state)
        assertNull(execution.rootResultReceiptSha256)
        assertEquals("old-name", db.registry.find("pep")!!.name)
        assertEquals(1, count(db, "game_server_registry_transition"))
        assertEquals(1, count(db, "game_server_d101_execution"))
        assertEquals("VERIFYING", db.jdbc.queryForObject(
            "SELECT state FROM game_server_publication WHERE server_id='pep'", String::class.java))
    }

    private fun terminalMvc(db: Fixture, authority: D101TerminalAuthority = UnavailableD101TerminalAuthority()): MockMvc {
        val service = D101ExecutionService(f.json, f.requestCodec, f.verifier(), db.store,
            terminalAuthority = authority)
        return MockMvcBuilders.standaloneSetup(D101ExecutionController(service))
            .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(
                InternalServiceTokenFilter("d101-internal-fixture"))
            .build()
    }

    private fun terminalRequest(): org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder {
        val action = D101PurposeAction.SETTLE_REGISTRY
        val intent = f.intent()
        val request = D101PurposeRequest(action, f.operation, intent.targetFingerprint, intent.sha256,
            D101Fixture.hash(f.prepareBody()), 1, action.method, action.path(f.operation), terminalBody)
        return post(action.path(f.operation)).contentType(MediaType.APPLICATION_JSON).content(terminalBody)
            .header("Authorization", "Bearer d101-internal-fixture")
            .header("X-D101-Grant", f.header(f.claims(request)))
    }

    private fun grant(action: D101PurposeAction, body: ByteArray): D101VerifiedPurposeGrant {
        val intent = f.intent()
        val request = D101PurposeRequest(action, f.operation, intent.targetFingerprint, intent.sha256,
            D101Fixture.hash(f.prepareBody()), 1, action.method, action.path(f.operation), body)
        return f.verifier().verify(listOf(f.header(f.claims(request))), request)
    }

    private fun fixture(): Fixture {
        val jdbc = JdbcTemplate(DriverManagerDataSource(
            "jdbc:h2:mem:d101-terminal-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000", "sa", ""))
        jdbc.execute("""CREATE TABLE game_server (sort_order BIGINT GENERATED ALWAYS AS IDENTITY, server_id VARCHAR(48) PRIMARY KEY,
            display_name TEXT NOT NULL, generation INTEGER, scenario_code TEXT, game_api_url TEXT, game_engine_url TEXT, deploy_project TEXT)""")
        jdbc.execute("""CREATE TABLE game_server_publication (server_id VARCHAR(48) PRIMARY KEY REFERENCES game_server(server_id) ON DELETE CASCADE,
            state VARCHAR(16) NOT NULL, revision BIGINT NOT NULL, operation_id VARCHAR(32) UNIQUE,
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
        val registry = ServerRegistry("", f.mapper, jdbc)
        val publication = JdbcServerPublicationRepository(jdbc, registry)
        val writer = JdbcServerPublicationWriter(jdbc, publication, ServerPublicationReceiptVerifier { _, _, _ -> })
        val store = JdbcD101ExecutionStore(jdbc, publication, writer, registry, f.requestCodec)
        val prepare = f.prepareBody()
        val prepared = store.prepare(prepare, f.requestCodec.prepare(prepare), grant(D101PurposeAction.PREPARE, prepare)).execution
        val dispatch = D101DispatchIntentCandidate(2, "4".repeat(64), "5".repeat(64), "6".repeat(64))
        val dispatchBody = f.mapper.writeValueAsBytes(linkedMapOf(
            "schemaVersion" to 1, "verifyingRevision" to "2", "approvalPlanSha256" to dispatch.approvalPlanSha256,
            "executionReceiptSha256" to dispatch.executionReceiptSha256, "rootRequestFingerprint" to dispatch.rootRequestFingerprint,
        ))
        store.dispatch(dispatch, grant(D101PurposeAction.DISPATCH_INTENT, dispatchBody), D101VerifiedDispatch(
            f.operation, prepared.intent.sha256, prepared.gatewayPayloadSha256, prepared.intent.targetFingerprint, 1,
            dispatch, f.now, f.now + 30, f.clock))
        return Fixture(jdbc, registry, store)
    }

    private fun count(db: Fixture, table: String) = db.jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)

    private class Fixture(val jdbc: JdbcTemplate, val registry: ServerRegistry, val store: JdbcD101ExecutionStore)
}

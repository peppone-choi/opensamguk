package opensamguk.gateway.d101

import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.*
import opensamguk.gateway.d101.infra.JdbcD101ExecutionStore
import opensamguk.gateway.publication.domain.*
import opensamguk.gateway.publication.infra.JdbcServerPublicationRepository
import opensamguk.gateway.publication.infra.JdbcServerPublicationWriter
import opensamguk.gateway.service.ServerRegistry
import opensamguk.gateway.service.ServerRegistryTransitionConflict
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.*

/** Normal PR CI only; local C8 verification excludes this Docker fixture. */
class D101ExecutionStorePostgresIT {
    private val f = D101Fixture()

    @Test
    fun `actual PostgreSQL trigger failure rolls back parent CAS history and reservation`() = fixture { db ->
        db.jdbc.execute("""CREATE FUNCTION reject_d101_pending() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN RAISE EXCEPTION ''synthetic pending insert rejected''; END'""")
        db.jdbc.execute("CREATE TRIGGER reject_d101_pending BEFORE INSERT ON game_server_registry_transition FOR EACH ROW EXECUTE FUNCTION reject_d101_pending()")
        assertFailsWith<D101ObservationUnavailable> { prepare(db) }
        for (table in listOf("game_server_operation_reservation", "game_server_publication_operation", "game_server_d101_execution")) {
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java))
        }
        assertEquals("PUBLIC", db.jdbc.queryForObject("SELECT state FROM game_server_publication", String::class.java))
        assertEquals(1L, db.jdbc.queryForObject("SELECT revision FROM game_server_publication", Long::class.java))
        db.jdbc.execute("DROP TRIGGER reject_d101_pending ON game_server_registry_transition")
        val result = prepare(db)
        assertTrue(result.created)
        assertEquals(D101ExecutionState.PREPARED, result.execution.state)
        assertFalse(prepare(db).created)
        assertEquals("old-name", db.jdbc.queryForObject("SELECT display_name FROM game_server", String::class.java))
    }

    @Test
    fun `actual PostgreSQL stage constraints and membership-independent identity refuse reuse`() = fixture { db ->
        prepare(db)
        assertFailsWith<org.springframework.dao.DataIntegrityViolationException> {
            db.jdbc.update("UPDATE game_server_d101_execution SET state='DISPATCH_INTENT', last_safe_state='DISPATCH_INTENT'")
        }
        assertFailsWith<org.springframework.dao.DataIntegrityViolationException> {
            db.jdbc.update("UPDATE game_server_d101_execution SET intent_bytes=?", ByteArray(32769))
        }
        assertEquals(D101ExecutionState.PREPARED, db.store.query(f.operation)!!.state)
        db.jdbc.update("DELETE FROM game_server WHERE server_id='pep'")
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM game_server_operation_reservation", Int::class.java))
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM game_server_d101_execution", Int::class.java))
        assertEquals(D101ExecutionState.PREPARED, db.store.query(f.operation)!!.state)
        assertFailsWith<ServerRegistryTransitionConflict> {
            db.registry.register(opensamguk.gateway.service.ServerDef("pep","replacement","http://spep-game-api:8081","http://spep-game-engine:8082","opensamguk-spep",9,"old"))
        }
        assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM game_server", Int::class.java))
        assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM game_server_publication", Int::class.java))
        assertEquals(D101ExecutionState.PREPARED, db.store.query(f.operation)!!.state)
        // Restore only the synthetic membership fixture after its cascading
        // delete. An ordinary register must not bypass the active D101 fence.
        db.jdbc.update("""INSERT INTO game_server (server_id,display_name,game_api_url,game_engine_url,deploy_project,generation,scenario_code)
            VALUES ('pep','replacement','http://spep-game-api:8081','http://spep-game-engine:8082','opensamguk-spep',9,'old')""")
        db.jdbc.update("INSERT INTO game_server_publication (server_id,state,revision) VALUES ('pep','PUBLIC',1)")
        assertFailsWith<ServerPublicationConflict> {
            db.writer.verifying(VerifyServerPublication("pep",1,ServerPublicationTarget(f.operation,0,"scenario_3190",f.intent().targetFingerprint)))
        }
        assertEquals("PUBLIC", db.jdbc.queryForObject("SELECT state FROM game_server_publication", String::class.java))
    }

    @Test
    fun `actual PostgreSQL canonical trigger failure retains physical success then settles without redispatch`() = fixture { db ->
        val execution = prepare(db).execution
        val dispatch = D101DispatchIntentCandidate(2, "4".repeat(64), "5".repeat(64), "6".repeat(64))
        val dispatchBody = f.mapper.writeValueAsBytes(linkedMapOf(
            "schemaVersion" to 1, "verifyingRevision" to "2", "approvalPlanSha256" to dispatch.approvalPlanSha256,
            "executionReceiptSha256" to dispatch.executionReceiptSha256, "rootRequestFingerprint" to dispatch.rootRequestFingerprint,
        ))
        val dispatched = db.store.dispatch(dispatch, purpose(D101PurposeAction.DISPATCH_INTENT, dispatchBody), D101VerifiedDispatch(
            f.operation, execution.intent.sha256, execution.gatewayPayloadSha256, execution.intent.targetFingerprint, 1,
            dispatch, f.now, f.now + 30, f.clock,
        )).execution
        val resultBytes = "verified synthetic Root success".toByteArray()
        val candidate = D101TerminalCandidate(2, D101Fixture.hash(resultBytes))
        val terminalBody = f.mapper.writeValueAsBytes(linkedMapOf(
            "schemaVersion" to 1, "verifyingRevision" to "2", "rootResultReceiptSha256" to candidate.rootResultReceiptSha256,
        ))
        db.store.remoteSucceeded(candidate, purpose(D101PurposeAction.SETTLE_REGISTRY, terminalBody),
            D101VerifiedTerminalEvidence(dispatched, candidate.rootResultReceiptSha256, resultBytes))
        db.jdbc.execute("""CREATE FUNCTION reject_d101_canonical() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN RAISE EXCEPTION ''synthetic canonical update rejected''; END'""")
        db.jdbc.execute("CREATE TRIGGER reject_d101_canonical BEFORE UPDATE ON game_server FOR EACH ROW EXECUTE FUNCTION reject_d101_canonical()")
        assertFailsWith<D101ObservationUnavailable> {
            db.store.settleRegistry(candidate, purpose(D101PurposeAction.SETTLE_REGISTRY, terminalBody))
        }
        assertEquals(D101ExecutionState.REMOTE_SUCCEEDED, db.store.query(f.operation)!!.state)
        assertEquals(candidate.rootResultReceiptSha256, db.store.query(f.operation)!!.rootResultReceiptSha256)
        assertEquals("old-name", db.registry.find("pep")!!.name)
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM game_server_registry_transition", Int::class.java))
        assertEquals("VERIFYING", db.jdbc.queryForObject("SELECT state FROM game_server_publication", String::class.java))
        db.jdbc.execute("DROP TRIGGER reject_d101_canonical ON game_server")
        val settled = db.store.settleRegistry(candidate, purpose(D101PurposeAction.SETTLE_REGISTRY, terminalBody))
        assertEquals(D101ExecutionState.REGISTRY_SETTLED, settled.execution.state)
        assertEquals("빼섭", db.registry.find("pep")!!.name)
        assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM game_server_registry_transition", Int::class.java))
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM game_server_d101_execution", Int::class.java))
        assertEquals("VERIFYING", db.jdbc.queryForObject("SELECT state FROM game_server_publication", String::class.java))
        assertEquals(2L, db.jdbc.queryForObject("SELECT revision FROM game_server_publication", Long::class.java))
        assertFalse(db.store.settleRegistry(candidate, purpose(D101PurposeAction.SETTLE_REGISTRY, terminalBody)).created)
    }

    private fun purpose(action: D101PurposeAction, body: ByteArray): D101VerifiedPurposeGrant {
        val intent = f.intent()
        val request = D101PurposeRequest(action, f.operation, intent.targetFingerprint, intent.sha256,
            D101Fixture.hash(f.prepareBody()), 1, action.method, action.path(f.operation), body)
        return f.verifier().verify(listOf(f.header(f.claims(request))), request)
    }

    private fun prepare(db: Db): D101ExecutionWrite {
        val body = f.prepareBody()
        val request = f.request()
        val grant = f.verifier().verify(listOf(f.header(f.claims(request))), request)
        return db.store.prepare(body, f.requestCodec.prepare(body), grant)
    }

    private fun fixture(test: (Db) -> Unit) {
        assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable - D101 PostgreSQL IT skipped")
        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()
            val ds = DriverManagerDataSource(pg.jdbcUrl, pg.username, pg.password)
            val jdbc = JdbcTemplate(ds)
            jdbc.execute("""CREATE TABLE game_server (sort_order BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
                server_id VARCHAR(48) PRIMARY KEY, display_name TEXT NOT NULL, game_api_url TEXT NOT NULL,
                game_engine_url TEXT NOT NULL, deploy_project TEXT NOT NULL, generation INTEGER, scenario_code TEXT)""")
            jdbc.update("""INSERT INTO game_server (server_id,display_name,game_api_url,game_engine_url,deploy_project,generation,scenario_code)
                VALUES ('pep','old-name','http://spep-game-api:8081','http://spep-game-engine:8082','opensamguk-spep',9,'old')""")
            for (pattern in listOf("classpath*:db/migration/V*__game_server_publication.sql", "classpath*:db/migration/V76__server_operator_visibility.sql", "classpath*:db/migration/V*__game_server_d101_execution.sql", "classpath*:db/pending/d101_pre_reset_originals.sql")) {
                val resources = PathMatchingResourcePatternResolver().getResources(pattern)
                assertEquals(1, resources.size)
                ds.connection.use { ScriptUtils.executeSqlScript(it, resources.single()) }
            }
            jdbc.execute("CREATE TABLE game_server_registry_seed_state (id SMALLINT PRIMARY KEY, initialized BOOLEAN NOT NULL)")
            jdbc.update("INSERT INTO game_server_registry_seed_state VALUES (1,TRUE)")
            jdbc.execute("""CREATE TABLE game_server_registry_transition (
                server_id VARCHAR(48) PRIMARY KEY, action VARCHAR(16) NOT NULL,
                display_name TEXT NOT NULL, game_api_url TEXT NOT NULL, game_engine_url TEXT NOT NULL, deploy_project TEXT NOT NULL,
                generation INTEGER, scenario_code TEXT, operation_id VARCHAR(32) UNIQUE NOT NULL, request_fingerprint VARCHAR(64) NOT NULL,
                dispatched BOOLEAN NOT NULL, remote_applied BOOLEAN NOT NULL, owner_token TEXT NOT NULL,
                lease_until TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP)""")
            val registry = ServerRegistry("", f.mapper, jdbc)
            val source = JdbcServerPublicationRepository(jdbc, registry)
            val writer = JdbcServerPublicationWriter(jdbc, source, ServerPublicationReceiptVerifier { _, _, _ -> error("not final publication proof") })
            test(Db(jdbc,registry,writer,JdbcD101ExecutionStore(jdbc,source,writer,registry,f.requestCodec)))
        }
    }

    private data class Db(val jdbc: JdbcTemplate, val registry: ServerRegistry, val writer: JdbcServerPublicationWriter, val store: JdbcD101ExecutionStore)
}

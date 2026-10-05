package opensamguk.gateway.d101

import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.infra.JdbcD101ExecutionStore
import opensamguk.gateway.d101.infra.JdbcD101RecoveryStore
import opensamguk.gateway.d101.security.*
import opensamguk.gateway.publication.domain.ServerPublicationReceiptVerifier
import opensamguk.gateway.publication.infra.JdbcServerPublicationRepository
import opensamguk.gateway.publication.infra.JdbcServerPublicationWriter
import opensamguk.gateway.service.ServerRegistry
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID
import kotlin.test.*

/** Synthetic verified projections exercise the Gateway transaction boundary only. */
class D101RecoveryStoreTest {
    private val f = D101Fixture()
    private val codec = D101RecoveryRequestCodec(f.json)

    @Test
    fun `prephysical result enters same operation and exact begin replay preserves null terminal result`() {
        val db = fixture()
        val dispatched = prepareDispatch(db)
        val root = """{"status":"FAILED"}""".toByteArray()
        val rootSha = D101Fixture.hash(root)
        val body = beginBody(rootSha)
        val result = db.recovery().begin(body, codec.begin(body), grant(D101PurposeAction.RECOVERY_BEGIN, body),
            D101VerifiedRecoveryBegin(dispatched, rootSha, "FAILED", root))
        assertTrue(result.created)
        assertEquals(D101ExecutionState.RECOVERY_REQUIRED, result.execution.state)
        assertEquals(D101ExecutionState.DISPATCH_INTENT, result.execution.lastSafeState)
        assertNull(result.execution.rootResultReceiptSha256)
        assertEquals("D101_RESET", db.jdbc.queryForObject("SELECT kind FROM game_server_operation_reservation", String::class.java))
        assertEquals(rootSha, db.jdbc.queryForObject("SELECT root_result_sha FROM game_server_d101_recovery", String::class.java))

        val replay = db.recovery().begin(body, codec.begin(body), grant(D101PurposeAction.RECOVERY_BEGIN, body), null)
        assertFalse(replay.created)
        assertEquals(result.beginReceiptSha256, replay.beginReceiptSha256)
        val differentWire = body + byteArrayOf(0x20)
        assertFailsWith<D101OperationConflict> {
            db.recovery().begin(differentWire, codec.begin(differentWire),
                grant(D101PurposeAction.RECOVERY_BEGIN, differentWire), null)
        }
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM game_server_d101_recovery", Int::class.java))
    }

    @Test
    fun `close proof must bind immutable begin original while missing old canonical contract keeps fence`() {
        val db = fixture()
        val dispatched = prepareDispatch(db)
        val root = """{"status":"RECOVERY_REQUIRED"}""".toByteArray()
        val rootSha = D101Fixture.hash(root)
        val beginBody = beginBody(rootSha)
        val begun = db.recovery().begin(beginBody, codec.begin(beginBody), grant(D101PurposeAction.RECOVERY_BEGIN, beginBody),
            D101VerifiedRecoveryBegin(dispatched, rootSha, "RECOVERY_REQUIRED", root))
        val restored = """{"status":"RECOVERED"}""".toByteArray()
        val resultSha = D101Fixture.hash(restored)
        val closeBody = closeBody(begun.beginReceiptSha256, resultSha)
        val closeCandidate = codec.close(closeBody)
        val exact = closeProof(begun.execution, begun.beginReceiptSha256, rootSha, restored)
        assertFailsWith<D101ObservationUnavailable> {
            db.recovery().validateCloseEvidence(closeBody, closeCandidate, grant(D101PurposeAction.RECOVERY_CLOSE, closeBody), exact)
        }
        assertEquals(D101ExecutionState.RECOVERY_REQUIRED, db.store().query(f.operation)?.state)
        assertNull(db.store().query(f.operation)?.rootResultReceiptSha256)

        val wrong = closeProof(begun.execution, begun.beginReceiptSha256, "f".repeat(64), restored)
        assertFailsWith<D101OperationConflict> {
            db.recovery().validateCloseEvidence(closeBody, closeCandidate, grant(D101PurposeAction.RECOVERY_CLOSE, closeBody), wrong)
        }
    }

    @Test
    fun `already committed success requires the same original Root hash and bytes`() {
        val db = fixture()
        prepareDispatch(db)
        val root = """{"status":"SUCCEEDED"}""".toByteArray()
        val rootSha = D101Fixture.hash(root)
        db.jdbc.update("""UPDATE game_server_d101_execution SET state='REMOTE_SUCCEEDED', last_safe_state='REMOTE_SUCCEEDED',
            root_result_sha=?, root_result_bytes=? WHERE operation_id=?""", rootSha, root, f.operation)
        val succeeded = requireNotNull(db.store().query(f.operation))
        val beginBody = beginBody(rootSha)
        val begun = db.recovery().begin(beginBody, codec.begin(beginBody), grant(D101PurposeAction.RECOVERY_BEGIN, beginBody),
            D101VerifiedRecoveryBegin(succeeded, rootSha, "SUCCEEDED", root))
        val restored = """{"status":"RECOVERED"}""".toByteArray()
        val closeBody = closeBody(begun.beginReceiptSha256, D101Fixture.hash(restored))
        val wrong = closeProof(begun.execution, begun.beginReceiptSha256, "f".repeat(64), restored)
        assertFailsWith<D101OperationConflict> {
            db.recovery().validateCloseEvidence(closeBody, codec.close(closeBody),
                grant(D101PurposeAction.RECOVERY_CLOSE, closeBody), wrong)
        }
        assertEquals(rootSha, db.store().query(f.operation)?.rootResultReceiptSha256)
        assertEquals(D101ExecutionState.RECOVERY_REQUIRED, db.store().query(f.operation)?.state)
    }

    private fun beginBody(rootSha: String) = f.mapper.writeValueAsBytes(linkedMapOf(
        "schemaVersion" to 1, "verifyingRevision" to "2", "rootResultReceiptSha256" to rootSha,
    ))

    private fun closeBody(beginSha: String, resultSha: String) = f.mapper.writeValueAsBytes(linkedMapOf(
        "schemaVersion" to 1, "verifyingRevision" to "2", "recoveryBeginReceiptSha256" to beginSha,
        "recoveryResultReceiptSha256" to resultSha,
    ))

    private fun closeProof(execution: D101Execution, beginSha: String, rootSha: String, bytes: ByteArray) =
        D101VerifiedRecoveryClose(execution, beginSha, D101Fixture.hash(bytes), rootSha, "d".repeat(64),
            9, "scenario_180", execution.intent.oldImageDigests, "e".repeat(64), "1".repeat(64), "2".repeat(64), bytes)

    private fun prepareDispatch(db: Fixture): D101Execution {
        val prepare = f.prepareBody()
        val prepared = db.store().prepare(prepare, f.requestCodec.prepare(prepare), grant(D101PurposeAction.PREPARE, prepare)).execution
        val candidate = D101DispatchIntentCandidate(2, "4".repeat(64), "5".repeat(64), "6".repeat(64))
        val body = f.mapper.writeValueAsBytes(linkedMapOf(
            "schemaVersion" to 1, "verifyingRevision" to "2", "approvalPlanSha256" to candidate.approvalPlanSha256,
            "executionReceiptSha256" to candidate.executionReceiptSha256,
            "rootRequestFingerprint" to candidate.rootRequestFingerprint,
        ))
        val proof = D101VerifiedDispatch(prepared.intent.operationId, prepared.intent.sha256, prepared.gatewayPayloadSha256,
            prepared.intent.targetFingerprint, prepared.intent.initialPublicRevision, candidate, f.now, f.now + 30, f.clock)
        return db.store().dispatch(candidate, grant(D101PurposeAction.DISPATCH_INTENT, body), proof).execution
    }

    private fun grant(action: D101PurposeAction, body: ByteArray): D101VerifiedPurposeGrant {
        val intent = f.intent()
        val request = D101PurposeRequest(action, f.operation, intent.targetFingerprint, intent.sha256,
            D101Fixture.hash(f.prepareBody()), 1, action.method, action.path(f.operation), body)
        return f.verifier().verify(listOf(f.header(f.claims(request))), request)
    }

    private fun fixture(): Fixture {
        val jdbc = JdbcTemplate(DriverManagerDataSource(
            "jdbc:h2:mem:d101-recovery-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000", "sa", "",
        ))
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
        jdbc.update("""INSERT INTO game_server (server_id,display_name,generation,scenario_code,game_api_url,game_engine_url,deploy_project)
            VALUES ('pep','old-name',9,'scenario_180','http://spep-game-api:8081','http://spep-game-engine:8082','opensamguk-spep')""")
        jdbc.update("INSERT INTO game_server_publication VALUES ('pep','PUBLIC',1,NULL,NULL,NULL,NULL)")
        return Fixture(jdbc)
    }

    private inner class Fixture(val jdbc: JdbcTemplate) {
        val registry = ServerRegistry("", f.mapper, jdbc)
        val source = JdbcServerPublicationRepository(jdbc, registry)
        val writer = JdbcServerPublicationWriter(jdbc, source, ServerPublicationReceiptVerifier { _, _, _ -> })
        fun store() = JdbcD101ExecutionStore(jdbc, source, writer, registry, f.requestCodec)
        fun recovery() = JdbcD101RecoveryStore(jdbc, source, registry, store(), codec)
    }
}

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
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/** Synthetic verified originals test the locked App close transaction, not Root custody or real PG. */
class D101RecoveryNullableStoreTest {
    private val f = D101Fixture()
    private val codec = D101RecoveryRequestCodec(f.json)
    private val actualGeneration = 9
    private val actualScenario = "scenario_180"

    @Test
    fun `pending null canonical closes with original nulls and same operation replays`() {
        val db = fixture(null, null)
        val (begun, rootSha) = begin(db, prepareDispatch(db), "RECOVERY_REQUIRED")
        val close = closeInput(begun, rootSha, null, null)
        val written = db.recovery().close(close.body, close.candidate, grant(D101PurposeAction.RECOVERY_CLOSE, close.body), close.verified)
        assertTrue(written.created)
        assertEquals(D101ExecutionState.RECOVERED, written.execution.state)
        assertNull(db.scalar("SELECT generation FROM game_server WHERE server_id='pep'"))
        assertNull(db.scalar("SELECT scenario_code FROM game_server WHERE server_id='pep'"))
        assertNull(db.scalar("SELECT old_generation FROM game_server_d101_recovery WHERE operation_id='${f.operation}'"))
        assertNull(db.scalar("SELECT old_scenario_code FROM game_server_d101_recovery WHERE operation_id='${f.operation}'"))
        val replay = db.recovery().close(close.body, close.candidate, grant(D101PurposeAction.RECOVERY_CLOSE, close.body), null)
        assertFalse(replay.created)
        assertEquals(written.recoveryResultReceiptSha256, replay.recoveryResultReceiptSha256)
        assertEquals(0, db.count("SELECT COUNT(*) FROM game_server_registry_transition"))
    }

    @Test
    fun `settled partial null canonical restores each nullable field and replays`() {
        for ((oldGeneration, oldScenario) in listOf(9 to null, null to actualScenario)) {
            val db = fixture(oldGeneration, oldScenario)
            val dispatched = prepareDispatch(db)
            val root = """{"status":"SUCCEEDED"}""".toByteArray()
            val rootSha = D101Fixture.hash(root)
            db.jdbc.update("""UPDATE game_server_d101_execution SET state='REMOTE_SUCCEEDED',
                last_safe_state='REMOTE_SUCCEEDED', root_result_sha=?, root_result_bytes=? WHERE operation_id=?""",
                rootSha, root, f.operation)
            val terminal = D101TerminalCandidate(2, rootSha)
            val terminalBody = f.mapper.writeValueAsBytes(linkedMapOf(
                "schemaVersion" to 1, "verifyingRevision" to "2", "rootResultReceiptSha256" to rootSha,
            ))
            val settled = db.store().settleRegistry(terminal, grant(D101PurposeAction.SETTLE_REGISTRY, terminalBody)).execution
            val (begun, _) = begin(db, settled, "SUCCEEDED", root)
            val close = closeInput(begun, rootSha, oldGeneration, oldScenario)
            assertTrue(db.recovery().close(close.body, close.candidate,
                grant(D101PurposeAction.RECOVERY_CLOSE, close.body), close.verified).created)
            assertEquals(oldGeneration, db.scalar("SELECT generation FROM game_server WHERE server_id='pep'")?.toInt())
            assertEquals(oldScenario, db.scalar("SELECT scenario_code FROM game_server WHERE server_id='pep'"))
            assertFalse(db.recovery().close(close.body, close.candidate,
                grant(D101PurposeAction.RECOVERY_CLOSE, close.body), null).created)
        }
    }

    @Test
    fun `nonnull canonical mismatch is rejected before any close write`() {
        val db = fixture(8, actualScenario)
        val (begun, rootSha) = begin(db, prepareDispatch(db), "RECOVERY_REQUIRED")
        assertFailsWith<D101ObservationUnavailable> { closeInput(begun, rootSha, 8, actualScenario) }
        assertNull(db.scalar("SELECT close_request_sha FROM game_server_d101_recovery WHERE operation_id='${f.operation}'"))
        assertEquals(D101ExecutionState.RECOVERY_REQUIRED, db.store().query(f.operation)?.state)
        assertEquals(1, db.count("SELECT COUNT(*) FROM game_server_registry_transition"))
    }

    @Test
    fun `locked parent and transition owner forgery deny without close write`() {
        for (tamper in listOf("parent", "owner")) {
            val db = fixture(null, null)
            val (begun, rootSha) = begin(db, prepareDispatch(db), "RECOVERY_REQUIRED")
            val close = closeInput(begun, rootSha, null, null)
            if (tamper == "parent") db.jdbc.update("UPDATE game_server SET display_name='forged' WHERE server_id='pep'")
            else db.jdbc.update("UPDATE game_server_registry_transition SET owner_token='forged' WHERE server_id='pep'")
            assertFailsWith<D101OperationConflict> {
                db.recovery().close(close.body, close.candidate, grant(D101PurposeAction.RECOVERY_CLOSE, close.body), close.verified)
            }
            assertNull(db.scalar("SELECT close_request_sha FROM game_server_d101_recovery WHERE operation_id='${f.operation}'"))
            assertEquals(D101ExecutionState.RECOVERY_REQUIRED, db.store().query(f.operation)?.state)
            assertEquals(1, db.count("SELECT COUNT(*) FROM game_server_registry_transition"))
        }
    }

    private data class Begun(val execution: D101Execution, val receiptSha: String)
    private data class CloseInput(val body: ByteArray, val candidate: D101RecoveryCloseCandidate,
        val verified: D101VerifiedRecoveryClose)

    private fun begin(db: Fixture, execution: D101Execution, status: String, root: ByteArray =
        """{"status":"RECOVERY_REQUIRED"}""".toByteArray()): Pair<Begun, String> {
        val rootSha = D101Fixture.hash(root)
        val body = f.mapper.writeValueAsBytes(linkedMapOf(
            "schemaVersion" to 1, "verifyingRevision" to "2", "rootResultReceiptSha256" to rootSha,
        ))
        val written = db.recovery().begin(body, codec.begin(body), grant(D101PurposeAction.RECOVERY_BEGIN, body),
            D101VerifiedRecoveryBegin(execution, rootSha, status, root))
        return Begun(written.execution, written.beginReceiptSha256) to rootSha
    }

    private fun closeInput(begun: Begun, rootSha: String, canonicalGeneration: Int?, canonicalScenario: String?): CloseInput {
        val execution = begun.execution
        val result = """{"status":"RECOVERED"}""".toByteArray()
        val resultSha = D101Fixture.hash(result)
        val body = f.mapper.writeValueAsBytes(linkedMapOf(
            "schemaVersion" to 1, "verifyingRevision" to "2", "recoveryBeginReceiptSha256" to begun.receiptSha,
            "recoveryResultReceiptSha256" to resultSha,
        ))
        val registry = f.mapper.writeValueAsBytes(linkedMapOf(
            "id" to "pep", "name" to "old-name", "gameApiUrl" to "http://spep-game-api:8081",
            "gameEngineUrl" to "http://spep-game-engine:8082", "deployProject" to "opensamguk-spep",
            "generation" to canonicalGeneration, "scenarioCode" to canonicalScenario,
        ))
        val databaseSha = "3".repeat(64)
        val runtimeSha = "4".repeat(64)
        val world = f.mapper.writeValueAsBytes(linkedMapOf(
            "schemaVersion" to 1, "kind" to "D101_RESTORED_OLD_WORLD_V1", "operationId" to f.operation,
            "approvalIntentSha256" to execution.intent.sha256, "targetFingerprint" to execution.intent.targetFingerprint,
            "verifyingRevision" to "2", "recoveryBeginReceiptSha256" to begun.receiptSha, "worldId" to 1,
            "generation" to actualGeneration, "scenarioCode" to actualScenario, "tickSeconds" to 60,
            "oldImageDigests" to execution.intent.oldImageDigests,
            "databaseReceiptSha256" to databaseSha, "runtimeReceiptSha256" to runtimeSha,
            "observedAtUtc" to "2026-10-06T09:00:02Z",
        ))
        val registrySha = D101Fixture.hash(registry)
        val worldSha = D101Fixture.hash(world)
        val snapshots = D101RecoverySnapshots.decode(f.json, execution, begun.receiptSha, registry, registrySha,
            world, worldSha, actualGeneration, actualScenario, databaseSha, runtimeSha,
            Instant.parse("2026-10-06T09:00:01Z"), Instant.parse("2026-10-06T09:00:03Z"))
        val verified = D101VerifiedRecoveryClose(execution, begun.receiptSha, resultSha, rootSha, "d".repeat(64),
            actualGeneration, actualScenario, execution.intent.oldImageDigests, registrySha, "1".repeat(64),
            worldSha, snapshots, result)
        return CloseInput(body, codec.close(body), verified)
    }

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

    private fun fixture(oldGeneration: Int?, oldScenario: String?): Fixture {
        val jdbc = JdbcTemplate(DriverManagerDataSource(
            "jdbc:h2:mem:d101-nullable-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000", "sa", "",
        ))
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
            VALUES ('pep','old-name',?,?,'http://spep-game-api:8081','http://spep-game-engine:8082','opensamguk-spep')""",
            oldGeneration, oldScenario)
        jdbc.update("INSERT INTO game_server_publication VALUES ('pep','PUBLIC',1,NULL,NULL,NULL,NULL)")
        return Fixture(jdbc)
    }

    private inner class Fixture(val jdbc: JdbcTemplate) {
        val registry = ServerRegistry("", f.mapper, jdbc)
        val source = JdbcServerPublicationRepository(jdbc, registry)
        val writer = JdbcServerPublicationWriter(jdbc, source, ServerPublicationReceiptVerifier { _, _, _ -> })
        fun store() = JdbcD101ExecutionStore(jdbc, source, writer, registry, f.requestCodec)
        fun recovery() = JdbcD101RecoveryStore(jdbc, source, registry, store(), codec, f.json)
        fun scalar(sql: String): String? = jdbc.query(sql, { rs, _ -> rs.getString(1) }).singleOrNull()
        fun count(sql: String): Int = jdbc.queryForObject(sql, Int::class.java)!!
    }
}

package opensamguk.gateway.d101

import opensamguk.gateway.d101.application.D101ExecutionService
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.infra.JdbcD101ExecutionStore
import opensamguk.gateway.d101.infra.JdbcD101PreResetOriginalsStore
import opensamguk.gateway.d101.security.*
import opensamguk.gateway.publication.domain.*
import opensamguk.gateway.publication.infra.JdbcServerPublicationRepository
import opensamguk.gateway.publication.infra.JdbcServerPublicationWriter
import opensamguk.gateway.service.ServerRegistry
import opensamguk.gateway.service.ServerRegistryTransitionConflict
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID
import kotlin.test.*

class D101ExecutionStoreTest {
    private val f = D101Fixture()

    @Test
    fun `prepare commits closure pending reset and global identity together then survives restart`() {
        val db = fixture()
        val prepared = prepare(db)
        assertTrue(prepared.created)
        assertEquals(D101ExecutionState.PREPARED, prepared.execution.state)
        assertEquals(2L, prepared.execution.verifyingRevision)
        assertEquals("old-name", db.jdbc.queryForObject("SELECT display_name FROM game_server WHERE server_id='pep'", String::class.java))
        val pending = db.jdbc.queryForMap("SELECT display_name, generation, scenario_code, game_api_url, deploy_project FROM game_server_registry_transition")
        assertEquals("빼섭", pending["display_name"])
        assertEquals(0, pending["generation"])
        assertEquals("scenario_3190", pending["scenario_code"])
        assertEquals("http://spep-game-api:8081", pending["game_api_url"])
        assertEquals("opensamguk-spep", pending["deploy_project"])
        assertEquals("D101_RESET", db.jdbc.queryForObject("SELECT kind FROM game_server_operation_reservation", String::class.java))
        val captured = JdbcD101PreResetOriginalsStore(db.jdbc, f.mapper).readForQuery(prepared.execution)
        val old = f.mapper.readTree(captured.originalBytes())
        assertEquals("PUBLIC", old["oldPublication"]["state"].asText())
        assertEquals("1", old["oldPublication"]["revision"].asText())
        assertEquals(9, old["oldRegistry"]["generation"].asInt())
        assertEquals("old", old["oldRegistry"]["scenarioCode"].asText())
        assertEquals("old-name", old["oldRegistry"]["name"].asText())
        assertEquals("VERIFYING", db.jdbc.queryForObject("SELECT state FROM game_server_publication", String::class.java))
        val restarted = db.store()
        val wire = f.prepareBody()
        val replay = restarted.prepare(wire, f.requestCodec.prepare(wire), grant(D101PurposeAction.PREPARE, wire))
        assertFalse(replay.created)
        assertContentEquals(captured.originalBytes(), JdbcD101PreResetOriginalsStore(db.jdbc, f.mapper).readForQuery(replay.execution).originalBytes())
        assertEquals(1, count(db, "game_server_d101_pre_reset_originals"))
        assertEquals(prepared.execution.createdAt, replay.execution.createdAt)
        assertEquals(1, count(db, "game_server_d101_execution"))
        assertEquals(1, count(db, "game_server_publication_operation"))
        assertContentEquals(wire, restarted.query(f.operation)!!.preparePayload())
    }

    @Test
    fun `pending registry failure rolls back CAS execution and common reservation`() {
        val db = fixture()
        db.jdbc.update("""INSERT INTO game_server_registry_transition
            (server_id, action, display_name, game_api_url, game_engine_url, deploy_project, generation, scenario_code,
             operation_id, request_fingerprint, dispatched, remote_applied, owner_token, lease_until)
            VALUES ('pep','CLOSE','old-name','http://spep-game-api:8081','http://spep-game-engine:8082','opensamguk-spep',9,'old',
                ?,?,FALSE,FALSE,'ordinary',CURRENT_TIMESTAMP)""", "e".repeat(32), "f".repeat(64))
        assertFailsWith<D101OperationConflict> { prepare(db) }
        assertEquals("PUBLIC", db.jdbc.queryForObject("SELECT state FROM game_server_publication", String::class.java))
        assertEquals(1L, db.jdbc.queryForObject("SELECT revision FROM game_server_publication", Long::class.java))
        for (table in listOf("game_server_operation_reservation","game_server_publication_operation","game_server_d101_execution","game_server_d101_pre_reset_originals")) {
            assertEquals(0, count(db, table))
        }
        assertEquals(1, count(db, "game_server_registry_transition"))
    }

    @Test
    fun `missing capture table rolls back prepare and absent replay cannot invent old originals`() {
        val fresh = fixture()
        fresh.jdbc.execute("DROP TABLE game_server_d101_pre_reset_originals")
        assertFailsWith<D101ObservationUnavailable> { prepare(fresh) }
        assertEquals("PUBLIC", fresh.jdbc.queryForObject("SELECT state FROM game_server_publication", String::class.java))
        for (table in listOf("game_server_operation_reservation", "game_server_publication_operation", "game_server_d101_execution", "game_server_registry_transition")) {
            assertEquals(0, count(fresh, table))
        }
        val replay = fixture()
        val prepared = prepare(replay).execution
        replay.jdbc.update("DELETE FROM game_server_d101_pre_reset_originals")
        assertFailsWith<D101ObservationUnavailable> { prepare(replay) }
        assertEquals(prepared.createdAt, replay.store().query(f.operation)!!.createdAt)
        assertEquals(0, count(replay, "game_server_d101_pre_reset_originals"))
        assertEquals(1, count(replay, "game_server_d101_execution"))
    }

    @Test
    fun `same logical JSON with different original bytes cannot replace prepared identity`() {
        val db = fixture()
        prepare(db)
        val original = f.prepareBody()
        val different = original + byteArrayOf(0x20)
        assertFailsWith<D101OperationConflict> {
            db.store().prepare(different, f.requestCodec.prepare(different), grant(D101PurposeAction.PREPARE, different))
        }
        assertContentEquals(original, db.store().query(f.operation)!!.preparePayload())
    }

    @Test
    fun `publication history and another server common reservation cannot become new D101`() {
        val db = fixture()
        val intent = f.intent()
        db.writer.verifying(VerifyServerPublication("pep", 1, ServerPublicationTarget(f.operation, 0, "scenario_3190", intent.targetFingerprint)))
        assertFailsWith<D101OperationConflict> { prepare(db) }
        assertEquals("PUBLICATION_ONLY", db.jdbc.queryForObject("SELECT kind FROM game_server_operation_reservation", String::class.java))
        assertEquals(0, count(db, "game_server_d101_execution"))

        val other = fixture()
        other.jdbc.update("INSERT INTO game_server_operation_reservation VALUES (?, 'RECOVERY', 'uni', ?, 1)", f.operation, intent.targetFingerprint)
        assertFailsWith<D101OperationConflict> { prepare(other) }
        assertEquals("PUBLIC", other.jdbc.queryForObject("SELECT state FROM game_server_publication", String::class.java))
        assertEquals(0, count(other, "game_server_d101_execution"))
    }

    @Test
    fun `dispatch identity and registry flag commit atomically and refs never change on replay`() {
        val db = fixture()
        val execution = prepare(db).execution
        val candidate = dispatchCandidate()
        val body = dispatchBody(candidate)
        val proof = dispatchProof(execution, candidate)
        val result = db.store().dispatch(candidate, grant(D101PurposeAction.DISPATCH_INTENT, body), proof)
        assertTrue(result.created)
        assertEquals(D101ExecutionState.DISPATCH_INTENT, result.execution.state)
        assertEquals(true, db.jdbc.queryForObject("SELECT dispatched FROM game_server_registry_transition", Boolean::class.java))
        val replay = db.store().dispatch(candidate, grant(D101PurposeAction.DISPATCH_INTENT, body), proof)
        assertFalse(replay.created)
        assertEquals(result.execution.updatedAt, replay.execution.updatedAt)
        val changed = candidate.copy(rootRequestFingerprint = "8".repeat(64))
        assertFailsWith<D101OperationConflict> {
            db.store().dispatch(changed, grant(D101PurposeAction.DISPATCH_INTENT, dispatchBody(changed)), dispatchProof(execution, changed))
        }
        assertEquals(candidate, db.store().query(f.operation)!!.dispatch)
    }

    @Test
    fun `dispatch registry tampering and cutoff failure leave both states prepared`() {
        val db = fixture()
        val execution = prepare(db).execution
        db.jdbc.update("UPDATE game_server_registry_transition SET game_api_url='http://wrong:8081'")
        val candidate = dispatchCandidate()
        assertFailsWith<D101OperationConflict> {
            db.store().dispatch(candidate, grant(D101PurposeAction.DISPATCH_INTENT, dispatchBody(candidate)), dispatchProof(execution, candidate))
        }
        assertEquals(D101ExecutionState.PREPARED, db.store().query(f.operation)!!.state)
        assertEquals(false, db.jdbc.queryForObject("SELECT dispatched FROM game_server_registry_transition", Boolean::class.java))

        val stale = fixture()
        val staleExecution = prepare(stale).execution
        val staleGrant = grant(D101PurposeAction.DISPATCH_INTENT, dispatchBody(candidate))
        val staleProof = dispatchProof(staleExecution, candidate)
        f.clock.epoch = f.now + 30
        assertFailsWith<D101ObservationUnavailable> { stale.store().dispatch(candidate, staleGrant, staleProof) }
        assertEquals(D101ExecutionState.PREPARED, stale.store().query(f.operation)!!.state)
        f.clock.epoch = f.now

        val clean = fixture()
        val cleanExecution = prepare(clean).execution
        val signed = grant(D101PurposeAction.DISPATCH_INTENT, dispatchBody(candidate))
        f.clock.epoch = f.now + 3600
        assertFailsWith<D101PurposeGrantInvalid> { clean.store().dispatch(candidate, signed, dispatchProof(cleanExecution, candidate)) }
        assertEquals(D101ExecutionState.PREPARED, clean.store().query(f.operation)!!.state)
    }

    @Test
    fun `ordinary registry and final publication cannot dispatch complete or cancel D101 reset`() {
        val db = fixture()
        val execution = prepare(db).execution
        assertFailsWith<ServerRegistryTransitionConflict> { db.registry.claimTransition(f.operation, "ordinary") }
        val owner = db.jdbc.queryForObject("SELECT owner_token FROM game_server_registry_transition", String::class.java)!!
        assertFailsWith<IllegalStateException> { db.registry.markDispatched("pep", opensamguk.gateway.service.ServerRegistryTransitionAction.RESET, owner) }
        assertFailsWith<IllegalStateException> { db.registry.markRemoteApplied("pep", opensamguk.gateway.service.ServerRegistryTransitionAction.RESET, owner) }
        db.registry.cancelTransition("pep", opensamguk.gateway.service.ServerRegistryTransitionAction.RESET, owner)
        assertEquals(1, count(db, "game_server_registry_transition"))
        assertFailsWith<ServerRegistryTransitionConflict> {
            db.registry.completeTransition("pep", opensamguk.gateway.service.ServerRegistryTransitionAction.RESET, owner)
        }
        assertFailsWith<ServerPublicationConflict> { db.writer.publish(PublishServerPublication("pep", 2, f.operation, "7".repeat(64))) }
        assertEquals(0, db.verificationCalls)
        // A history-only generic replay is permitted and grants no physical authority.
        assertEquals(2L, db.writer.verifying(VerifyServerPublication("pep", 1,
            ServerPublicationTarget(f.operation, 0, "scenario_3190", execution.intent.targetFingerprint))).revision)
    }

    @Test
    fun `actual phase provider absent remains unavailable after synthetically authenticated prepare`() {
        val db = fixture()
        prepare(db)
        val candidate = dispatchCandidate()
        val body = dispatchBody(candidate)
        val request = request(D101PurposeAction.DISPATCH_INTENT, body)
        val service = D101ExecutionService(f.json, f.requestCodec, f.verifier(), db.store())
        assertFailsWith<D101ObservationUnavailable> { service.dispatch(f.operation, body, listOf(f.header(f.claims(request))), 1) }
        assertEquals(D101ExecutionState.PREPARED, db.store().query(f.operation)!!.state)
        assertEquals(false, db.jdbc.queryForObject("SELECT dispatched FROM game_server_registry_transition", Boolean::class.java))
    }

    @Test
    fun `missing row differs from corrupt source and mutable returned bytes cannot change history`() {
        val db = fixture()
        assertNull(db.store().query(f.operation))
        val execution = prepare(db).execution
        execution.preparePayload().fill(0)
        execution.intentBytes().fill(0)
        assertEquals(D101ExecutionState.PREPARED, db.store().query(f.operation)!!.state)
        db.jdbc.update("UPDATE game_server_operation_reservation SET kind='PUBLICATION_ONLY'")
        assertFailsWith<D101ObservationUnavailable> { db.store().query(f.operation) }
        db.jdbc.update("UPDATE game_server_operation_reservation SET kind='D101_RESET'")
        db.jdbc.update("UPDATE game_server_d101_execution SET intent_bytes=?", byteArrayOf(1))
        assertFailsWith<D101ObservationUnavailable> { db.store().query(f.operation) }
        // Isolated H2 fixture: remove dependent recovery FK too, so the next
        // assertion observes an actually missing source rather than a DDL error.
        db.jdbc.execute("DROP TABLE game_server_d101_execution CASCADE")
        assertFailsWith<D101ObservationUnavailable> { db.store().query(f.operation) }
    }

    @Test
    fun `simultaneous same operation has one durable reservation and different bytes have one winner`() {
        for (differentBytes in listOf(false, true)) {
            val db = fixture()
            val original = f.prepareBody()
            val wires = listOf(original, if (differentBytes) original + byteArrayOf(0x20) else original)
            val grants = wires.map { grant(D101PurposeAction.PREPARE, it) }
            val ready = java.util.concurrent.CountDownLatch(2)
            val start = java.util.concurrent.CountDownLatch(1)
            val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val results = wires.mapIndexed { index, wire -> executor.submit<Boolean?> {
                    ready.countDown()
                    check(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    try {
                        db.store().prepare(wire, f.requestCodec.prepare(wire), grants[index]).created
                    } catch (_: D101OperationConflict) {
                        null
                    }
                } }
                check(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
                start.countDown()
                val values = results.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
                assertEquals(1, values.count { it == true })
                assertEquals(if (differentBytes) 1 else 0, values.count { it == null })
                for (table in listOf("game_server_operation_reservation", "game_server_publication_operation", "game_server_d101_execution", "game_server_registry_transition")) {
                    assertEquals(1, count(db, table))
                }
            } finally {
                start.countDown()
                executor.shutdownNow()
                check(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `verified physical result persists before canonical settlement and same result replay is immutable`() {
        val db = fixture()
        val execution = prepare(db).execution
        val dispatch = dispatchCandidate()
        val dispatched = db.store().dispatch(dispatch, grant(D101PurposeAction.DISPATCH_INTENT, dispatchBody(dispatch)), dispatchProof(execution, dispatch)).execution
        // Synthetic transaction projection only. C3 verifies actual Root crypto
        // and status before constructing this projection in the real adapter.
        val wire = "verified synthetic Root success".toByteArray()
        val candidate = D101TerminalCandidate(2, D101Fixture.hash(wire))
        val body = terminalBody(candidate)
        val source = D101VerifiedTerminalEvidence(dispatched, candidate.rootResultReceiptSha256, wire)
        val remote = db.store().remoteSucceeded(candidate, grant(D101PurposeAction.SETTLE_REGISTRY, body), source)
        assertEquals(D101ExecutionState.REMOTE_SUCCEEDED, remote.execution.state)
        assertEquals("old-name", db.registry.find("pep")!!.name)
        assertEquals("VERIFYING", db.source.find("pep")!!.state.name)
        assertEquals(1, count(db, "game_server_registry_transition"))
        assertFalse(db.store().remoteSucceeded(candidate, grant(D101PurposeAction.SETTLE_REGISTRY, body), source).created)
        val changed = "different verified result".toByteArray()
        val changedCandidate = D101TerminalCandidate(2, D101Fixture.hash(changed))
        assertFailsWith<D101OperationConflict> {
            db.store().remoteSucceeded(changedCandidate, grant(D101PurposeAction.SETTLE_REGISTRY, terminalBody(changedCandidate)),
                D101VerifiedTerminalEvidence(dispatched, changedCandidate.rootResultReceiptSha256, changed))
        }
        val settled = db.store().settleRegistry(candidate, grant(D101PurposeAction.SETTLE_REGISTRY, body))
        assertEquals(D101ExecutionState.REGISTRY_SETTLED, settled.execution.state)
        val canonical = db.registry.find("pep")!!
        assertEquals("빼섭", canonical.name)
        assertEquals(0, canonical.generation)
        assertEquals("scenario_3190", canonical.scenarioCode)
        assertEquals("http://spep-game-api:8081", canonical.gameApiUrl)
        assertEquals("http://spep-game-engine:8082", canonical.gameEngineUrl)
        assertEquals("opensamguk-spep", canonical.deployProject)
        assertEquals(0, count(db, "game_server_registry_transition"))
        assertFalse(db.store().settleRegistry(candidate, grant(D101PurposeAction.SETTLE_REGISTRY, body)).created)
        assertEquals(ServerPublicationState.VERIFYING, db.source.find("pep")!!.state)
        assertEquals(2L, db.source.find("pep")!!.revision)
    }

    @Test
    fun `canonical failure retains physical success and only settlement resumes`() {
        val db = fixture()
        val execution = prepare(db).execution
        val dispatch = dispatchCandidate()
        val dispatched = db.store().dispatch(dispatch, grant(D101PurposeAction.DISPATCH_INTENT, dispatchBody(dispatch)), dispatchProof(execution, dispatch)).execution
        val wire = "verified synthetic Root success".toByteArray()
        val candidate = D101TerminalCandidate(2, D101Fixture.hash(wire))
        val body = terminalBody(candidate)
        db.store().remoteSucceeded(candidate, grant(D101PurposeAction.SETTLE_REGISTRY, body), D101VerifiedTerminalEvidence(dispatched, candidate.rootResultReceiptSha256, wire))
        db.jdbc.update("UPDATE game_server_registry_transition SET dispatched=FALSE")
        assertFailsWith<D101OperationConflict> { db.store().settleRegistry(candidate, grant(D101PurposeAction.SETTLE_REGISTRY, body)) }
        assertEquals(D101ExecutionState.REMOTE_SUCCEEDED, db.store().query(f.operation)!!.state)
        assertEquals(candidate.rootResultReceiptSha256, db.store().query(f.operation)!!.rootResultReceiptSha256)
        assertEquals("old-name", db.registry.find("pep")!!.name)
        db.jdbc.update("UPDATE game_server_registry_transition SET dispatched=TRUE")
        assertEquals(D101ExecutionState.REGISTRY_SETTLED, db.store().settleRegistry(candidate, grant(D101PurposeAction.SETTLE_REGISTRY, body)).execution.state)
        assertEquals(1, count(db, "game_server_d101_execution"))
        assertEquals(1, count(db, "game_server_publication_operation"))
    }

    private fun terminalBody(c: D101TerminalCandidate) = f.mapper.writeValueAsBytes(linkedMapOf(
        "schemaVersion" to 1, "verifyingRevision" to c.verifyingRevision.toString(), "rootResultReceiptSha256" to c.rootResultReceiptSha256,
    ))

    private fun prepare(db: Fixture): D101ExecutionWrite {
        val wire = f.prepareBody()
        return db.store().prepare(wire, f.requestCodec.prepare(wire), grant(D101PurposeAction.PREPARE, wire))
    }

    private fun request(action: D101PurposeAction, wire: ByteArray): D101PurposeRequest {
        val intent = f.intent()
        return D101PurposeRequest(action, f.operation, intent.targetFingerprint, intent.sha256, D101Fixture.hash(f.prepareBody()),
            1, action.method, action.path(f.operation), wire)
    }
    private fun grant(action: D101PurposeAction, wire: ByteArray): D101VerifiedPurposeGrant {
        val req = request(action, wire)
        // PREPARE payload identity is its actual received bytes, including semantic-equivalent whitespace.
        val exact = if (action == D101PurposeAction.PREPARE) D101PurposeRequest(action, f.operation, req.targetFingerprint,
            req.approvalIntentSha256, D101Fixture.hash(wire), 1, action.method, action.path(f.operation), wire) else req
        return f.verifier().verify(listOf(f.header(f.claims(exact))), exact)
    }

    private fun dispatchCandidate() = D101DispatchIntentCandidate(2, "4".repeat(64), "5".repeat(64), "6".repeat(64))
    private fun dispatchBody(c: D101DispatchIntentCandidate) = f.mapper.writeValueAsBytes(linkedMapOf(
        "schemaVersion" to 1, "verifyingRevision" to c.verifyingRevision.toString(), "approvalPlanSha256" to c.approvalPlanSha256,
        "executionReceiptSha256" to c.executionReceiptSha256, "rootRequestFingerprint" to c.rootRequestFingerprint,
    ))
    private fun dispatchProof(e: D101Execution, c: D101DispatchIntentCandidate) = D101VerifiedDispatch(
        e.intent.operationId, e.intent.sha256, e.gatewayPayloadSha256, e.intent.targetFingerprint, e.intent.initialPublicRevision, c,
        f.now, f.now + 30, f.clock,
    )

    private fun count(db: Fixture, table: String) = db.jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)

    private fun fixture(): Fixture {
        val jdbc = JdbcTemplate(DriverManagerDataSource("jdbc:h2:mem:d101-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000", "sa", ""))
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
        jdbc.update("INSERT INTO game_server_publication (server_id,state,revision,operation_id,expected_generation,expected_scenario_code,target_fingerprint) VALUES ('pep','PUBLIC',1,NULL,NULL,NULL,NULL)")
        return Fixture(jdbc)
    }

    private inner class Fixture(val jdbc: JdbcTemplate) {
        val registry = ServerRegistry("", f.mapper, jdbc)
        val source = JdbcServerPublicationRepository(jdbc, registry)
        var verificationCalls = 0
        val writer = JdbcServerPublicationWriter(jdbc, source, ServerPublicationReceiptVerifier { _, _, _ -> verificationCalls++ })
        fun store() = JdbcD101ExecutionStore(jdbc, source, writer, registry, f.requestCodec)
    }
}

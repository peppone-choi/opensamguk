package opensamguk.gateway.d101

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.infra.JdbcD101PreResetOriginalsStore
import opensamguk.gateway.publication.domain.ServerPublication
import opensamguk.gateway.publication.domain.ServerPublicationState
import opensamguk.gateway.publication.domain.ServerPublicationTarget
import opensamguk.gateway.service.ServerDef
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/** Synthetic Gateway rows prove the capture transaction and immutable QUERY read. */
class D101PreResetOriginalsStoreTest {
    private val f = D101Fixture()

    @Test
    fun `nullable old registry and prior public target remain exact through query and defensive copies`() {
        // A shared Spring mapper may omit nulls; custody bytes must not.
        val db = fixture(f.mapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL))
        val oldOp = "e".repeat(32)
        val oldTarget = ServerPublicationTarget(oldOp, 7, "scenario_170", "d".repeat(64))
        db.jdbc.update("""UPDATE game_server_publication SET operation_id=?,expected_generation=?,
            expected_scenario_code=?,target_fingerprint=? WHERE server_id='pep'""",
            oldOp, 7, "scenario_170", oldTarget.fingerprint)
        val publication = ServerPublication("pep", ServerPublicationState.PUBLIC, 1, oldTarget)
        val original = db.capture(publication)
        val parsed = f.mapper.readTree(original.originalBytes())
        assertTrue(parsed["oldRegistry"]["generation"].isNull)
        assertTrue(parsed["oldRegistry"]["scenarioCode"].isNull)
        assertEquals(oldOp, parsed["oldPublication"]["operationId"].asText())
        assertEquals(7, parsed["oldPublication"]["expectedGeneration"].asInt())
        assertEquals(original.originalSha256, D101Fixture.hash(original.originalBytes()))

        db.insertExecution()
        db.jdbc.update("""UPDATE game_server_publication SET state='VERIFYING',revision=2,operation_id=?,
            expected_generation=0,expected_scenario_code='scenario_3190',target_fingerprint=? WHERE server_id='pep'""",
            f.operation, db.candidate.intent.targetFingerprint)
        val current = ServerPublication("pep", ServerPublicationState.VERIFYING, 2,
            ServerPublicationTarget(f.operation, 0, "scenario_3190", db.candidate.intent.targetFingerprint))
        val replay = db.capture(current)
        assertEquals(original.originalSha256, replay.originalSha256)
        assertContentEquals(original.originalBytes(), replay.originalBytes())
        val read = db.store.readForQuery(db.execution())
        assertContentEquals(original.originalBytes(), read.originalBytes())
        assertEquals(original.originalSha256, D101Fixture.hash(java.util.Base64.getUrlDecoder().decode(read.originalBytesBase64url())))
        val mutated = read.originalBytes()
        mutated[0] = 0
        assertEquals(original.originalSha256, D101Fixture.hash(read.originalBytes()))
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM game_server_d101_pre_reset_originals", Int::class.java))
    }

    @Test
    fun `capture needs the caller transaction and rejects changed binding or locked rows`() {
        val db = fixture()
        assertFailsWith<D101OperationConflict> {
            db.store.captureLockedForPrepare(db.candidate, db.canonical, db.publication)
        }
        assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM game_server_d101_pre_reset_originals", Int::class.java))
        db.capture()
        assertFailsWith<D101OperationConflict> { db.capture() }
        val changed = D101PrepareCandidate(db.candidate.intent, "f".repeat(64), db.candidate.intentBytes())
        assertFailsWith<D101OperationConflict> {
            db.tx.execute { db.store.captureLockedForPrepare(changed, db.canonical, db.publication) }
        }
        assertFailsWith<D101OperationConflict> {
            db.tx.execute { db.store.captureLockedForPrepare(db.candidate, db.canonical.copy(name = "not-locked"), db.publication) }
        }
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM game_server_d101_pre_reset_originals", Int::class.java))
    }

    @Test
    fun `capture rolls back with prepare and corrupted committed bytes fail closed`() {
        val db = fixture()
        assertFailsWith<IllegalStateException> {
            db.tx.execute {
                db.store.captureLockedForPrepare(db.candidate, db.canonical, db.publication)
                throw IllegalStateException("synthetic later PREPARE failure")
            }
        }
        assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM game_server_d101_pre_reset_originals", Int::class.java))
        db.capture()
        db.insertExecution()
        db.jdbc.update("UPDATE game_server_d101_pre_reset_originals SET original_sha=? WHERE operation_id=?",
            "f".repeat(64), f.operation)
        assertFailsWith<D101ObservationUnavailable> { db.store.readForQuery(db.execution()) }
    }

    @Test
    fun `an old execution without pre reset capture cannot be invented after publication closes`() {
        val db = fixture()
        db.insertExecution()
        db.jdbc.update("""UPDATE game_server_publication SET state='VERIFYING',revision=2,operation_id=?,
            expected_generation=0,expected_scenario_code='scenario_3190',target_fingerprint=? WHERE server_id='pep'""",
            f.operation, db.candidate.intent.targetFingerprint)
        val current = ServerPublication("pep", ServerPublicationState.VERIFYING, 2,
            ServerPublicationTarget(f.operation, 0, "scenario_3190", db.candidate.intent.targetFingerprint))
        assertFailsWith<D101OperationConflict> { db.capture(current) }
        assertFailsWith<D101ObservationUnavailable> { db.store.readForQuery(db.execution()) }
        assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM game_server_d101_pre_reset_originals", Int::class.java))
    }

    private fun fixture(mapper: ObjectMapper = f.mapper): Fixture {
        val jdbc = JdbcTemplate(DriverManagerDataSource(
            "jdbc:h2:mem:d101-pre-reset-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000", "sa", "",
        ))
        jdbc.execute("""CREATE TABLE game_server (server_id VARCHAR(48) PRIMARY KEY,display_name TEXT NOT NULL,
            game_api_url TEXT NOT NULL,game_engine_url TEXT NOT NULL,deploy_project TEXT NOT NULL,
            generation INTEGER,scenario_code TEXT)""")
        jdbc.execute("""CREATE TABLE game_server_publication (server_id VARCHAR(48) PRIMARY KEY,state VARCHAR(16) NOT NULL,
            revision BIGINT NOT NULL,operation_id VARCHAR(32),expected_generation INTEGER,
            expected_scenario_code TEXT,target_fingerprint VARCHAR(64))""")
        jdbc.execute("CREATE TABLE game_server_publication_operation (operation_id VARCHAR(32) PRIMARY KEY)")
        D101TestSchema.install(jdbc)
        jdbc.execute("""CREATE TABLE game_server_d101_pre_reset_originals (operation_id VARCHAR(32) PRIMARY KEY,
            intent_sha VARCHAR(64) NOT NULL,target_fingerprint VARCHAR(64) NOT NULL,gateway_payload_sha VARCHAR(64) NOT NULL,
            initial_public_revision BIGINT NOT NULL,original_sha VARCHAR(64) NOT NULL,original_bytes BYTEA NOT NULL)""")
        jdbc.update("""INSERT INTO game_server VALUES ('pep','old-name','http://spep-game-api:8081',
            'http://spep-game-engine:8082','opensamguk-spep',NULL,NULL)""")
        jdbc.update("INSERT INTO game_server_publication VALUES ('pep','PUBLIC',1,NULL,NULL,NULL,NULL)")
        return Fixture(jdbc, mapper)
    }

    private inner class Fixture(val jdbc: JdbcTemplate, mapper: ObjectMapper) {
        val tx = TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.dataSource)))
        val store = JdbcD101PreResetOriginalsStore(jdbc, mapper)
        val body = f.prepareBody()
        val candidate = f.requestCodec.prepare(body)
        val canonical = ServerDef("pep", "old-name", "http://spep-game-api:8081",
            "http://spep-game-engine:8082", "opensamguk-spep", null, null)
        val publication = ServerPublication("pep", ServerPublicationState.PUBLIC, 1, null)

        fun capture(current: ServerPublication = publication): D101PreResetOriginalsRead =
            requireNotNull(tx.execute { store.captureLockedForPrepare(candidate, canonical, current) })

        fun insertExecution() {
            jdbc.update("INSERT INTO game_server_operation_reservation VALUES (?,?,?,?,?)",
                f.operation, "D101_RESET", "pep", candidate.intent.targetFingerprint, 1)
            jdbc.update("""INSERT INTO game_server_d101_execution
                (operation_id,server_id,world_id,state,last_safe_state,intent_sha,intent_bytes,gateway_payload_sha,
                 prepare_payload,target_fingerprint,initial_public_revision,verifying_revision)
                VALUES (?,'pep',1,'PREPARED','PREPARED',?,?,?,?,?,1,2)""",
                f.operation, candidate.intent.sha256, candidate.intentBytes(), candidate.gatewayPayloadSha256,
                body, candidate.intent.targetFingerprint)
        }

        fun execution(): D101Execution = D101Execution(candidate.intent, candidate.intentBytes(), body,
            candidate.gatewayPayloadSha256, D101ExecutionState.PREPARED, D101ExecutionState.PREPARED, 2,
            null, null, null, null, Instant.parse("2026-10-06T09:00:00Z"), Instant.parse("2026-10-06T09:00:00Z"))
    }
}

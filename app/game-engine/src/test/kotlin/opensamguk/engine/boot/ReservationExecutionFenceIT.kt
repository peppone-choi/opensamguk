package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.common.wire.*
import opensamguk.engine.flush.DeltaGenerationSession
import opensamguk.engine.flush.FlushRecoveryGate
import opensamguk.engine.flush.PrimaryDaemonRecovery
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.run.TurnRunService
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.*
import org.mockito.Mockito.mock
import org.springframework.context.support.GenericApplicationContext
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier
import kotlin.test.*

/** Real lifecycle, primary loader, JDBC flush and commit boundaries; never silently skips Docker. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReservationExecutionFenceIT {
    private val pg = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    private lateinit var source: DriverManagerDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var named: NamedParameterJdbcTemplate
    private lateinit var fence: ReservationExecutionFence
    private lateinit var fixture: EnlistmentFixture
    private val pool = Executors.newFixedThreadPool(2)
    private val worldId = WorldId(731)
    private val at = Instant.parse("0200-01-01T00:00:01Z")
    private var afterSnapshot: () -> Unit = {}
    private var beforeCommit: (FlushPayload) -> Unit = {}
    private var failFlush = false
    private var loseCommitReply = false
    private var includeIntake = false
    private var failDelivery = false
    private val intakeEnvelope = TurnDaemonCommandEnvelope("cancellation-intake", "0200-01-01T00:00:00Z",
        TurnDaemonCommand.Vacation(generalId = 1))
    private lateinit var current: Prepared
    private val published = mutableListOf<String>()
    private val pids = mutableListOf<Int>()
    private var previousArtifactsRoot: String? = null

    private data class Prepared(val world: InMemoryTurnWorld, val recorder: ChangeRecorder,
        val generation: DeltaGenerationSession, val service: TurnRunService)

    @BeforeAll fun start() {
        pg.start()
        source = DriverManagerDataSource(pg.jdbcUrl, pg.username, pg.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source); named = NamedParameterJdbcTemplate(source)
        val manager = object : DataSourceTransactionManager(source) {
            override fun doCommit(status: DefaultTransactionStatus) {
                super.doCommit(status)
                if (loseCommitReply) throw TransactionSystemException("Synthetic lost commit acknowledgement")
            }
        }
        fence = ReservationExecutionFence(named, TransactionTemplate(manager))
        fixture = EnlistmentFixture(jdbc, JdbcFlushExecutor(named, fence.transactions))
    }

    @AfterAll fun stop() { pool.shutdownNow(); pg.stop() }
    @AfterEach fun restoreArtifactsRoot() {
        previousArtifactsRoot?.let { System.setProperty("opensamguk.artifacts.root", it) }
            ?: System.clearProperty("opensamguk.artifacts.root")
    }
    @BeforeEach fun reset() {
        previousArtifactsRoot = System.getProperty("opensamguk.artifacts.root")
        System.setProperty("opensamguk.artifacts.root", PassChainSupport.repoRoot().toString())
        jdbc.execute("TRUNCATE world_state CASCADE")
        fixture.seed(worldId.value)
        jdbc.update("UPDATE world_state SET start_time='0200-01-01T00:00:00Z',meta=meta || '{\"startYear\":200,\"startTime\":\"0200-01-01T00:00:00Z\"}'::jsonb WHERE id=731")
        jdbc.update("UPDATE general SET user_id='42',turn_time='0200-01-01T00:00:00Z' WHERE world_id=? AND id=1", worldId.value)
        jdbc.update("UPDATE general SET turn_time='0200-02-01T00:00:00Z' WHERE world_id=? AND id<>1", worldId.value)
        ReservedTurnRepository(named).reserve(worldId, 1, 0, "action.scout", "{}", "견문")
        afterSnapshot = {}; beforeCommit = {}; failFlush = false; loseCommitReply = false; includeIntake = false; failDelivery = false
        published.clear(); pids.clear(); current = prepare()
    }

    private fun prepare(fenced: Boolean = true): Prepared {
        val active = InMemoryTurnWorld(fixture.load(worldId.value)) // Actual WorldSnapshotLoader, no in-memory clone.
        val recorder = ChangeRecorder(); val handler = fixture.reservedHandler(active, recorder)
        val generation = DeltaGenerationSession()
        val reservations = ReservedTurnRepository(named)
        val lifecycle = TurnDaemonLifecycle(active, handler,
            pullGeneralTurnOf = { actor, selected -> recorder.recordGeneralTurnPull(actor, expectedReservation = selected) },
            reservedActionOf = { actor -> reservations.readReserved(worldId, actor, 0).also {
                pids += jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!
                afterSnapshot()
            } })
        val redis = mock(StringRedisTemplate::class.java)
        org.mockito.Mockito.doAnswer {
            assertEquals(DeltaGenerationSession.Phase.IDLE, generation.snapshot().phase)
            published += "clock"
            0L
        }.`when`(redis).convertAndSend(org.mockito.Mockito.anyString(), org.mockito.Mockito.any<Any>())
        val stream = object : RedisCommandStream(redis, "cancel-fence-fixture", worldId, startId = "0") {
            override fun readEnvelopes(blockMs: Long) = emptyList<TurnDaemonCommandEnvelope>()
            override fun readWakeEnvelopes(blockMs: Long) = if (includeIntake) listOf(WakeEnvelope("synthetic-wake", intakeEnvelope)) else emptyList()
            override fun acknowledgeWake(messageIds: List<String>): Long {
                published += "ack"
                if (failDelivery) throw org.springframework.dao.DataAccessResourceFailureException("Synthetic post-commit ACK failure")
                return messageIds.size.toLong()
            }
        }
        val publisher = object : RealtimePublisher(redis, "cancel-fence-fixture", worldId) {
            override fun publishCommandResultPayload(requestId: String, payloadJson: String) {
                published += "result"
                if (failDelivery) throw org.springframework.dao.DataAccessResourceFailureException("Synthetic post-commit publication failure")
            }
            override fun publishRealtimeEvent(event: RealtimeEvent) { published += "clock" }
        }
        val configuredFlush = opensamguk.engine.config.DaemonLoopConfig().jdbcFlushExecutor(named, fence)
        val flush = object : JdbcFlushExecutor(named, fence.transactions) {
            override fun flush(payload: FlushPayload) {
                pids += jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!
                configuredFlush.flush(payload)
                beforeCommit(payload)
                if (failFlush) throw org.springframework.dao.DataAccessResourceFailureException("Synthetic flush failure")
            }
        }
        return Prepared(active, recorder, generation, TurnRunService(active, stream, lifecycle, handler, flush, publisher,
            generationSession = generation, executionFence = if (fenced) fence else null,
            boardPostRepository = if (includeIntake) mock(opensamguk.infra.read.BoardPostRepository::class.java) else null,
            commandInboxRepository = if (includeIntake) CommandInboxRepository(named) else null))
    }

    private fun fullRows() = listOf("world_state", "general", "city", "general_turn", "log_entry", "game_event",
        "command_inbox", "command_result", "command_outbox").associateWith { table ->
        jdbc.queryForList("SELECT to_jsonb(t)::text AS row FROM $table t ORDER BY to_jsonb(t)::text") }
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(20, TimeUnit.SECONDS))
    private fun sharedAvailable() = fence.transactions.execute { fence.tryCancellation(worldId) }!!

    @Test fun `late revision check alone allows effects from a reservation deleted after snapshot`() {
        current = prepare(fenced = false)
        val beforeGeneral = fullRows().getValue("general")
        afterSnapshot = { jdbc.update("DELETE FROM general_turn WHERE world_id=731 AND general_id=1 AND turn_idx=0") }
        val result = current.service.runDueGeneralTurns(at)
        assertEquals("action.scout", result.handled.single().reservedActionCode)
        assertEquals(1, jdbc.queryForObject("SELECT world_version FROM world_state WHERE id=731", Int::class.java))
        assertNotEquals(beforeGeneral, fullRows().getValue("general"), "Selected cancelled action still committed general effects")
        assertTrue(ReservedTurnRepository(named).readReserved(worldId, 1, 0).rowExists.not())
    }

    @Test fun `exclusive claim failure has zero memory and database changes`() {
        val locked = CountDownLatch(1); val release = CountDownLatch(1)
        val holder = pool.submit { fence.execute(worldId) { locked.countDown(); await(release) } }
        try {
            await(locked)
            val before = fullRows(); val memory = current.world.getState()
            fence.transactions.timeout = 1
            assertFails { current.service.runTick(at) }
            assertEquals(before, fullRows()); assertEquals(memory, current.world.getState())
            assertTrue(current.recorder.dirtyGeneralIds().isEmpty())
            assertEquals(FlushRecoveryGate.Mode.READY, current.service.recoverySnapshot().mode)
            assertTrue(published.isEmpty())
        } finally {
            fence.transactions.timeout = org.springframework.transaction.TransactionDefinition.TIMEOUT_DEFAULT
            release.countDown(); holder.get(20, TimeUnit.SECONDS)
        }
    }

    @Test fun `snapshot and flush use same physical connection and memory commits only after outer commit`() {
        beforeCommit = {
            assertEquals(DeltaGenerationSession.Phase.PREPARED, current.generation.snapshot().phase)
            assertTrue(current.recorder.dirtyGeneralIds().isNotEmpty())
            assertTrue(published.isEmpty(), "No publish or ACK before actual commit")
            assertEquals(0, JdbcTemplate(DriverManagerDataSource(pg.jdbcUrl, pg.username, pg.password))
                .queryForObject("SELECT world_version FROM world_state WHERE id=731", Int::class.java))
        }
        current.service.runTick(at)
        assertTrue(pids.size >= 2); assertEquals(1, pids.distinct().size)
        assertEquals(DeltaGenerationSession.Phase.IDLE, current.generation.snapshot().phase)
        assertTrue(current.recorder.dirtyGeneralIds().isEmpty())
        assertEquals(1L, current.world.getState().worldVersion)
        assertEquals(at, current.world.getState().lastTurnTime)
        assertTrue(sharedAvailable())
    }

    @Test fun `real snapshot to flush interval rejects shared cancellation claim`() {
        val selected = CountDownLatch(1); val release = CountDownLatch(1)
        afterSnapshot = { selected.countDown(); await(release) }
        val tick = pool.submit<TurnRunService.TickResult> { current.service.runDueGeneralTurns(at) }
        try { await(selected); assertFalse(sharedAvailable()) }
        finally { release.countDown(); tick.get(20, TimeUnit.SECONDS) }
        assertTrue(sharedAvailable())
    }

    @Test fun `flush rollback clears all effects and forbids retained generation retry after cancellation`() {
        val before = fullRows(); failFlush = true
        assertFailsWith<org.springframework.dao.DataAccessResourceFailureException> { current.service.runTick(at) }
        assertEquals(before, fullRows())
        assertEquals(FlushRecoveryGate.Mode.RELOAD_REQUIRED, current.service.recoverySnapshot().mode)
        assertFalse(current.service.recoverySnapshot().hasRetainedPayload)
        assertTrue(published.isEmpty()); assertTrue(sharedAvailable())
        assertFailsWith<IllegalStateException> { current.service.retryRetainedFlush() }
        fence.transactions.execute {
            assertTrue(fence.tryCancellation(worldId))
            jdbc.update("DELETE FROM general_turn WHERE world_id=731 AND general_id=1 AND turn_idx=0")
        }
        assertFailsWith<IllegalStateException> { current.service.runTick(at) }
        failFlush = false
        val fresh = prepare(); assertNotSame(current.world, fresh.world)
        fresh.service.runTick(at)
        assertEquals(1, jdbc.queryForObject("SELECT world_version FROM world_state WHERE id=731", Int::class.java))
    }

    @Test fun `ambiguous commit cannot replay and primary reload observes already committed clock`() {
        loseCommitReply = true
        assertFailsWith<TransactionSystemException> { current.service.runTick(at) }
        assertEquals(FlushRecoveryGate.Mode.RELOAD_REQUIRED, current.service.recoverySnapshot().mode)
        assertFalse(current.service.recoverySnapshot().hasRetainedPayload)
        assertTrue(published.isEmpty())
        assertEquals(1, jdbc.queryForObject("SELECT world_version FROM world_state WHERE id=731", Int::class.java))
        assertFailsWith<IllegalStateException> { current.service.retryRetainedFlush() }
        loseCommitReply = false
        val fresh = prepare()
        assertEquals(at, fresh.world.getState().lastTurnTime)
        assertEquals(1L, fresh.world.getState().worldVersion)
        assertTrue(fresh.world.getGeneralById(1)!!.turnTime.isAfter(at))
    }

    @Test fun `bounded full context recovery reconstructs primary world recorder and service after rollback`() {
        val old = GenericApplicationContext().apply {
            registerBean(Prepared::class.java, Supplier { current }); refresh()
        }
        failFlush = true
        assertFailsWith<org.springframework.dao.DataAccessResourceFailureException> { current.service.runTick(at) }
        failFlush = false
        var fresh: GenericApplicationContext? = null
        val attempted = AtomicBoolean()
        val recovery = PrimaryDaemonRecovery(old::close, {
            fresh = GenericApplicationContext().apply { registerBean(Prepared::class.java, Supplier { prepare() }); refresh() }
        }, attempted, launch = { it() })
        assertTrue(recovery.requestRestart()); assertFalse(old.isActive)
        try {
            val next = fresh!!.getBean(Prepared::class.java)
            assertNotSame(current.service, next.service); assertNotSame(current.recorder, next.recorder)
            assertNotSame(current.world, next.world)
            assertEquals(0L, next.world.getState().worldVersion)
            assertTrue(next.recorder.dirtyGeneralIds().isEmpty())
            next.service.runTick(at)
            assertEquals(1L, next.world.getState().worldVersion)
            assertFalse(recovery.requestRestart(), "No unbounded context restart loop")
        } finally { fresh?.close() }
    }

    private fun prepareIntake() {
        includeIntake = true
        CommandInboxRepository(named).insertAccepted(CommandInboxRepository.AcceptedCommand(worldId, intakeEnvelope.requestId,
            commandKind = CommandInboxRepository.CommandKind.IMMEDIATE, intentFingerprint = "synthetic-intake",
            generalId = 1, turnIdx = null, actionCode = "vacation",
            payloadJson = WireJson.encodeToString(TurnDaemonCommandEnvelope.serializer(), intakeEnvelope), ownerUserId = 42))
        current = prepare()
    }

    @Test fun `immediate intake holds execution fence and publishes result and ACK only after commit`() {
        prepareIntake()
        beforeCommit = {
            assertTrue(published.isEmpty())
            assertEquals(DeltaGenerationSession.Phase.PREPARED, current.generation.snapshot().phase)
            assertTrue(current.recorder.dirtyGeneralIds().isNotEmpty())
            assertFalse(pool.submit<Boolean> { sharedAvailable() }.get(10, TimeUnit.SECONDS))
        }
        assertEquals(1, current.service.runIntakeCommands())
        assertEquals(listOf("ack", "result"), published)
        assertTrue(current.recorder.dirtyGeneralIds().isEmpty())
        assertEquals("APPLIED", jdbc.queryForObject("SELECT status FROM command_inbox WHERE request_id='cancellation-intake'", String::class.java))
    }

    @Test fun `ACK and publication failures after commit never replay effects or block local clock completion`() {
        prepareIntake(); failDelivery = true
        current.service.runTick(at)
        assertEquals(1L, current.world.getState().worldVersion)
        assertEquals(at, current.world.getState().lastTurnTime)
        assertEquals(FlushRecoveryGate.Mode.READY, current.service.recoverySnapshot().mode)
        assertFalse(current.service.recoverySnapshot().hasRetainedPayload)
        assertTrue(current.recorder.dirtyGeneralIds().isEmpty())
        assertEquals(listOf("ack", "result", "clock"), published)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM command_result", Int::class.java))
    }

    @Test fun `production application context automatically restarts from primary after failed generation`() {
        val arguments = arrayOf(
            "--spring.main.web-application-type=none", "--opensamguk.daemon.enabled=false",
            "--spring.datasource.url=${pg.jdbcUrl}", "--spring.datasource.username=${pg.username}",
            "--spring.datasource.password=${pg.password}", "--opensamguk.world-id=${worldId.value}",
            "--OPENSAMGUK_WORLD_ID=${worldId.value}", "--management.health.redis.enabled=false",
            "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
                "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration," +
                "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
        )
        val factory = opensamguk.engine.flush.PrimaryDaemonContextFactory()
        val old = factory.prepare(arguments)
        var fresh: org.springframework.context.ConfigurableApplicationContext? = null
        try {
            val originalService = old.getBean(TurnRunService::class.java)
            val originalWorld = old.getBean(InMemoryTurnWorld::class.java)
            assertIs<DataSourceTransactionManager>(old.getBean(ReservationExecutionFence::class.java).transactions.transactionManager)
            jdbc.execute("CREATE FUNCTION cancellation_flush_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic flush fault'; END $$")
            jdbc.execute("CREATE TRIGGER cancellation_flush_fault BEFORE UPDATE ON general FOR EACH ROW EXECUTE FUNCTION cancellation_flush_fault()")
            val before = fullRows()
            assertFails { originalService.runDueGeneralTurns(at) }
            assertEquals(before, fullRows())
            assertEquals(FlushRecoveryGate.Mode.RELOAD_REQUIRED, originalService.recoverySnapshot().mode)
            jdbc.execute("DROP TRIGGER cancellation_flush_fault ON general")
            jdbc.execute("DROP FUNCTION cancellation_flush_fault()")
            // In production the same coordinator runs on its own thread after the runner sees RELOAD_REQUIRED.
            val restarted = CountDownLatch(1)
            val recovery = PrimaryDaemonRecovery(old::close, { fresh = factory.prepare(arguments); restarted.countDown() }, AtomicBoolean())
            assertTrue(recovery.requestRestart()); await(restarted)
            assertFalse(old.isActive)
            val next = fresh!!.getBean(TurnRunService::class.java)
            val nextWorld = fresh!!.getBean(InMemoryTurnWorld::class.java)
            assertNotSame(originalService, next); assertNotSame(originalWorld, nextWorld)
            assertEquals(0L, nextWorld.getState().worldVersion)
            assertEquals(FlushRecoveryGate.Mode.READY, next.recoverySnapshot().mode)
            assertFalse(recovery.requestRestart())
            next.runDueGeneralTurns(at)
            assertEquals(1L, nextWorld.getState().worldVersion)
        } finally {
            if (old.isActive) old.close()
            fresh?.close()
            jdbc.execute("DROP TRIGGER IF EXISTS cancellation_flush_fault ON general")
            jdbc.execute("DROP FUNCTION IF EXISTS cancellation_flush_fault()")
        }
    }

    @Test fun `recovery preparation failure closes old context and has no automatic restart loop`() {
        val old = GenericApplicationContext().apply { refresh() }
        var attempts = 0
        val recovery = PrimaryDaemonRecovery(old::close, { attempts++; error("Synthetic primary unavailable") },
            AtomicBoolean(), launch = { it() })
        assertTrue(recovery.requestRestart()); assertFalse(old.isActive)
        assertFalse(recovery.requestRestart()); assertEquals(1, attempts)
    }

    @Test fun `nested transaction cannot finalize a generation before its outer commit`() {
        val before = fullRows()
        fence.transactions.execute {
            assertFailsWith<IllegalStateException> { current.service.runDueGeneralTurns(at) }
        }
        assertEquals(before, fullRows())
        assertTrue(current.recorder.dirtyGeneralIds().isEmpty())
        assertTrue(published.isEmpty())
    }

    @Test fun `snapshot failure stops before effects and no old generation is retained`() {
        val before = fullRows()
        afterSnapshot = { throw IllegalStateException("Synthetic snapshot failure") }
        assertFailsWith<IllegalStateException> { current.service.runTick(at) }
        assertEquals(before, fullRows()); assertTrue(published.isEmpty())
        assertFalse(current.service.recoverySnapshot().hasRetainedPayload)
    }
}

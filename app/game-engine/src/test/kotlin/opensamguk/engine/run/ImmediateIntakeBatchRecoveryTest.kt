package opensamguk.engine.run

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.encodeCommandPayload
import opensamguk.common.world.WorldId
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.read.BoardPostRepository
import opensamguk.infra.read.ContactReader
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertAll
import org.mockito.Mockito.mock
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** GH1489 / OPENSAM-322: candidate only until the normal control and failure cases execute. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImmediateIntakeBatchRecoveryTest {
    private class Database : PostgreSQLContainer<Database>("postgres:16-alpine")
    private lateinit var postgres: Database
    private lateinit var source: DataSource
    private lateinit var jdbc: JdbcTemplate
    private val nextWorld = AtomicInteger(910)

    @BeforeAll
    fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable,
            "Docker unavailable: immediate batch database recovery NOT verified")
        val nonce = System.getenv("BUG1_BATCH_NONCE") ?: UUID.randomUUID().toString()
        require(nonce.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        postgres = Database().withDatabaseName("bug1_batch")
            .withImagePullPolicy { false }
            .withLabel("opensamguk.bug1.batch", nonce)
            .withStartupTimeout(Duration.ofMinutes(2))
            .withCreateContainerCmdModifier { command ->
                checkNotNull(command.hostConfig).withMemory(256L * 1024 * 1024)
                    .withMemorySwap(256L * 1024 * 1024)
            }
        // A failed start may leave the container allocated; the driver also owns its nonce label.
        postgres.start()
        source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
    }

    @AfterAll
    fun cleanup() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun normalBatchCommitsEffectAndTerminal() {
        val fixture = fixture()
        assertEquals(2, fixture.service.runIntakeCommands())
        assertAligned(fixture)
        assertEquals(0, fixture.service.runIntakeCommands(), "terminal inbox must not replay")
        assertAligned(fixture)
    }

    @Test
    fun readFailureLeaseReplayKeepsEffectAndTerminalAligned() {
        val fixture = fixture(failRead = true)
        assertReadFailure(fixture)
        fixture.inbox.afterLeaseExpiry = true
        assertEquals(2, fixture.service.runIntakeCommands())
        assertAligned(fixture)
        assertEquals(1, fixture.fault.failures.get(), "exactly one real ContactReader query failed")
    }

    @Test
    fun readFailureIntermediateTickKeepsEffectAndTerminalAligned() {
        val fixture = fixture(failRead = true)
        assertReadFailure(fixture)
        // Both inbox rows still have a live lease. A normal scheduled tick cannot claim them yet.
        fixture.service.runTick(fixture.now.plusSeconds(1))
        fixture.inbox.afterLeaseExpiry = true
        assertEquals(2, fixture.service.runIntakeCommands())
        assertAligned(fixture)
        assertEquals(1, fixture.fault.failures.get())
    }

    private data class Fixture(
        val id: WorldId,
        val now: Instant,
        val service: TurnRunService,
        val inbox: LeaseClockInbox,
        val fault: OneReadFailure,
        val sendId: String,
        val deleteId: String,
    )

    private fun assertReadFailure(fixture: Fixture) {
        val error = assertFailsWith<DataAccessException> { fixture.service.runIntakeCommands() }
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is SQLException },
            "failure must originate at the ContactReader JDBC seam")
        assertEquals(1, fixture.fault.failures.get())
        assertEquals(2, scalar("SELECT count(*) FROM command_inbox WHERE world_id=? AND status='CLAIMED'", fixture.id),
            "the accepted inputs must remain durably replayable")
        assertEquals(0, scalar("SELECT count(*) FROM command_result WHERE world_id=?", fixture.id),
            "the failed batch did not reach its result flush")
        assertEquals(0, scalar("SELECT count(*) FROM message WHERE world_id=? AND type='private'", fixture.id),
            "no private effect was committed by the failed batch")
    }

    /** Separate JDBC reads observe committed storage, rather than a fabricated flush payload. */
    private fun assertAligned(fixture: Fixture) {
        val id = fixture.id
        val results = jdbc.queryForList("""
            SELECT terminal_status, ok, result_payload->'event'->'result'->>'msgID' AS message_id
              FROM command_result WHERE world_id=? AND request_id=? AND result_seq=1
        """.trimIndent(), id.value, fixture.sendId)
        val result = results.singleOrNull().orEmpty()
        val receiverIds = jdbc.queryForList("""
            SELECT id FROM message WHERE world_id=? AND mailbox=20 AND type='private'
               AND src=10 AND dest=20 AND message->>'text'='batch-private'
        """.trimIndent(), Int::class.java, id.value)
        val receiverId = receiverIds.singleOrNull()
        assertAll(
            { assertEquals(1, results.size, "one durable send result must exist") },
            { assertEquals(1, receiverIds.size, "one private receiver row must exist") },
            { assertEquals(2, scalar("SELECT count(*) FROM message WHERE world_id=? AND type='private'", id),
                "one receiver row and one sender copy, never duplicate delivery") },
            { assertEquals("APPLIED", result["terminal_status"], "delivered private send needs a successful durable result") },
            { assertEquals(true, result["ok"]) },
            { assertEquals(receiverId.toString(), result["message_id"], "result must identify the actual receiver row") },
            { assertEquals(2, scalar("SELECT count(*) FROM command_result WHERE world_id=? AND ok=true", id)) },
            { assertEquals(2, scalar("SELECT count(*) FROM command_inbox WHERE world_id=? AND status='APPLIED'", id)) },
            { assertEquals(2, scalar("SELECT count(*) FROM command_outbox WHERE world_id=?", id)) },
            { assertEquals(1, scalar("SELECT count(*) FROM general WHERE world_id=? AND id=20 AND meta->>'newmsg'='1'", id)) },
            { assertEquals(1, scalar("SELECT count(*) FROM message WHERE world_id=? AND id=900 AND message->'option'->>'invalid'='true'", id)) },
        )
        val receiver = ContactReader(NamedParameterJdbcTemplate(source)).findMessage(id, checkNotNull(receiverId))
        assertEquals("batch-private", receiver?.text, "cold read uses the actual read model")
        assertEquals(10, receiver?.srcGeneralId)
        assertEquals(20, receiver?.destGeneralId)
    }

    private fun scalar(sql: String, id: WorldId): Int =
        checkNotNull(jdbc.queryForObject(sql, Int::class.java, id.value))

    private fun fixture(failRead: Boolean = false): Fixture {
        val id = WorldId(nextWorld.incrementAndGet())
        val now = Instant.now().minusSeconds(1)
        val config = """{"mapName":"han-world-v3","worldFormat":"GENERAL_RETAINER_CAMPAIGN"}"""
        val stateMeta = """{"startYear":200,"startTime":"$now","maxGeneralId":20,"maxNationId":1}"""
        jdbc.update("""
            INSERT INTO world_state(id,scenario_code,current_year,current_month,tick_seconds,world_version,writer_epoch,config,meta)
            VALUES (?,'bug1-batch',200,1,3600,0,1,?::jsonb,?::jsonb)
        """.trimIndent(), id.value, config, stateMeta)
        jdbc.update("INSERT INTO nation(world_id,id,name,color,level,capital_city_id) VALUES (?,1,'fixture-nation','#000000',1,1)", id.value)
        jdbc.update("""
            INSERT INTO city(world_id,id,name,level,nation_id,pop,pop_max,agri,agri_max,comm,comm_max,
                secu,secu_max,def,def_max,wall,wall_max,region)
            VALUES (?,1,'fixture-county',1,1,50,100,50,100,50,100,50,100,50,100,50,100,1)
        """.trimIndent(), id.value)
        val future = now.plusSeconds(3600)
        for (generalId in listOf(10, 20)) jdbc.update("""
            INSERT INTO general(world_id,id,user_id,name,nation_id,city_id,npc_state,officer_level,turn_time)
            VALUES (?,?,?, ?,1,1,0,1,?)
        """.trimIndent(), id.value, generalId, (generalId + 100).toString(), "person-$generalId", Timestamp.from(future))
        jdbc.update("""
            INSERT INTO message(world_id,id,mailbox,type,src,dest,time,valid_until,message)
            VALUES (?,900,9999,'public',10,9999,?,'9999-12-31T23:59:59Z',
               '{"src":{"id":10,"nation_id":1},"dest":{"id":9999,"nation_id":1},"text":"old-public","option":{}}'::jsonb)
        """.trimIndent(), id.value, Timestamp.from(now.minusSeconds(20)))
        val named = NamedParameterJdbcTemplate(source)
        val fault = OneReadFailure(source, failRead)
        val inbox = LeaseClockInbox(named)
        val sendId = "batch-${id.value}-send"
        val deleteId = "batch-${id.value}-delete"
        val commands = listOf(
            sendId to TurnDaemonCommand.SendMessage(requestId=sendId, generalId=10, mailbox=20, text="batch-private"),
            deleteId to TurnDaemonCommand.DeleteMessage(requestId=deleteId, generalId=10, msgID=900),
        )
        for ((requestId, command) in commands) assertEquals(CommandInboxRepository.InsertResult.Inserted,
            inbox.insertAccepted(CommandInboxRepository.AcceptedCommand(id, requestId,
                commandKind=CommandInboxRepository.CommandKind.IMMEDIATE, intentFingerprint=requestId,
                generalId=10, turnIdx=null, actionCode=command.type, ownerUserId=110,
                payloadJson=encodeCommandPayload(TurnDaemonCommandEnvelope(requestId, now.toString(), command)))))
        assertEquals(listOf(sendId, deleteId), jdbc.queryForList(
            "SELECT request_id FROM command_inbox WHERE world_id=? ORDER BY created_at,request_id",
            String::class.java, id.value), "real durable order must be send then delete")
        val people = listOf(10, 20).map { generalId -> TurnGeneral(
            id=generalId, userId=(generalId+100).toString(), name="person-$generalId", nationId=1, cityId=1,
            troopId=0, stats=GeneralStats(50,50,50), experience=0, dedication=0, officerLevel=1, turnTime=future) }
        val world = InMemoryTurnWorld(WorldSnapshot(TurnWorldState(id.value,200,1,3600,now,
            writerEpoch=1, meta=mapOf("startYear" to 200, "startTime" to now.toString(),
                "maxGeneralId" to 20, "maxNationId" to 1),
            config=mapOf("mapName" to "han-world-v3", "worldFormat" to "GENERAL_RETAINER_CAMPAIGN")),
            people, listOf(City(1,"fixture-county",1,1)), listOf(Nation(1,"fixture-nation","#000000",level=1)), worldId=id))
        val messageIds = AtomicInteger(900)
        val recorder = ChangeRecorder(messageIdAllocator=messageIds::incrementAndGet)
        val registry = EngineGeneralActionPipelineBuilder(world,200).registryFor(people.first())
        val handler = ReservedTurnHandler(world, registry, "00", 200, recorder=recorder)
        val lifecycle = TurnDaemonLifecycle(world, handler, reservedActionOf = { generalId ->
            opensamguk.infra.persistence.ReservedTurnRepository(named).readReserved(id, generalId, 0)
        })
        val redis = mock(StringRedisTemplate::class.java)
        val stream = object : RedisCommandStream(redis,"bug1-batch",id,startId="0") {
            override fun readWakeEnvelopes(blockMs: Long) = emptyList<RedisCommandStream.WakeEnvelope>()
        }
        val publisher = object : RealtimePublisher(redis,"bug1-batch",id) {
            override fun publishCommandResultPayload(requestId: String, payloadJson: String) = Unit
        }
        val flush = JdbcFlushExecutor(named, TransactionTemplate(DataSourceTransactionManager(source)))
        val service = TurnRunService(world,stream,lifecycle,handler,flush,publisher,
            boardPostRepository=mock(BoardPostRepository::class.java), commandInboxRepository=inbox,
            contactReader=ContactReader(NamedParameterJdbcTemplate(fault)))
        return Fixture(id,now,service,inbox,fault,sendId,deleteId)
    }

    /** Advances only the inbox's explicit lease clock; all claim SQL remains production SQL. */
    private class LeaseClockInbox(jdbc: NamedParameterJdbcTemplate) : CommandInboxRepository(jdbc) {
        var afterLeaseExpiry = false
        override fun claimPendingForExecution(worldId: WorldId, now: Instant, limit: Int, lease: Duration): List<CommandInboxRepository.ClaimedCommand> =
            super.claimPendingForExecution(worldId,
                if (afterLeaseExpiry) now.plus(CommandInboxRepository.DEFAULT_CLAIM_LEASE).plusSeconds(1) else now, limit, lease)
    }

    /** Throws once at JDBC prepareStatement for the actual scoped ContactReader query. */
    private class OneReadFailure(private val delegate: DataSource, armed: Boolean) : DataSource by delegate {
        private val remaining = AtomicInteger(if (armed) 1 else 0)
        val failures = AtomicInteger()
        override fun getConnection(): Connection = wrap(delegate.connection)
        override fun getConnection(username: String, password: String): Connection = wrap(delegate.getConnection(username,password))
        private fun wrap(connection: Connection): Connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            val sql = args?.firstOrNull() as? String
            if (method.name == "prepareStatement" && sql?.contains("FROM message") == true &&
                sql.contains("world_id") && remaining.getAndSet(0) == 1) {
                failures.incrementAndGet()
                throw SQLException("synthetic one-shot scoped message read failure", "08006")
            }
            try { method.invoke(connection, *(args ?: emptyArray())) }
            catch (error: InvocationTargetException) { throw error.targetException }
        } as Connection
    }
}

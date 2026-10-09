package opensamguk.gameapi.reserve

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.infra.persistence.CommandResultRepository
import opensamguk.infra.persistence.ReservationExecutionFence
import opensamguk.infra.persistence.ReservedTurnRepository
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** No operating connection, optional Docker skip or sleep-based race assertion. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReservationCancelIT {
    private val pg = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    private lateinit var jdbc: JdbcTemplate
    private lateinit var params: NamedParameterJdbcTemplate
    private lateinit var fence: ReservationExecutionFence
    private lateinit var service: ReservationCancelService
    private lateinit var reader: ReservationSlotReader
    private val mapper = ObjectMapper()
    private val threads = Executors.newFixedThreadPool(3)
    private val world = WorldId(1)

    @BeforeAll fun start() {
        pg.start()
        val source = DriverManagerDataSource(pg.jdbcUrl, pg.username, pg.password)
        jdbc = JdbcTemplate(source)
        params = NamedParameterJdbcTemplate(source)
        javaClass.getResource("/reservation-cancel-schema.sql")!!.readText().split(';')
            .filter { it.isNotBlank() }.forEach(jdbc::execute)
        jdbc.execute(ReservedTurnRepository::class.java.getResource("/db/migration/V77__general_turn_reservation_revision.sql")!!.readText())
        jdbc.update("INSERT INTO world_state(id,config) VALUES(1,'{\"worldFormat\":\"GENERAL_RETAINER_CAMPAIGN\"}'),(2,'{\"worldFormat\":\"GENERAL_RETAINER_CAMPAIGN\"}')")
        jdbc.update("INSERT INTO general(world_id,id,user_id) VALUES(1,10,'42'),(1,11,'43'),(2,10,'42')")
        fence = ReservationExecutionFence(params)
        reader = ReservationSlotReader(params, mapper, GameApiProcessWorld(1))
        service = freshService()
    }

    private fun freshService(id: Int = 1) = ReservationCancelService(ReservationCancelRepository(params,
        CommandInboxRepository(params), CommandResultRepository(params), mapper, GameApiProcessWorld(id)), GameApiProcessWorld(id))

    @AfterAll fun stop() { threads.shutdownNow(); pg.stop() }
    @AfterEach fun clearPrincipal() { SecurityContextHolder.clearContext() }
    @BeforeEach fun reset() {
        listOf("general_turn", "command_inbox", "command_result", "command_outbox").forEach { jdbc.execute("TRUNCATE $it") }
        jdbc.update("UPDATE general SET user_id=CASE WHEN id=10 THEN '42' ELSE '43' END,npc_state=0,meta='{}'")
        jdbc.update("UPDATE world_state SET config='{\"worldFormat\":\"GENERAL_RETAINER_CAMPAIGN\"}',meta='{}'")
        reserve(0); reserve(1)
    }

    private fun reserve(slot: Int, action: String = "action.scout") = ReservedTurnRepository(params)
        .reserve(world, 10, slot, action, "{\"city\":7}", "견문")
    private fun revision(slot: Int = 0) = reader.read(10).single { it.turnIdx == slot }.revision
    private fun cancel(key: String = UUID.randomUUID().toString(), rev: String = revision(), slot: Int = 0,
        owner: Long = 42, actor: Int = 10) = service.cancel(owner, actor, slot, rev, key)
    private fun contents() = listOf("general_turn", "command_inbox", "command_result", "command_outbox")
        .associateWith { table -> jdbc.queryForList("SELECT to_jsonb(t)::text AS row FROM $table t ORDER BY to_jsonb(t)::text") }
    private fun rejected(code: String, block: () -> Unit) {
        val before = contents()
        assertEquals(code, assertFailsWith<ReservationCancelRejected> { block() }.code)
        assertEquals(before, contents(), "Rejection must preserve complete row contents")
    }
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(10, TimeUnit.SECONDS))

    @Test fun `exact cancellation is absent and cold retry preserves replacement and original reservation result`() {
        val key = UUID.randomUUID().toString(); val rev = revision(); val other = revision(1)
        val result = cancel(key, rev)
        assertTrue(result.result.slotEmpty == true)
        assertEquals(rev, result.result.reservationRevision)
        assertEquals(listOf(1), reader.read(10).map { it.turnIdx })
        assertEquals(other, revision(1))
        reserve(0); val replacement = revision()
        val beforeReplay = contents()
        assertEquals(result, freshService().cancel(42, 10, 0, rev, key))
        assertEquals(beforeReplay, contents())
        assertEquals(replacement, revision())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM command_result", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM command_outbox", Int::class.java))
        assertEquals(listOf(1), jdbc.queryForList("SELECT result_seq FROM command_result", Int::class.java))
        rejected("REVISION_MISMATCH") { cancel(rev = rev) }
    }

    @Test fun `cancellation never writes original reservation terminal sequence or changes its receipt`() {
        val original = "original-reservation"
        fence.transactions.execute {
            CommandInboxRepository(params).insertAccepted(CommandInboxRepository.AcceptedCommand(world, original,
                commandKind = CommandInboxRepository.CommandKind.RESERVED_TURN, intentFingerprint = "synthetic-original",
                generalId = 10, turnIdx = 0, actionCode = "action.scout", payloadJson = "{}", ownerUserId = 42))
            ReservedTurnRepository(params).reserve(world, 10, 0, "action.scout", "{}", "견문", original)
            CommandResultRepository(params).insertTerminalResult(world, CommandTerminalResultFactory.acceptedRow(world,
                original, java.time.Instant.now(), "reservationAccepted", CommandInboxRepository.CommandKind.RESERVED_TURN,
                "action.scout", 10, 0))
        }
        fun originalRows() = listOf("command_inbox", "command_result", "command_outbox").associateWith { table ->
            jdbc.queryForList("SELECT to_jsonb(t)::text AS row FROM $table t WHERE request_id=?", original) }
        val before = originalRows(); cancel()
        assertEquals(before, originalRows())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM command_result WHERE result_seq=2", Int::class.java))
    }

    @Test fun `wrong owner intent and non mutation key never disclose or change a receipt`() {
        val key = UUID.randomUUID().toString(); val rev = revision(); cancel(key, rev)
        rejected("IDEMPOTENCY_CONFLICT") { cancel(key, rev, owner = 43) }
        rejected("IDEMPOTENCY_CONFLICT") { cancel(key, revision(1), slot = 1) }
        jdbc.update("UPDATE command_inbox SET command_kind='RESERVED_TURN' WHERE request_id=?", key)
        rejected("IDEMPOTENCY_CONFLICT") { cancel(key, rev) }
        jdbc.update("UPDATE command_inbox SET command_kind='QUEUE_MUTATION',owner_user_id=null WHERE request_id=?", key)
        rejected("IDEMPOTENCY_CONFLICT") { cancel(key, rev) }
    }

    @Test fun `historical receipt survives ownership loss and exclusive execution fence`() {
        val key = UUID.randomUUID().toString(); val rev = revision(); val result = cancel(key, rev)
        jdbc.update("UPDATE general SET user_id='43' WHERE world_id=1 AND id=10")
        val locked = CountDownLatch(1); val release = CountDownLatch(1)
        val execution = threads.submit { fence.execute(world) { locked.countDown(); await(release) } }
        try { await(locked); assertEquals(result, cancel(key, rev)) } finally { release.countDown(); execution.get(10, TimeUnit.SECONDS) }
        rejected("NOT_OWNER") { cancel(rev = revision(1), slot = 1) }
    }

    @Test fun `daemon claim excludes all new world cancellations before snapshot and commit`() {
        val locked = CountDownLatch(1); val release = CountDownLatch(1)
        val execution = threads.submit { fence.execute(world) { locked.countDown(); await(release) } }
        try { await(locked); rejected("WORLD_EXECUTING") { cancel() } }
        finally { release.countDown(); execution.get(10, TimeUnit.SECONDS) }
        assertTrue(cancel().ok)
    }

    @Test fun `cancel shared fence excludes daemon snapshot until cancellation transaction commits`() {
        val entered = CountDownLatch(1); val locked = CountDownLatch(1)
        val future = fence.transactions.execute {
            assertTrue(fence.tryCancellation(world))
            cancel()
            threads.submit<List<ReservationSlotDto>> { entered.countDown(); fence.execute(world) { locked.countDown(); reader.read(10) } }
                .also { await(entered); assertEquals(1L, locked.count, "Daemon cannot claim under a live shared fence") }
        }!!
        val after = future.get(10, TimeUnit.SECONDS)
        assertEquals(listOf(1), after.map { it.turnIdx })
        assertEquals(0L, locked.count)
    }

    @Test fun `same key concurrent cancellation produces one immutable receipt`() {
        val rev = revision(); val key = UUID.randomUUID().toString(); val start = CountDownLatch(1)
        val calls = (1..2).map { threads.submit<ReservationCancelDto> { await(start); cancel(key, rev) } }
        start.countDown()
        assertEquals(calls[0].get(10, TimeUnit.SECONDS), calls[1].get(10, TimeUnit.SECONDS))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM command_result", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM command_outbox", Int::class.java))
    }

    @Test fun `different keys competing for one revision produce one success and one unchanged rejection`() {
        val rev = revision(); val start = CountDownLatch(1)
        val calls = (1..2).map { threads.submit<Result<ReservationCancelDto>> { await(start); runCatching { cancel(rev = rev) } } }
        start.countDown(); val outcomes = calls.map { it.get(10, TimeUnit.SECONDS) }
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals("REVISION_MISMATCH", (outcomes.single { it.isFailure }.exceptionOrNull() as ReservationCancelRejected).code)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM command_inbox", Int::class.java))
    }

    @Test fun `same payload replacement has different identity and old cancellation cannot remove it`() {
        val old = revision(); reserve(0); assertNotEquals(old, revision())
        rejected("REVISION_MISMATCH") { cancel(rev = old) }
        val next = revision(); cancel(rev = next); reserve(0); assertNotEquals(next, revision())
    }

    @Test fun `invalid profile owner npc slot and UUID preserve every table`() {
        rejected("INVALID_SLOT") { cancel(slot = 12) }
        rejected("INVALID_SLOT") { cancel(slot = -1) }
        rejected("INVALID_REVISION") { cancel(rev = "0-0-0-0-0") }
        rejected("INVALID_IDEMPOTENCY_KEY") { service.cancel(42, 10, 0, revision(), null) }
        rejected("UNAUTHORIZED") { service.cancel(null, 10, 0, revision(), UUID.randomUUID().toString()) }
        rejected("NOT_OWNER") { cancel(owner = 43) }
        rejected("NOT_OWNER") { cancel(actor = 99) }
        jdbc.update("UPDATE general SET npc_state=2 WHERE world_id=1 AND id=10")
        rejected("NOT_OWNER") { cancel() }
        jdbc.update("UPDATE general SET npc_state=0,meta='{\"retired\":true}' WHERE world_id=1 AND id=10")
        rejected("NOT_OWNER") { cancel() }
        jdbc.update("UPDATE general SET meta='{}' WHERE world_id=1 AND id=10")
        jdbc.update("UPDATE world_state SET config='{}' WHERE id=1")
        rejected("POLICY_UNAVAILABLE") { cancel() }
        jdbc.update("UPDATE world_state SET config='{\"worldFormat\":\"GENERAL_RETAINER_CAMPAIGN\"}',meta='{\"ruleProfile\":null}' WHERE id=1")
        rejected("POLICY_UNAVAILABLE") { cancel() }
    }

    @Test fun `foreign world is separate and slot eleven remains valid`() {
        rejected("REVISION_MISMATCH") { freshService(2).cancel(42, 10, 0, revision(), UUID.randomUUID().toString()) }
        reserve(11); assertTrue(cancel(rev = revision(11), slot = 11).ok)
    }

    @Test fun `owner mutation under actor lock is authoritative`() {
        val rev = revision(); val started = CountDownLatch(1)
        val request = fence.transactions.execute {
            jdbc.queryForMap("SELECT id FROM general WHERE world_id=1 AND id=10 FOR UPDATE")
            val pending = threads.submit<Result<ReservationCancelDto>> { started.countDown(); runCatching { cancel(rev = rev) } }
            await(started)
            jdbc.update("UPDATE general SET user_id='43' WHERE world_id=1 AND id=10")
            pending
        }!!
        val result = request.get(10, TimeUnit.SECONDS)
        assertEquals("NOT_OWNER", (result.exceptionOrNull() as ReservationCancelRejected).code)
        assertEquals(rev, revision())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM command_inbox", Int::class.java))
    }

    @Test fun `reservation writer first defeats stale cancellation without changing replacement`() {
        val old = revision(); val started = CountDownLatch(1)
        val request = fence.transactions.execute {
            jdbc.queryForMap("SELECT id FROM general WHERE world_id=1 AND id=10 FOR UPDATE")
            val pending = threads.submit<Result<ReservationCancelDto>> { started.countDown(); runCatching { cancel(rev = old) } }
            await(started); reserve(0, "action.work")
            pending
        }!!
        assertEquals("REVISION_MISMATCH", (request.get(10, TimeUnit.SECONDS).exceptionOrNull() as ReservationCancelRejected).code)
        assertEquals("action.work", reader.read(10).single { it.turnIdx == 0 }.actionCode)
        assertNotEquals(old, revision())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM command_inbox", Int::class.java))
    }

    @Test fun `cancellation first allows normal reservation writer to create a replacement after commit`() {
        val old = revision(); val started = CountDownLatch(1)
        val request = fence.transactions.execute {
            cancel(rev = old)
            threads.submit { started.countDown(); reserve(0, "action.work") }.also { await(started) }
        }!!
        request.get(10, TimeUnit.SECONDS)
        assertEquals("action.work", reader.read(10).single { it.turnIdx == 0 }.actionCode)
        assertNotEquals(old, revision())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM command_inbox", Int::class.java))
    }

    @Test fun `durable final result lookup restores lost success response and enforces submitter ACL`() {
        val key = UUID.randomUUID().toString(); val rev = revision(); cancel(key, rev)
        val redis = org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate::class.java)
        val values = org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations::class.java)
        @Suppress("UNCHECKED_CAST")
        org.mockito.Mockito.`when`(redis.opsForValue()).thenReturn(values as org.springframework.data.redis.core.ValueOperations<String,String>)
        val controller = opensamguk.gameapi.web.CommandController(org.mockito.Mockito.mock(), org.mockito.Mockito.mock(),
            org.mockito.Mockito.mock(), org.mockito.Mockito.mock(), org.mockito.Mockito.mock(), CommandResultRepository(params),
            CommandInboxRepository(params), redis, mapper, "cancel-fixture", GameApiProcessWorld(1), org.mockito.Mockito.mock())
        val restored = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(controller.commandResult(42L,key).body)
        assertEquals("RESOLVED", restored["status"].asText())
        assertEquals("reservationCancelled", restored["type"].asText())
        assertEquals(rev, restored["result"]["reservationRevision"].asText())
        assertTrue(restored["result"]["slotEmpty"].asBoolean())
        val foreign = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(controller.commandResult(43L,key).body)
        assertEquals("PENDING", foreign["status"].asText()); assertNull(foreign["result"])
        jdbc.update("UPDATE general SET user_id='43' WHERE world_id=1 AND id=10")
        assertEquals("RESOLVED", mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(controller.commandResult(42L,key).body)["status"].asText())
    }

    @Test fun `HTTP exact endpoint requires identity and reports non accepted admission errors`() {
        val mvc = MockMvcBuilders.standaloneSetup(ReservationCancelController(service))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver()).build()
        mvc.perform(delete("/api/reserved-commands")).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.accepted").value(false))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(42L, null, emptyList())
        mvc.perform(delete("/api/reserved-commands").param("generalId", "10").param("turnIdx", "0")
            .param("revision", revision()).header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("RESOLVED"))
            .andExpect(jsonPath("$.result.slotEmpty").value(true))
        mvc.perform(delete("/api/reserved-commands").param("generalId", "10").param("turnIdx", "0"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.receiptRecorded").value(false))
    }
}

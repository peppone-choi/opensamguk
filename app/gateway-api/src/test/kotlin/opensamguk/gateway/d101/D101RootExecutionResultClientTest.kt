package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.node.ObjectNode
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.infra.*
import opensamguk.gateway.d101.security.D101PurposeAuthority
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class D101RootExecutionResultClientTest {
    private val f = D101Fixture()
    private val origin = URI("http://127.0.0.1:9000")
    private val dispatch = D101DispatchIntentCandidate(2, "1".repeat(64), "2".repeat(64), "3".repeat(64))
    private val intentWire = f.mapper.writeValueAsBytes(f.intentTree())
    private val prepare = f.prepareBody()
    private val execution = D101Execution(
        f.codec.decode(intentWire, D101Fixture.hash(intentWire)), intentWire, prepare, D101Fixture.hash(prepare),
        D101ExecutionState.DISPATCH_INTENT, D101ExecutionState.DISPATCH_INTENT, 2, dispatch, null, null, null,
        Instant.ofEpochSecond(f.now - 20), Instant.ofEpochSecond(f.now - 10),
    )

    private fun result(status: String = "succeeded"): ObjectNode = f.mapper.valueToTree(linkedMapOf<String, Any?>(
        "schemaVersion" to 1, "serverId" to "pep", "worldId" to 1, "operationId" to f.operation,
        "kind" to "reset", "status" to status, "generation" to 0, "scenarioCode" to "scenario_3190",
        "serverName" to "빼섭", "approvalIntentSha256" to execution.intent.sha256,
        "approvalPlanSha256" to dispatch.approvalPlanSha256,
        "executionReceiptSha256" to dispatch.executionReceiptSha256,
        "targetFingerprint" to execution.intent.targetFingerprint,
        "rootRequestFingerprint" to dispatch.rootRequestFingerprint,
        "gatewayPayloadSha256" to execution.gatewayPayloadSha256,
        "verifyingRevision" to "2", "appSourceSha" to f.app,
        "imageDigests" to f.pins,
        "acceptedAtUtc" to Instant.ofEpochSecond(f.now - 8).toString(),
        "completedAtUtc" to Instant.ofEpochSecond(f.now - 4).toString(),
        "executionJournalSha256" to if (status == "succeeded") "4".repeat(64) else null,
        "actualRuntimeReceiptSha256" to if (status == "succeeded") "5".repeat(64) else null,
        "failureCode" to if (status == "succeeded") null else "WORKER_FAILED",
    ))

    private fun read(tree: ObjectNode, signatureValid: Boolean = true,
        expectedSha: String? = null): D101RootExecutionResult {
        val wire = f.mapper.writeValueAsBytes(tree)
        val sha = expectedSha ?: D101Fixture.hash(wire)
        val signed = if (signatureValid) f.sign(D101RootExecutionResultClient.DOMAIN + wire) else ByteArray(64)
        val header = "rfc8032-fixture.${D101Fixture.b64(signed)}"
        val client = D101RootExecutionResultClient(origin, { "fixture-token" }, f.authority(), f.clock, f.mapper,
            D101RootResultTransport { uri, token, _ ->
                assertEquals("Bearer fixture-token", "Bearer $token")
                assertEquals("/operations/${f.operation}/execution-result/$sha", uri.rawPath)
                assertNull(uri.rawQuery)
                assertEquals(origin.scheme, uri.scheme)
                assertEquals(origin.host, uri.host)
                D101RootResultHttpResponse(200, wire, listOf(header))
            })
        return client.readVerified(execution, sha)
    }

    @Test
    fun `signed exact result binds stored dispatch and preserves original bytes`() {
        val tree = result()
        val verified = read(tree)
        assertEquals(D101RootExecutionResult.Status.SUCCEEDED, verified.status)
        assertEquals(dispatch.rootRequestFingerprint, verified.rootRequestFingerprint)
        assertEquals(f.pins, verified.imageDigests)
        val original = verified.originalBytes()
        assertEquals(D101Fixture.hash(original), verified.rawSha256)
        original[0] = 0
        assertNotEquals(0, verified.originalBytes()[0].toInt())
        assertEquals(0x0a.toByte(), D101RootExecutionResultClient.DOMAIN.last())
        assertNotEquals("OPENSAMGUK-D101-GRANT-V1\n", String(D101RootExecutionResultClient.DOMAIN))
    }

    @Test
    fun `failed terminal remains failure with nullable runtime evidence`() {
        val verified = read(result("failed"))
        assertEquals(D101RootExecutionResult.Status.FAILED, verified.status)
        assertEquals(D101RootExecutionResult.FailureCode.WORKER_FAILED, verified.failureCode)
        assertNull(verified.executionJournalSha256)
        assertNull(verified.actualRuntimeReceiptSha256)
    }

    @Test
    fun `wrong raw sha and wrong signature fail closed before result use`() {
        assertFailsWith<D101ObservationUnavailable> { read(result(), expectedSha = "9".repeat(64)) }
        assertFailsWith<D101ObservationUnavailable> { read(result(), signatureValid = false) }
    }

    @Test
    fun `signed wrong operation source plan revision and pins fail closed`() {
        val changes: List<(ObjectNode) -> Unit> = listOf(
            { it.put("operationId", "b".repeat(32)) },
            { it.put("approvalIntentSha256", "9".repeat(64)) },
            { it.put("approvalPlanSha256", "9".repeat(64)) },
            { it.put("executionReceiptSha256", "9".repeat(64)) },
            { it.put("rootRequestFingerprint", "9".repeat(64)) },
            { it.put("gatewayPayloadSha256", "9".repeat(64)) },
            { it.put("targetFingerprint", "9".repeat(64)) },
            { it.put("verifyingRevision", "3") },
            { it.put("appSourceSha", "9".repeat(40)) },
            { (it["imageDigests"] as ObjectNode).put("game-api", "sha256:" + "9".repeat(64)) },
        )
        changes.forEach { change ->
            val tree = result().also(change)
            assertFailsWith<D101ObservationUnavailable> { read(tree) }
        }
    }

    @Test
    fun `schema type null status and clock violations fail closed`() {
        val changes: List<(ObjectNode) -> Unit> = listOf(
            { it.put("schemaVersion", 1.0) }, { it.put("generation", 1) },
            { it.put("status", "SUCCEEDED") }, { it.put("status", "unknown") },
            { it.putNull("executionJournalSha256") }, { it.put("failureCode", "PHASE_FAILED") },
            { it.put("completedAtUtc", Instant.ofEpochSecond(f.now + 1).toString()) },
            { it.put("completedAtUtc", Instant.ofEpochSecond(f.now - 9).toString()) },
            { it.put("acceptedAtUtc", "2026-10-06T08:59:52+00:00") },
            { it.put("extra", "unexpected") }, { it.remove("failureCode") },
        )
        changes.forEach { change ->
            assertFailsWith<D101ObservationUnavailable> { read(result().also(change)) }
        }
    }

    @Test
    fun `oversize redirect missing proof and unavailable authority never become result`() {
        val wire = f.mapper.writeValueAsBytes(result())
        val sha = D101Fixture.hash(wire)
        fun client(status: Int, body: ByteArray, proofs: List<String>, authority: D101PurposeAuthority = f.authority()) =
            D101RootExecutionResultClient(origin, { "fixture-token" }, authority, f.clock, f.mapper,
                D101RootResultTransport { _, _, _ -> D101RootResultHttpResponse(status, body, proofs) })
        assertFailsWith<D101ObservationUnavailable> {
            client(200, ByteArray(16 * 1024 + 1), listOf("invalid")).readVerified(execution, sha)
        }
        assertFailsWith<D101ObservationUnavailable> { client(302, wire, emptyList()).readVerified(execution, sha) }
        assertFailsWith<D101ObservationUnavailable> { client(200, wire, emptyList()).readVerified(execution, sha) }
        assertFailsWith<D101ObservationUnavailable> { client(200, wire, listOf("one", "two")).readVerified(execution, sha) }
        val calls = AtomicInteger()
        val unavailable = D101RootExecutionResultClient(origin, { "fixture-token" }, clock = f.clock, mapper = f.mapper,
            transport = D101RootResultTransport { _, _, _ -> calls.incrementAndGet(); error("must not call") })
        assertFailsWith<D101ObservationUnavailable> { unavailable.readVerified(execution, sha) }
        assertEquals(0, calls.get())
    }

    @Test
    fun `a stored different Root result receipt cannot be replaced by another signed result`() {
        val alreadyBound = D101Execution(execution.intent, intentWire, prepare, execution.gatewayPayloadSha256,
            D101ExecutionState.REMOTE_SUCCEEDED, D101ExecutionState.REMOTE_SUCCEEDED, 2, dispatch,
            "8".repeat(64), null, null, execution.createdAt, execution.updatedAt)
        val wire = f.mapper.writeValueAsBytes(result())
        val calls = AtomicInteger()
        val client = D101RootExecutionResultClient(origin, { "fixture-token" }, f.authority(), f.clock, f.mapper,
            D101RootResultTransport { _, _, _ -> calls.incrementAndGet(); error("must not call") })
        assertFailsWith<D101ObservationUnavailable> { client.readVerified(alreadyBound, D101Fixture.hash(wire)) }
        assertEquals(0, calls.get())
    }

    @Test
    fun `only two simultaneous requests run and the third has no queue`() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val threadFailure = AtomicReference<Throwable?>()
        val wire = f.mapper.writeValueAsBytes(result())
        val sha = D101Fixture.hash(wire)
        val client = D101RootExecutionResultClient(origin, { "fixture-token" }, f.authority(), f.clock, f.mapper,
            D101RootResultTransport { _, _, _ ->
                calls.incrementAndGet()
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                D101RootResultHttpResponse(503, byteArrayOf(), emptyList())
            })
        val workers = List(2) { Thread {
            try { assertFailsWith<D101ObservationUnavailable> { client.readVerified(execution, sha) } }
            catch (failure: Throwable) { threadFailure.compareAndSet(null, failure) }
        } }
        workers.forEach(Thread::start)
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFailsWith<D101ObservationUnavailable> { client.readVerified(execution, sha) }
            assertEquals(2, calls.get())
        } finally {
            release.countDown()
            workers.forEach { it.join(1500) }
        }
        assertNull(threadFailure.get())
        assertTrue(workers.none(Thread::isAlive))
    }

    @Test
    fun `a stalled source returns unavailable within the two second budget`() {
        val wire = f.mapper.writeValueAsBytes(result())
        val sha = D101Fixture.hash(wire)
        val client = D101RootExecutionResultClient(origin, { "fixture-token" }, f.authority(), f.clock, f.mapper,
            D101RootResultTransport { _, _, _ ->
                Thread.sleep(5_000)
                D101RootResultHttpResponse(200, wire, emptyList())
            })
        val start = System.nanoTime()
        assertFailsWith<D101ObservationUnavailable> { client.readVerified(execution, sha) }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_500)
    }

    @Test
    fun `preinterrupted callers cannot exhaust slots before workers start`() {
        val wire = f.mapper.writeValueAsBytes(result())
        val sha = D101Fixture.hash(wire)
        val header = "rfc8032-fixture.${D101Fixture.b64(f.sign(D101RootExecutionResultClient.DOMAIN + wire))}"
        val gates = List(2) { CountDownLatch(1) }
        val finished = List(2) { CountDownLatch(1) }
        val created = AtomicInteger()
        val client = D101RootExecutionResultClient(origin, { "fixture-token" }, f.authority(), f.clock, f.mapper,
            D101RootResultTransport { _, _, _ -> D101RootResultHttpResponse(200, wire, listOf(header)) },
            workerThread = { task ->
                val index = created.getAndIncrement()
                Thread({
                    if (index < gates.size) {
                        while (gates[index].count > 0) {
                            try { gates[index].await() } catch (_: InterruptedException) { /* Release the test gate first. */ }
                        }
                    }
                    try { task.run() }
                    finally { if (index < finished.size) finished[index].countDown() }
                }, "d101-result-test-worker").apply { isDaemon = true }
            })
        try {
            repeat(2) {
                val failure = AtomicReference<Throwable?>()
                val caller = Thread {
                    Thread.currentThread().interrupt()
                    try { assertFailsWith<D101ObservationUnavailable> { client.readVerified(execution, sha) } }
                    catch (error: Throwable) { failure.set(error) }
                }
                caller.start()
                caller.join(1_500)
                assertFalse(caller.isAlive)
                assertNull(failure.get())
                gates[it].countDown()
                assertTrue(finished[it].await(1, TimeUnit.SECONDS))
            }
            assertEquals(D101RootExecutionResult.Status.SUCCEEDED, client.readVerified(execution, sha).status)
        } finally {
            gates.forEach(CountDownLatch::countDown)
        }
    }

    @Test
    fun `worker start failure returns its slot`() {
        val wire = f.mapper.writeValueAsBytes(result())
        val sha = D101Fixture.hash(wire)
        val header = "rfc8032-fixture.${D101Fixture.b64(f.sign(D101RootExecutionResultClient.DOMAIN + wire))}"
        val starts = AtomicInteger()
        val client = D101RootExecutionResultClient(origin, { "fixture-token" }, f.authority(), f.clock, f.mapper,
            D101RootResultTransport { _, _, _ -> D101RootResultHttpResponse(200, wire, listOf(header)) },
            workerThread = { task ->
                object : Thread(task, "d101-result-test-worker") {
                    override fun start() {
                        if (starts.getAndIncrement() < 2) throw IllegalThreadStateException("start rejected")
                        super.start()
                    }
                }.apply { isDaemon = true }
            })
        repeat(2) { assertFailsWith<D101ObservationUnavailable> { client.readVerified(execution, sha) } }
        assertEquals(D101RootExecutionResult.Status.SUCCEEDED, client.readVerified(execution, sha).status)
    }
}

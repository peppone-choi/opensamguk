package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpServer
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.infra.*
import opensamguk.gateway.d101.security.D101PurposeAuthority
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class D101RootPreparedProofClientTest {
    private val f = D101Fixture()
    private val origin = URI("http://127.0.0.1:9000")
    private val candidate = D101DispatchIntentCandidate(2, "1".repeat(64), "2".repeat(64), "3".repeat(64))
    private val intentWire = f.mapper.writeValueAsBytes(f.intentTree())
    private val prepare = f.prepareBody()
    private val execution = D101Execution(
        f.codec.decode(intentWire, D101Fixture.hash(intentWire)), intentWire, prepare, D101Fixture.hash(prepare),
        D101ExecutionState.PREPARED, D101ExecutionState.PREPARED, 2, null, null, null, null,
        Instant.ofEpochSecond(f.now - 1), Instant.ofEpochSecond(f.now - 1),
    )

    private fun proof(): ObjectNode = f.mapper.valueToTree(linkedMapOf<String, Any>(
        "schemaVersion" to 1, "serverId" to "pep", "worldId" to 1, "operationId" to f.operation,
        "phase" to "prepared", "approvalIntentSha256" to execution.intent.sha256,
        "approvalPlanSha256" to candidate.approvalPlanSha256, "executionReceiptSha256" to candidate.executionReceiptSha256,
        "targetFingerprint" to execution.intent.targetFingerprint, "rootRequestFingerprint" to candidate.rootRequestFingerprint,
        "gatewayPayloadSha256" to execution.gatewayPayloadSha256, "initialPublicRevision" to "1", "verifyingRevision" to "2",
        "appSourceSha" to f.app, "imageDigests" to f.pins,
        "acceptedAtUtc" to Instant.ofEpochSecond(f.now - 1).toString(), "preparedAtUtc" to Instant.ofEpochSecond(f.now).toString(),
        "preparedJournalSha256" to "4".repeat(64), "destructiveCutoffUnix" to execution.intent.destructiveCutoffUnix,
        "recoveryDeadlineUnix" to execution.intent.recoveryDeadlineUnix,
    ))

    private fun response(wire: ByteArray, domain: ByteArray = D101RootPreparedProofClient.DOMAIN) =
        D101RootPreparedResponse(200, wire, listOf(D101Fixture.hash(wire)),
            listOf("rfc8032-fixture.${D101Fixture.b64(f.sign(domain + wire))}"))

    private fun client(tree: ObjectNode = proof(), change: (D101RootPreparedResponse) -> D101RootPreparedResponse = { it }): D101RootPreparedProofClient {
        val wire = f.mapper.writeValueAsBytes(tree)
        return D101RootPreparedProofClient(origin, { "fixture-root-token" }, f.authority(), f.clock, f.mapper,
            D101RootPreparedTransport { uri, token, _ ->
                assertEquals("fixture-root-token", token)
                assertEquals("/operations/${f.operation}/prepared-proof/${candidate.approvalPlanSha256}/${candidate.executionReceiptSha256}", uri.rawPath)
                assertNull(uri.rawQuery)
                assertEquals(origin.host, uri.host)
                change(response(wire))
            })
    }

    @Test
    fun `signed first preparation and dispatch projection retain source times and bytes`() {
        val client = client()
        val proof = client.readVerified(execution, candidate)
        assertEquals(Instant.ofEpochSecond(f.now - 1), proof.acceptedAtUtc)
        assertEquals(Instant.ofEpochSecond(f.now), proof.preparedAtUtc)
        val copy = proof.originalBytes(); copy[0] = 0
        assertNotEquals(0, proof.originalBytes()[0].toInt())
        assertEquals(D101Fixture.hash(proof.originalBytes()), proof.rawSha256)
        val verified = D101VerifiedDispatchAuthorityAdapter(client, f.clock).readVerified(execution, candidate)
        assertEquals(f.now, verified.observedAtUnix)
        assertEquals(f.now + 30, verified.expiresAtUnix)
        verified.requireMatches(execution, candidate)
        f.clock.epoch += 30
        assertFailsWith<D101ObservationUnavailable> { verified.requireMatches(execution, candidate) }
        assertEquals(0x0a.toByte(), D101RootPreparedProofClient.DOMAIN.last())
    }

    @Test
    fun `signed mismatched state source references clocks and cutoffs are refused`() {
        val changes: List<(ObjectNode) -> Unit> = listOf(
            { it.put("operationId", "b".repeat(32)) }, { it.put("phase", "before-down") },
            { it.put("approvalIntentSha256", "9".repeat(64)) }, { it.put("approvalPlanSha256", "9".repeat(64)) },
            { it.put("executionReceiptSha256", "9".repeat(64)) }, { it.put("rootRequestFingerprint", "9".repeat(64)) },
            { it.put("targetFingerprint", "9".repeat(64)) }, { it.put("gatewayPayloadSha256", "9".repeat(64)) },
            { it.put("initialPublicRevision", "2") }, { it.put("verifyingRevision", "3") },
            { it.put("appSourceSha", "9".repeat(40)) },
            { (it["imageDigests"] as ObjectNode).put("game-api", "sha256:"+"9".repeat(64)) },
            { it.put("acceptedAtUtc", Instant.ofEpochSecond(f.now - 2).toString()) },
            { it.put("preparedAtUtc", Instant.ofEpochSecond(f.now + 1).toString()) },
            { it.put("destructiveCutoffUnix", execution.intent.destructiveCutoffUnix + 1) },
            { it.put("recoveryDeadlineUnix", execution.intent.recoveryDeadlineUnix + 1) },
            { it.putNull("worldId") }, { it.put("extra", "forbidden") },
        )
        changes.forEach { change -> assertFailsWith<D101ObservationUnavailable> { client(proof().also(change)).readVerified(execution, candidate) } }
        val expired = proof(); f.clock.epoch += 30
        assertFailsWith<D101ObservationUnavailable> { client(expired).readVerified(execution, candidate) }
    }

    @Test
    fun `original SHA signature domains and duplicate headers precede dispatch`() {
        val changes: List<(D101RootPreparedResponse) -> D101RootPreparedResponse> = listOf(
            { D101RootPreparedResponse(200, it.body(), listOf("9".repeat(64)), it.proofHeaders) },
            { D101RootPreparedResponse(200, it.body(), it.shaHeaders + it.shaHeaders, it.proofHeaders) },
            { D101RootPreparedResponse(200, it.body(), it.shaHeaders, it.proofHeaders + it.proofHeaders) },
            { D101RootPreparedResponse(200, it.body(), it.shaHeaders, listOf("rfc8032-fixture.${D101Fixture.b64(ByteArray(64))}")) },
            { response(it.body(), "OPENSAMGUK-D101-RESULT-V1\n".toByteArray()) },
            { D101RootPreparedResponse(503, it.body(), it.shaHeaders, it.proofHeaders) },
            { response(it.body() + "{}".toByteArray()) },
            { response(String(it.body()).replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1").toByteArray()) },
            { response(ByteArray(D101RootPreparedProof.MAX_BYTES+1)) },
        )
        changes.forEach { change -> assertFailsWith<D101ObservationUnavailable> { client(change = change).readVerified(execution, candidate) } }
        assertFailsWith<D101ObservationUnavailable> {
            D101RootPreparedProofClient(origin, { "fixture" }, D101PurposeAuthority { throw D101PurposeAuthorityUnavailable() }, f.clock, f.mapper,
                D101RootPreparedTransport { _, _, _ -> fail("unavailable authority performed HTTP") }).readVerified(execution, candidate)
        }
    }

    @Test
    fun `actual HTTP uses bearer private GET and refuses redirect and oversized body`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        val redirected = AtomicInteger()
        val mode = AtomicReference("valid")
        val wire = f.mapper.writeValueAsBytes(proof())
        server.createContext("/operations/") { exchange ->
            requests.incrementAndGet()
            assertEquals("GET", exchange.requestMethod)
            assertEquals(listOf("Bearer fixture-root-token"), exchange.requestHeaders["Authorization"])
            assertNull(exchange.requestURI.rawQuery)
            assertEquals(-1, exchange.requestBody.read())
            if (mode.get() == "redirect") {
                exchange.responseHeaders.set("Location", "/redirected")
                exchange.sendResponseHeaders(302, -1)
            } else {
                val body = if (mode.get() == "oversize") ByteArray(D101RootPreparedProof.MAX_BYTES+1) else wire
                exchange.responseHeaders.set("X-D101-Prepared-Sha256", D101Fixture.hash(body))
                exchange.responseHeaders.set("X-D101-Prepared-Proof", response(body).proofHeaders.single())
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.createContext("/redirected") { exchange -> redirected.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close() }
        server.start()
        try {
            val client = D101RootPreparedProofClient(URI("http://127.0.0.1:${server.address.port}"), { "fixture-root-token" }, f.authority(), f.clock, f.mapper)
            client.readVerified(execution, candidate)
            mode.set("redirect")
            assertFailsWith<D101ObservationUnavailable> { client.readVerified(execution, candidate) }
            mode.set("oversize")
            assertFailsWith<D101ObservationUnavailable> { client.readVerified(execution, candidate) }
            assertEquals(3, requests.get()); assertEquals(0, redirected.get())
        } finally { server.stop(0) }
    }

    @Test
    fun `untrusted origin credential and worker failures never consume reader capacity`() {
        listOf("https://example.com:9000", "http://127.0.0.1:9000?x=1", "http://u:p@127.0.0.1:9000",
            "http://127.0.0.1:9000/path", "http://127.0.0.1").forEach { value ->
            assertFailsWith<D101ObservationUnavailable> { D101RootPreparedProofClient(URI(value), { "fixture" }) }
        }
        listOf("", "with space", "control\u0001", "x".repeat(4097)).forEach { token ->
            assertFailsWith<D101ObservationUnavailable> {
                D101RootPreparedProofClient(origin, { token }, f.authority(), f.clock, f.mapper,
                    D101RootPreparedTransport { _, _, _ -> fail("invalid credential performed HTTP") }).readVerified(execution, candidate)
            }
        }
        val builders: List<(Runnable) -> Thread> = listOf(
            { throw IllegalStateException("synthetic thread construction failure") },
            { action -> object : Thread(action) { override fun start() { throw IllegalStateException("synthetic thread start failure") } } },
        )
        builders.forEach { builder ->
            repeat(3) {
                assertFailsWith<D101ObservationUnavailable> {
                    D101RootPreparedProofClient(origin, { "fixture" }, f.authority(), f.clock, f.mapper,
                        D101RootPreparedTransport { _, _, _ -> fail("unstarted reader performed HTTP") }, builder)
                        .readVerified(execution, candidate)
                }
            }
        }
        client().readVerified(execution, candidate)
    }

    @Test
    fun `timed out uncooperative readers keep both slots until actual exit`() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(2)
        val readers = ConcurrentLinkedQueue<Thread>()
        val failure = AtomicReference<Throwable?>()
        val wire = f.mapper.writeValueAsBytes(proof())
        val client = D101RootPreparedProofClient(origin, { "fixture" }, f.authority(), f.clock, f.mapper,
            D101RootPreparedTransport { _, _, _ ->
                entered.countDown()
                while (true) { try { release.await(); break } catch (_: InterruptedException) { /* emulate blocked source */ } }
                response(wire)
            }, workerThread = { action -> Thread(action).apply { isDaemon = true; readers.add(this) } })
        val callers = List(2) { Thread {
            try { assertFailsWith<D101ObservationUnavailable> { client.readVerified(execution, candidate) } }
            catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { returned.countDown() }
        }.apply { start() } }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(returned.await(3, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            assertFailsWith<D101ObservationUnavailable> { client.readVerified(execution, candidate) }
        } finally {
            release.countDown()
            callers.forEach { it.join(1000) }
            readers.forEach { it.join(1000); assertFalse(it.isAlive) }
        }
    }
}

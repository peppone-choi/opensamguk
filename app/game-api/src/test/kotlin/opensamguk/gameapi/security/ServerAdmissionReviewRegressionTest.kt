package opensamguk.gameapi.security

import com.sun.net.httpserver.HttpServer
import opensamguk.gameapi.sse.RealtimeRelayController
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpConnectTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

class ServerAdmissionReviewRegressionTest {
    private val budget = ServerAdmissionDraftBudget.totalNanos
    private fun body(state: String = "PUBLIC", revision: String = "10") =
        """{"serverId":"pep","state":"$state","revision":"$revision","sourceStatus":"KNOWN"}"""
    private fun awaitWaiting(thread: Thread) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (thread.state != Thread.State.TIMED_WAITING && thread.isAlive && System.nanoTime() < until) LockSupport.parkNanos(100_000)
        assertEquals(Thread.State.TIMED_WAITING, thread.state)
    }

    /** 대기 1.9초 뒤 permit을 받는 동일 요청의 마지막 0.1초를 소비한다. */
    private fun afterQueue(response: (AtomicLong) -> ServerAdmissionHttpResponse): ServerAdmissionRead {
        val now = AtomicLong(); val calls = AtomicInteger()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val source = GatewayServerAdmissionSource("http://localhost", "pep", "test-only-service", ServerAdmissionTransport { _, _, started, total ->
            assertEquals(0L, started); assertEquals(budget, total)
            if (calls.incrementAndGet() == 1) {
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
                ServerAdmissionHttpResponse(200, body())
            } else if (calls.get() == 2) response(now) else ServerAdmissionHttpResponse(200, body())
        }, now::get, maxConcurrent = 1)
        val result = AtomicReference<ServerAdmissionRead>(); val failure = AtomicReference<Throwable>()
        val active = Thread { try { source.readFresh() } catch (e: Throwable) { failure.set(e) } }
        val queued = Thread { try { result.set(source.readFresh()) } catch (e: Throwable) { failure.set(e) } }
        try {
            active.start(); assertTrue(entered.await(1, TimeUnit.SECONDS)); queued.start(); awaitWaiting(queued)
            now.set(TimeUnit.MILLISECONDS.toNanos(1_900))
        } finally { release.countDown(); active.join(3_000); queued.join(3_000) }
        assertFalse(active.isAlive); assertFalse(queued.isAlive); assertNull(failure.get()); assertEquals(2, calls.get())
        now.set(budget)
        assertInstanceOf(ServerAdmissionRead.Known::class.java, source.readFresh())
        assertEquals(3, calls.get())
        return result.get()
    }

    @Test fun `queued original deadline expiry is local while full budget expiry is a source failure`() {
        assertEquals(ServerAdmissionRead.LocalCapacity, afterQueue { now -> now.set(budget); throw ServerAdmissionRequestDeadlineException() })
        val now = AtomicLong()
        val source = GatewayServerAdmissionSource("http://localhost", "pep", "test-only-service", ServerAdmissionTransport { _, _, _, _ ->
            now.set(budget); throw ServerAdmissionRequestDeadlineException()
        }, now::get)
        assertEquals(ServerAdmissionRead.Unavailable, source.readFresh())
    }

    @Test fun `late valid PUBLIC after queue is local but VERIFYING remains a global fence`() {
        assertEquals(ServerAdmissionRead.LocalCapacity, afterQueue { now -> now.set(budget); ServerAdmissionHttpResponse(200, body()) })
        assertEquals(ServerAdmissionRead.Unavailable, afterQueue { now -> now.set(budget); ServerAdmissionHttpResponse(200, body("VERIFYING")) })
    }

    @Test fun `status connect malformed identity revision and body failures after queue are never hidden`() {
        val responses = listOf(ServerAdmissionHttpResponse(503, body()), ServerAdmissionHttpResponse(200, "not-json"),
            ServerAdmissionHttpResponse(200, body().replace("pep", "uni")), ServerAdmissionHttpResponse(200, body(revision = "0")),
            ServerAdmissionHttpResponse(200, " ".repeat(4097)), ServerAdmissionHttpResponse(200, body().replace("KNOWN", "UNKNOWN")))
        for (response in responses) assertEquals(ServerAdmissionRead.Unavailable, afterQueue { now -> now.set(budget); response })
        assertEquals(ServerAdmissionRead.Unavailable, afterQueue { now -> now.set(budget); throw HttpConnectTimeoutException("test-only-connect") })
        assertEquals(ServerAdmissionRead.Unavailable, afterQueue { now -> now.set(budget); throw IllegalStateException("test-only-body-error") })
    }

    @Test fun `trusted dispatch wait is local and the same no-wait deadline remains unavailable`() {
        for (waited in listOf(true, false)) {
            val now = AtomicLong(TimeUnit.MILLISECONDS.toNanos(1_900))
            val source = GatewayServerAdmissionSource("http://localhost", "pep", "test-only-service", ServerAdmissionTransport { _, _, started, _ ->
                assertEquals(0L, started); now.set(budget); throw ServerAdmissionRequestDeadlineException()
            }, now::get)
            assertEquals(if (waited) ServerAdmissionRead.LocalCapacity else ServerAdmissionRead.Unavailable, source.readFresh(0, waited))
        }
    }

    @Test fun `local queue expiry preserves another fresh proof but real failure invalidates it`() {
        var now = budget
        var read: ServerAdmissionRead = ServerAdmissionRead.Known(ServerAdmissionSnapshot("pep", ServerPublicationState.PUBLIC, 10), now, budget)
        val policy = ServerAdmissionPolicy(ServerAdmissionSource { read }, { now })
        val proof = policy.checkOrdinary() as ServerAdmissionDecision.Allowed
        read = ServerAdmissionRead.LocalCapacity
        assertEquals(ServerAdmissionDecision.Denied.LOCAL_CAPACITY, policy.checkOrdinary())
        assertTrue(policy.stillCurrent(proof))
        read = ServerAdmissionRead.Unavailable
        assertEquals(ServerAdmissionDecision.Denied.UNAVAILABLE, policy.checkOrdinary())
        assertFalse(policy.stillCurrent(proof))
    }

    @Test fun `future origin and exhausted arrival cannot start source lookup`() {
        val calls = AtomicInteger()
        val policy = ServerAdmissionPolicy(ServerAdmissionSource { calls.incrementAndGet(); error("must not read") }, { budget })
        assertEquals(ServerAdmissionDecision.Denied.UNAVAILABLE, policy.checkOrdinary(budget + 1))
        assertEquals(ServerAdmissionDecision.Denied.LOCAL_CAPACITY, policy.checkOrdinary(0))
        assertEquals(0, calls.get())
        val source = GatewayServerAdmissionSource("http://localhost", "pep", "test-only-service", ServerAdmissionTransport { _, _, _, _ -> calls.incrementAndGet(); error("must not fetch") }, { budget })
        assertEquals(ServerAdmissionRead.Unavailable, source.readFresh(budget + 1))
        assertEquals(ServerAdmissionRead.LocalCapacity, source.readFresh(0)); assertEquals(0, calls.get())
    }

    @Test fun `default noarg source cannot reset dispatch arrival proof budget`() {
        var now = TimeUnit.MILLISECONDS.toNanos(1_500)
        val policy = ServerAdmissionPolicy(ServerAdmissionSource {
            ServerAdmissionRead.Known(ServerAdmissionSnapshot("pep", ServerPublicationState.PUBLIC, 10), now, budget)
        }, { now })
        val proof = policy.checkOrdinary(0, true) as ServerAdmissionDecision.Allowed
        assertEquals(0L, proof.proof.startedNanos)
        now = budget; assertFalse(policy.stillCurrent(proof))
    }

    @Test fun `real HTTP remaining body deadline has a distinct typed outcome`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor(); val release = CountDownLatch(1); val arrived = CountDownLatch(1)
        server.executor = executor
        server.createContext("/slow") { e ->
            e.sendResponseHeaders(200, 0); e.responseBody.flush(); arrived.countDown()
            try { release.await(3, TimeUnit.SECONDS) } finally { e.close() }
        }
        server.start()
        try {
            val start = System.nanoTime() - budget + TimeUnit.MILLISECONDS.toNanos(500)
            assertThrows(ServerAdmissionRequestDeadlineException::class.java) {
                JdkServerAdmissionTransport().fetch(URI("http://127.0.0.1:${server.address.port}/slow"), "test-only-service", start, budget)
            }
            assertTrue(arrived.await(1, TimeUnit.SECONDS))
        } finally { release.countDown(); server.stop(0); executor.shutdownNow(); assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS)) }
    }

    private class Emitter : SseEmitter(0L) {
        val sends = AtomicInteger()
        var beforeSend: () -> Unit = {}
        override fun send(builder: SseEventBuilder) { beforeSend(); sends.incrementAndGet() }
    }
    private fun connect(relay: RealtimeRelayController) = relay.turn(MockHttpServletRequest("GET", "/sse/turn"), MockHttpServletResponse())

    @Test fun `registration can proceed while an event fresh source query is waiting`() {
        val calls = AtomicInteger(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val policy = ServerAdmissionPolicy(ServerAdmissionSource {
            if (calls.incrementAndGet() == 2) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            ServerAdmissionRead.Known(ServerAdmissionSnapshot("pep", ServerPublicationState.PUBLIC, 10), 0, budget)
        }, { 0L })
        val relay = RealtimeRelayController(policy, { 0L }, { Emitter() }, false)
        val failure = AtomicReference<Throwable>(); val sender = Thread { try { relay.fanOut("{}") } catch (e: Throwable) { failure.set(e) } }
        try {
            assertEquals(200, connect(relay).statusCode.value()); sender.start(); assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(200, connect(relay).statusCode.value()); assertEquals(2, relay.emitterCount())
        } finally { release.countDown(); sender.join(3_000); relay.destroy() }
        assertFalse(sender.isAlive); assertNull(failure.get()); assertEquals(3, calls.get())
    }

    @Test fun `registration tolerates bounded send round contention`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val emitter = Emitter()
        val policy = ServerAdmissionPolicy(ServerAdmissionSource { ServerAdmissionRead.Known(ServerAdmissionSnapshot("pep", ServerPublicationState.PUBLIC, 10), 0, budget) }, { 0L })
        val relay = RealtimeRelayController(policy, { 0L }, { emitter }, false)
        val result = AtomicInteger(); val failure = AtomicReference<Throwable>()
        val sender = Thread { try { relay.fanOut("{}") } catch (e: Throwable) { failure.set(e) } }
        val registering = Thread { try { result.set(connect(relay).statusCode.value()) } catch (e: Throwable) { failure.set(e) } }
        try {
            assertEquals(200, connect(relay).statusCode.value())
            emitter.beforeSend = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            sender.start(); assertTrue(entered.await(1, TimeUnit.SECONDS)); registering.start(); awaitWaiting(registering)
        } finally { release.countDown(); sender.join(3_000); registering.join(3_000); relay.destroy() }
        assertFalse(sender.isAlive); assertFalse(registering.isAlive); assertNull(failure.get()); assertEquals(200, result.get())
    }

    @Test fun `expired dispatch queue never begins another fresh source budget or sends a frame`() {
        val now = AtomicLong(); val calls = AtomicInteger(); val emitter = Emitter()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val policy = ServerAdmissionPolicy(ServerAdmissionSource { calls.incrementAndGet(); ServerAdmissionRead.Known(ServerAdmissionSnapshot("pep", ServerPublicationState.PUBLIC, 10), now.get(), budget) }, now::get)
        val relay = RealtimeRelayController(policy, now::get, { emitter }, false)
        val failure = AtomicReference<Throwable>()
        val first = Thread { try { relay.fanOut("{}") } catch (e: Throwable) { failure.set(e) } }
        val second = Thread { try { relay.fanOut("{}") } catch (e: Throwable) { failure.set(e) } }
        try {
            connect(relay); emitter.beforeSend = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            first.start(); assertTrue(entered.await(1, TimeUnit.SECONDS)); second.start(); awaitWaiting(second); now.set(budget)
        } finally { release.countDown(); first.join(3_000); second.join(3_000); relay.destroy() }
        assertFalse(first.isAlive); assertFalse(second.isAlive); assertNull(failure.get()); assertEquals(2, calls.get())
        assertEquals(2, emitter.sends.get())
    }
}

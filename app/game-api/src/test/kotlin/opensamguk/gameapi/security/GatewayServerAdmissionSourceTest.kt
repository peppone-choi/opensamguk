package opensamguk.gameapi.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

class GatewayServerAdmissionSourceTest {
    private fun body(state: String = "PUBLIC", revision: String = "10", serverId: String = "pep") =
        """{"serverId":"$serverId","state":"$state","revision":"$revision","sourceStatus":"KNOWN"}"""
    private fun source(response: ServerAdmissionHttpResponse, now: () -> Long = { 0L }) =
        GatewayServerAdmissionSource("http://localhost:8080", "pep", "test-only-service", ServerAdmissionTransport { uri, _, _, _ ->
            assertEquals("/internal/servers/pep/admission", uri.path)
            response
        }, now)

    @Test fun `exact KNOWN PUBLIC and VERIFYING with decimal-string BIGINT are accepted`() {
        for (state in listOf("PUBLIC", "VERIFYING")) {
            val id = "a".repeat(48)
            val response = ServerAdmissionHttpResponse(200, body(state, "9223372036854775807", id))
            val result = GatewayServerAdmissionSource("http://localhost", id, "test-only-service",
                ServerAdmissionTransport { _, _, _, _ -> response }, { 0L }).readFresh()
            assertInstanceOf(ServerAdmissionRead.Known::class.java, result)
            assertEquals(Long.MAX_VALUE, (result as ServerAdmissionRead.Known).snapshot.revision)
            assertEquals(id, result.snapshot.serverId)
        }
    }

    @Test fun `wrong server unknown source invalid state and malformed revision fail closed`() {
        val bodies = listOf(body(serverId = "uni"), body(state = "PRIVATE"), body(revision = "0"),
            body(revision = "01"), body(revision = "9223372036854775808"), body().replace("KNOWN", "UNKNOWN"),
            body().replace("\"10\"", "10"), "{}", "[]", "not-json")
        for (invalid in bodies) assertEquals(ServerAdmissionRead.Unavailable, source(ServerAdmissionHttpResponse(200, invalid)).readFresh())
    }

    @Test fun `internal status errors and oversized body are unavailable not user auth or default PUBLIC`() {
        for (status in listOf(301, 401, 403, 404, 500, 503)) {
            assertEquals(ServerAdmissionRead.Unavailable, source(ServerAdmissionHttpResponse(status, body())).readFresh())
        }
        assertEquals(ServerAdmissionRead.Unavailable, source(ServerAdmissionHttpResponse(200, " ".repeat(4097) + body())).readFresh())
    }

    @Test fun `body completion and parsing deadline excludes late success`() {
        var now = 0L
        val deadlineSource = GatewayServerAdmissionSource("http://localhost:8080", "pep", "test-only-service", ServerAdmissionTransport { _, _, _, _ ->
            now = ServerAdmissionDraftBudget.totalNanos
            ServerAdmissionHttpResponse(200, body())
        }, { now })
        assertEquals(ServerAdmissionRead.Unavailable, deadlineSource.readFresh())
        // HTTP 완료는 즉시지만 parse 뒤 monotonic 시각이 deadline이면 거절한다.
        var clockReads = 0
        val parseSource = source(ServerAdmissionHttpResponse(200, body())) {
            if (clockReads++ < 2) 0L else ServerAdmissionDraftBudget.totalNanos
        }
        assertEquals(ServerAdmissionRead.Unavailable, parseSource.readFresh())
    }

    @Test fun `missing identity origin service configuration does not create permissive source`() {
        val transport = ServerAdmissionTransport { _, _, _, _ -> error("must not be called") }
        for (invalidId in listOf("a".repeat(49), "PEP", "pep-one", "한", "pep/uni", " pep")) {
            assertThrows(IllegalArgumentException::class.java) { GatewayServerAdmissionSource("http://localhost", invalidId, "test-only-service", transport) }
        }
        assertThrows(IllegalArgumentException::class.java) { GatewayServerAdmissionSource("", "pep", "test-only-service", transport) }
        assertThrows(IllegalArgumentException::class.java) { GatewayServerAdmissionSource("http://localhost", "", "test-only-service", transport) }
        assertThrows(IllegalArgumentException::class.java) { GatewayServerAdmissionSource("http://localhost", "pep", "", transport) }
        assertThrows(IllegalArgumentException::class.java) { GatewayServerAdmissionSource("http://localhost/path", "pep", "test-only-service", transport) }
    }

    private fun awaitWaiting(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (thread.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) LockSupport.parkNanos(100_000)
        assertEquals(Thread.State.TIMED_WAITING, thread.state)
    }

    @Test fun `twelve simultaneous PUBLIC requests finish with eight active lookups and no result reuse`() {
        val entered = CountDownLatch(8); val release = CountDownLatch(1)
        val active = AtomicInteger(); val maximum = AtomicInteger(); val calls = AtomicInteger()
        val source = GatewayServerAdmissionSource("http://localhost", "pep", "test-only-service", ServerAdmissionTransport { _, _, _, _ ->
            calls.incrementAndGet(); maximum.accumulateAndGet(active.incrementAndGet()) { left, right -> maxOf(left, right) }
            entered.countDown()
            try { assertTrue(release.await(2, TimeUnit.SECONDS)); ServerAdmissionHttpResponse(200, body()) }
            finally { active.decrementAndGet() }
        }, { 0L })
        val results = (1..12).map { AtomicReference<ServerAdmissionRead>() }
        val threads = results.map { result -> Thread { result.set(source.readFresh()) } }
        try {
            threads.take(8).forEach(Thread::start)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            threads.drop(8).forEach(Thread::start)
            threads.drop(8).forEach(::awaitWaiting)
            assertEquals(8, calls.get())
        } finally { release.countDown(); threads.forEach { if (it.state != Thread.State.NEW) it.join(3_000) } }
        assertTrue(threads.none { it.isAlive })
        results.forEach { assertInstanceOf(ServerAdmissionRead.Known::class.java, it.get()) }
        assertEquals(12, calls.get()); assertEquals(8, maximum.get()); assertEquals(0, active.get())
    }

    @Test fun `bounded queue refuses excess locally then returns every permit`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val source = GatewayServerAdmissionSource("http://localhost", "pep", "test-only-service", ServerAdmissionTransport { _, _, _, _ ->
            calls.incrementAndGet(); entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS))
            ServerAdmissionHttpResponse(200, body())
        }, { 0L }, maxConcurrent = 1)
        val first = AtomicReference<ServerAdmissionRead>(); val second = AtomicReference<ServerAdmissionRead>()
        val running = Thread { first.set(source.readFresh()) }; val waiting = Thread { second.set(source.readFresh()) }
        try {
            running.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); waiting.start(); awaitWaiting(waiting)
            assertEquals(ServerAdmissionRead.LocalCapacity, source.readFresh()); assertEquals(1, calls.get())
        } finally { release.countDown(); running.join(3_000); waiting.join(3_000) }
        assertFalse(running.isAlive); assertFalse(waiting.isAlive)
        assertInstanceOf(ServerAdmissionRead.Known::class.java, first.get())
        assertInstanceOf(ServerAdmissionRead.Known::class.java, second.get())
        assertInstanceOf(ServerAdmissionRead.Known::class.java, source.readFresh()); assertEquals(3, calls.get())
    }

    @Test fun `waiting timeout spends only the original total budget and never calls producer`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val source = GatewayServerAdmissionSource("http://localhost", "pep", "test-only-service", ServerAdmissionTransport { _, _, _, _ ->
            calls.incrementAndGet(); entered.countDown(); assertTrue(release.await(4, TimeUnit.SECONDS))
            ServerAdmissionHttpResponse(200, body())
        }, maxConcurrent = 1)
        val running = Thread { source.readFresh() }
        try {
            running.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            val started = System.nanoTime()
            assertEquals(ServerAdmissionRead.LocalCapacity, source.readFresh())
            val elapsed = System.nanoTime() - started
            assertTrue(elapsed >= ServerAdmissionDraftBudget.totalNanos)
            assertTrue(elapsed < TimeUnit.SECONDS.toNanos(3)); assertEquals(1, calls.get())
        } finally { release.countDown(); running.join(3_000) }
        assertFalse(running.isAlive)
        assertInstanceOf(ServerAdmissionRead.Known::class.java, source.readFresh())
    }

    @Test fun `queue wait does not grant a fresh HTTP budget when permit arrives after deadline`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val now = java.util.concurrent.atomic.AtomicLong()
        val source = GatewayServerAdmissionSource("http://localhost", "pep", "test-only-service", ServerAdmissionTransport { _, _, _, _ ->
            calls.incrementAndGet(); entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS))
            ServerAdmissionHttpResponse(200, body())
        }, now::get, maxConcurrent = 1)
        val second = AtomicReference<ServerAdmissionRead>()
        val running = Thread { source.readFresh() }; val waiting = Thread { second.set(source.readFresh()) }
        try {
            running.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); waiting.start(); awaitWaiting(waiting)
            now.set(ServerAdmissionDraftBudget.totalNanos)
        } finally { release.countDown(); running.join(3_000); waiting.join(3_000) }
        assertEquals(ServerAdmissionRead.LocalCapacity, second.get()); assertEquals(1, calls.get())
        assertFalse(running.isAlive); assertFalse(waiting.isAlive)
    }

    @Test fun `interrupted queued request restores interrupt and releases only its waiting slot`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val source = GatewayServerAdmissionSource("http://localhost", "pep", "test-only-service", ServerAdmissionTransport { _, _, _, _ ->
            entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); ServerAdmissionHttpResponse(200, body())
        }, { 0L }, maxConcurrent = 1)
        val result = AtomicReference<ServerAdmissionRead>(); val interrupted = AtomicReference<Boolean>()
        val running = Thread { source.readFresh() }
        val waiting = Thread { result.set(source.readFresh()); interrupted.set(Thread.currentThread().isInterrupted) }
        try {
            running.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); waiting.start(); awaitWaiting(waiting)
            waiting.interrupt(); waiting.join(2_000)
            assertEquals(ServerAdmissionRead.LocalCapacity, result.get()); assertEquals(true, interrupted.get())
        } finally { release.countDown(); running.join(3_000); waiting.join(3_000) }
        assertFalse(running.isAlive); assertFalse(waiting.isAlive)
        assertInstanceOf(ServerAdmissionRead.Known::class.java, source.readFresh())
    }

    @Test fun `queued requests acquire available producer slots in arrival order`() {
        val entered = CountDownLatch(2); val thirdEntered = CountDownLatch(1)
        val release = Semaphore(0); val order = CopyOnWriteArrayList<String>()
        val source = GatewayServerAdmissionSource("http://localhost", "pep", "test-only-service", ServerAdmissionTransport { _, _, _, _ ->
            order += Thread.currentThread().name; entered.countDown()
            if (order.size >= 3) thirdEntered.countDown()
            assertTrue(release.tryAcquire(2, TimeUnit.SECONDS)); ServerAdmissionHttpResponse(200, body())
        }, { 0L }, maxConcurrent = 2)
        val results = (1..4).map { AtomicReference<ServerAdmissionRead>() }
        val threads = results.mapIndexed { i, result -> Thread({ result.set(source.readFresh()) }, "admission-arrival-$i") }
        try {
            threads.take(2).forEach(Thread::start); assertTrue(entered.await(2, TimeUnit.SECONDS))
            threads[2].start(); awaitWaiting(threads[2]); threads[3].start(); awaitWaiting(threads[3])
            release.release(); assertTrue(thirdEntered.await(2, TimeUnit.SECONDS))
            assertEquals("admission-arrival-2", order[2])
        } finally { release.release(4); threads.forEach { if (it.state != Thread.State.NEW) it.join(3_000) } }
        assertTrue(threads.none { it.isAlive }); assertEquals("admission-arrival-3", order[3])
        results.forEach { assertInstanceOf(ServerAdmissionRead.Known::class.java, it.get()) }
    }

    @Test fun `real producer error after waiting remains unavailable rather than local capacity`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val source = GatewayServerAdmissionSource("http://localhost", "pep", "test-only-service", ServerAdmissionTransport { _, _, _, _ ->
            val first = calls.incrementAndGet() == 1
            if (first) { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
            ServerAdmissionHttpResponse(if (first) 200 else 503, body())
        }, { 0L }, maxConcurrent = 1)
        val first = AtomicReference<ServerAdmissionRead>(); val second = AtomicReference<ServerAdmissionRead>()
        val running = Thread { first.set(source.readFresh()) }; val waiting = Thread { second.set(source.readFresh()) }
        try { running.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); waiting.start(); awaitWaiting(waiting) }
        finally { release.countDown(); running.join(3_000); waiting.join(3_000) }
        assertInstanceOf(ServerAdmissionRead.Known::class.java, first.get())
        assertEquals(ServerAdmissionRead.Unavailable, second.get()); assertEquals(2, calls.get())
        assertFalse(running.isAlive); assertFalse(waiting.isAlive)
        assertEquals(ServerAdmissionRead.Unavailable, GatewayServerAdmissionSource("http://localhost", "pep", "test-only-service",
            ServerAdmissionTransport { _, _, _, _ -> throw java.net.http.HttpTimeoutException("test-only") }).readFresh())
    }
}

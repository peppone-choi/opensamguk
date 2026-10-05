package opensamguk.gameapi.sse

import opensamguk.gameapi.security.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.concurrent.atomic.AtomicReference

class RealtimeRelayAdmissionTest {
    private var now = 0L
    private var state: ServerPublicationState? = ServerPublicationState.PUBLIC
    private var revision = 1L
    private var reads = 0
    private var localCapacity = false
    private val policy = ServerAdmissionPolicy(ServerAdmissionSource {
        reads++
        if (localCapacity) return@ServerAdmissionSource ServerAdmissionRead.LocalCapacity
        state?.let { ServerAdmissionRead.Known(ServerAdmissionSnapshot("pep", it, revision), now, TimeUnit.SECONDS.toNanos(2)) }
            ?: ServerAdmissionRead.Unavailable
    }, { now })
    private val emitters = mutableListOf<RecordingEmitter>()
    private val relay = RealtimeRelayController(policy, { now }, { RecordingEmitter().also { emitters += it } }, false)
    @AfterEach fun shutdown() = relay.destroy()

    private class RecordingEmitter : SseEmitter(0L) {
        var sends = 0
        val frames = java.util.concurrent.CopyOnWriteArrayList<String>()
        var beforeSend: (() -> Unit)? = null
        var completion: Runnable? = null
        var error: Consumer<Throwable>? = null
        val completeCalled = CountDownLatch(1)
        override fun send(builder: SseEventBuilder) { beforeSend?.invoke(); frames += builder.build().joinToString { it.data.toString() }; sends++ }
        override fun onCompletion(callback: Runnable) { completion = callback }
        override fun onError(callback: Consumer<Throwable>) { error = callback }
        override fun complete() { completeCalled.countDown() }
        fun finish() { completion!!.run() }
    }
    private fun connect() = relay.turn(MockHttpServletRequest("GET", "/sse/turn"), MockHttpServletResponse())

    @Test fun `private and unavailable registration creates no emitter`() {
        state = ServerPublicationState.VERIFYING
        assertEquals(401, connect().statusCode.value())
        state = null
        assertEquals(503, connect().statusCode.value())
        assertEquals(0, relay.emitterCount()); assertTrue(emitters.isEmpty())
    }

    @Test fun `registration and each fanout heartbeat use one fresh query for all clients`() {
        repeat(2) { assertEquals(200, connect().statusCode.value()) }
        assertEquals(2, reads)
        relay.fanOut("{\"type\":\"turnCompleted\"}")
        assertEquals(3, reads); assertEquals(listOf(2, 2), emitters.map { it.sends })
        relay.heartbeat()
        assertEquals(4, reads); assertEquals(listOf(3, 3), emitters.map { it.sends })
    }

    @Test fun `deadline expires after first send and stops remainder of same round`() {
        repeat(2) { connect() }
        emitters[0].beforeSend = { now = TimeUnit.SECONDS.toNanos(2) }
        relay.fanOut("{}")
        assertEquals(listOf(2, 1), emitters.map { it.sends })
        assertEquals(0, relay.emitterCount()); assertEquals(2, relay.pendingCloseCount())
        emitters.forEach { assertTrue(it.completeCalled.await(2, TimeUnit.SECONDS)); it.finish() }
        assertEquals(0, relay.pendingCloseCount())
    }

    @Test fun `watchdog failure detaches but completion call alone is not socket evidence`() {
        connect(); state = null
        relay.admissionWatchdog()
        assertEquals(0, relay.emitterCount()); assertEquals(1, relay.pendingCloseCount())
        assertTrue(emitters.single().completeCalled.await(2, TimeUnit.SECONDS))
        assertEquals(1, relay.pendingCloseCount())
        relay.heartbeat(); assertEquals(1, emitters.single().sends)
        emitters.single().finish(); assertEquals(0, relay.pendingCloseCount())
        // 늦은 error callback은 이미 완료된 client를 pending에 다시 등록하지 않는다.
        emitters.single().error!!.accept(IllegalStateException("late test callback"))
        assertEquals(0, relay.pendingCloseCount())
    }

    @Test fun `new VERIFYING revision closes existing PUBLIC stream without another frame`() {
        connect(); state = ServerPublicationState.VERIFYING; revision++
        relay.fanOut("{}")
        assertEquals(1, emitters.single().sends); assertEquals(0, relay.emitterCount())
        assertTrue(emitters.single().completeCalled.await(2, TimeUnit.SECONDS)); emitters.single().finish()
    }

    @Test fun `expired registration rolls back emitter before HTTP attachment without pending socket`() {
        val created = RecordingEmitter()
        val local = RealtimeRelayController(policy, { now }, { now = TimeUnit.SECONDS.toNanos(2); created }, false)
        try {
            val response = MockHttpServletResponse()
            val result = local.turn(MockHttpServletRequest("GET", "/sse/turn"), response)
            assertEquals(503, result.statusCode.value()); assertNull(result.body)
            assertTrue(response.contentAsString.contains("SERVER_ADMISSION_UNAVAILABLE"))
            assertEquals(0, created.sends); assertEquals(0, local.emitterCount()); assertEquals(0, local.pendingCloseCount())
            assertTrue(created.completeCalled.await(2, TimeUnit.SECONDS))
        } finally { local.destroy() }
    }

    @Test fun `consecutive command and turn events wait in order without disconnecting PUBLIC streams`() {
        connect()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val senderFailure = AtomicReference<Throwable>()
        emitters.single().beforeSend = {
            if (events.isEmpty()) { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
            events += Thread.currentThread().name
        }
        val first = Thread({ try { relay.fanOut("{\"type\":\"commandSettled\"}") } catch (e: Throwable) { senderFailure.set(e) } }, "commandSettled")
        val second = Thread({ try { relay.fanOut("{\"type\":\"turnCompleted\"}") } catch (e: Throwable) { senderFailure.set(e) } }, "turnCompleted")
        try {
            first.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); second.start()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (second.state != Thread.State.TIMED_WAITING && second.isAlive && System.nanoTime() < until) Thread.yield()
            assertEquals(Thread.State.TIMED_WAITING, second.state)
            assertEquals(1, relay.emitterCount())
        } finally { release.countDown(); first.join(2_000); second.join(2_000) }
        assertFalse(first.isAlive); assertFalse(second.isAlive); assertNull(senderFailure.get())
        assertEquals(listOf("commandSettled", "turnCompleted"), events)
        assertTrue(emitters.single().frames[1].contains("event:commandSettled"))
        assertTrue(emitters.single().frames[2].contains("event:turnCompleted"))
        assertEquals(3, reads); assertEquals(3, emitters.single().sends)
        assertEquals(1, relay.emitterCount()); assertEquals(0, relay.pendingCloseCount())
    }
    @Test fun `local capacity blocks new attachment and skips rounds without closing existing streams`() {
        repeat(2) { assertEquals(200, connect().statusCode.value()) }
        localCapacity = true
        assertEquals(503, connect().statusCode.value())
        relay.fanOut("{}"); relay.heartbeat(); relay.admissionWatchdog()
        assertEquals(listOf(1, 1), emitters.map { it.sends })
        assertEquals(2, relay.emitterCount()); assertEquals(0, relay.pendingCloseCount())
        localCapacity = false
        relay.heartbeat()
        assertEquals(listOf(2, 2), emitters.map { it.sends })
        state = null
        relay.admissionWatchdog()
        assertEquals(0, relay.emitterCount()); assertEquals(2, relay.pendingCloseCount())
        emitters.forEach { assertTrue(it.completeCalled.await(2, TimeUnit.SECONDS)); it.finish() }
    }

    @Test fun `local HTTP capacity rejection cannot interrupt a separate successful fanout round`() {
        connect()
        emitters.single().beforeSend = {
            localCapacity = true
            assertEquals(ServerAdmissionDecision.Denied.LOCAL_CAPACITY, policy.checkHttp(false))
            localCapacity = false
        }
        relay.fanOut("{}")
        assertEquals(2, emitters.single().sends); assertEquals(1, relay.emitterCount())
        assertEquals(0, relay.pendingCloseCount())
    }

    @Test fun `repeated local capacity never extends the previous observation deadline`() {
        connect(); localCapacity = true
        repeat(3) { relay.admissionWatchdog(); assertEquals(1, relay.emitterCount()) }
        now = TimeUnit.SECONDS.toNanos(2)
        relay.admissionWatchdog()
        assertEquals(1, emitters.single().sends)
        assertEquals(0, relay.emitterCount()); assertEquals(1, relay.pendingCloseCount())
        assertTrue(emitters.single().completeCalled.await(2, TimeUnit.SECONDS)); emitters.single().finish()
    }

}

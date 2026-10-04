package opensamguk.gameapi.sse

import opensamguk.gameapi.security.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

class RealtimeRelayAdmissionTest {
    private var now = 0L
    private var state: ServerPublicationState? = ServerPublicationState.PUBLIC
    private var revision = 1L
    private var reads = 0
    private val policy = ServerAdmissionPolicy(ServerAdmissionSource {
        reads++
        state?.let { ServerAdmissionRead.Known(ServerAdmissionSnapshot("pep", it, revision), now, TimeUnit.SECONDS.toNanos(2)) }
            ?: ServerAdmissionRead.Unavailable
    }, { now })
    private val emitters = mutableListOf<RecordingEmitter>()
    private val relay = RealtimeRelayController(policy, { now }, { RecordingEmitter().also { emitters += it } }, false)
    @AfterEach fun shutdown() = relay.destroy()

    private class RecordingEmitter : SseEmitter(0L) {
        var sends = 0
        var beforeSend: (() -> Unit)? = null
        var completion: Runnable? = null
        var error: Consumer<Throwable>? = null
        val completeCalled = CountDownLatch(1)
        override fun send(builder: SseEventBuilder) { beforeSend?.invoke(); sends++ }
        override fun onCompletion(callback: Runnable) { completion = callback }
        override fun onError(callback: Consumer<Throwable>) { error = callback }
        override fun complete() { completeCalled.countDown() }
        fun finish() { completion!!.run() }
    }
    private fun connect() = relay.turn(MockHttpServletRequest("GET", "/sse/turn"))

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

    @Test fun `parallel round contention detaches existing connection without callback queue`() {
        connect()
        // 동일 thread의 reentrant 호출 대신 별도 thread가 열린 round와 충돌한다.
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        emitters.single().beforeSend = { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
        val sender = Thread { relay.fanOut("{}") }
        sender.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            relay.fanOut("{}")
            assertEquals(0, relay.emitterCount())
        } finally { release.countDown(); sender.join(2_000) }
        assertFalse(sender.isAlive)
        assertTrue(emitters.single().completeCalled.await(2, TimeUnit.SECONDS)); emitters.single().finish()
    }
}

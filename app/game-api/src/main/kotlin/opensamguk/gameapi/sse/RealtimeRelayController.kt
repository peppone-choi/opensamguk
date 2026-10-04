package opensamguk.gameapi.sse

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensamguk.gameapi.security.JwtVerifyFilter
import opensamguk.gameapi.security.ServerAdmissionDecision
import opensamguk.gameapi.security.ServerAdmissionFilter
import opensamguk.gameapi.security.ServerAdmissionPolicy
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/** 공개 coarse 신호만 중계한다. 상태 재검사 실패 시 기존 ordinary 연결도 송신을 멈춘다. */
@RestController
@RequestMapping("/sse")
class RealtimeRelayController internal constructor(
    private val policy: ServerAdmissionPolicy,
    private val nanoTime: () -> Long,
    private val emitterFactory: () -> SseEmitter,
    startSchedules: Boolean,
) : DisposableBean {
    @Autowired
    constructor(policy: ServerAdmissionPolicy) : this(policy, System::nanoTime, { SseEmitter(0L) }, true)

    private class Client(val id: Long, val emitter: SseEmitter) {
        val open = AtomicBoolean(true)
        val closeStarted = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
    }
    private val nextId = AtomicLong()
    private val clients = CopyOnWriteArrayList<Client>()
    private val closing = ConcurrentHashMap<Long, Client>()
    private val round = ReentrantLock()
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { runnable ->
        Thread(runnable, "sse-admission-schedule").apply { isDaemon = true }
    }
    private val closeExecutor = ThreadPoolExecutor(0, 8, 30L, TimeUnit.SECONDS,
        SynchronousQueue(), { runnable -> Thread(runnable, "sse-admission-close").apply { isDaemon = true } })
    private val destroyed = AtomicBoolean(false)

    init {
        if (startSchedules) {
            scheduler.scheduleWithFixedDelay({ admissionWatchdog() }, 5L, 5L, TimeUnit.SECONDS)
            scheduler.scheduleWithFixedDelay({ heartbeat() }, 30L, 30L, TimeUnit.SECONDS)
        }
    }

    @GetMapping("/turn")
    fun turn(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<SseEmitter> {
        val fromChain = ServerAdmissionFilter.proof(request)
        val decision = if (fromChain == null) policy.checkHttp(JwtVerifyFilter.principal(request) != null)
            else if (policy.stillCurrent(fromChain)) fromChain else ServerAdmissionDecision.Denied.UNAVAILABLE
        if (decision !is ServerAdmissionDecision.Allowed) return rejected(decision as ServerAdmissionDecision.Denied, response)
        if (destroyed.get() || !round.tryLock()) return rejected(ServerAdmissionDecision.Denied.UNAVAILABLE, response)
        try {
            if (!policy.stillCurrent(decision)) return rejected(ServerAdmissionDecision.Denied.UNAVAILABLE, response)
            val client = Client(nextId.incrementAndGet(), emitterFactory())
            client.emitter.onCompletion {
                client.completed.set(true)
                client.open.set(false)
                clients.remove(client)
                closing.remove(client.id, client)
            }
            client.emitter.onTimeout { closeClient(client) }
            client.emitter.onError { closeClient(client) }
            clients.add(client)
            if (!policy.stillCurrent(decision) || !client.open.get()) {
                discardBeforeReturn(client)
                return rejected(ServerAdmissionDecision.Denied.UNAVAILABLE, response)
            }
            try { client.emitter.send(SseEmitter.event().comment("connected")) }
            catch (_: Exception) { discardBeforeReturn(client); return rejected(ServerAdmissionDecision.Denied.UNAVAILABLE, response) }
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).header("Cache-Control", "no-store").body(client.emitter)
        } finally { round.unlock() }
    }

    /** 같은 round의 모든 emitter가 같은 fresh 조회를 사용하며 송신마다 proof를 재검사한다. */
    fun fanOut(json: String) = broadcast { eventFor(json) }

    internal fun heartbeat() = broadcast { SseEmitter.event().comment("hb") }

    private fun broadcast(event: () -> SseEmitter.SseEventBuilder) {
        if (destroyed.get() || clients.isEmpty()) return
        // parallel fanOut을 무한 callback queue로 저장하지 않는다. 기존 연결은 닫고 재접속시킨다.
        if (!round.tryLock()) { closeAll(); return }
        try {
            val fresh = policy.checkOrdinary()
            if (fresh !is ServerAdmissionDecision.Allowed) { closeAll(); return }
            for (client in clients) {
                if (!policy.stillCurrent(fresh)) { closeAll(); return }
                if (!client.open.get()) continue
                try { client.emitter.send(event()) }
                catch (_: Exception) { closeClient(client) }
            }
        } finally { round.unlock() }
    }

    /** 송신 스레드가 막혀도 별도 watchdog가 논리 detach/close 접수를 할 수 있다. */
    internal fun admissionWatchdog() {
        if (destroyed.get()) return
        retryClosing()
        if (clients.isEmpty()) return
        if (policy.checkOrdinary() !is ServerAdmissionDecision.Allowed) closeAll()
    }

    fun emitterCount(): Int = clients.size
    fun pendingCloseCount(): Int = closing.size

    private fun closeAll() {
        val detached = clients.toList()
        // 모두 open=false가 된 뒤 제거한다. 오래된 iterator/callback도 다시 송신하지 못한다.
        for (client in detached) markClosing(client)
        clients.removeAll(detached.toSet())
        val started = nanoTime()
        for (client in detached) {
            if (nanoTime() - started >= CLOSE_SUBMISSION_BUDGET_NANOS) break
            submitClose(client)
        }
        // budget/worker 부족은 closing에 남겨 실제 completion 전 완료로 세지 않는다.
    }

    private fun discardBeforeReturn(client: Client) {
        // MVC에 emitter를 반환하지 않았으므로 HTTP stream/async handler는 아직 생성되지 않았다.
        // 이 rollback은 연결된 socket의 completion 증거와 구분한다.
        client.completed.set(true)
        client.open.set(false)
        clients.remove(client)
        closing.remove(client.id, client)
        try { client.emitter.complete() } catch (_: Exception) { /* 미등록 자원만 회수한다. */ }
    }

    private fun closeClient(client: Client) {
        markClosing(client)
        clients.remove(client)
        submitClose(client)
    }

    private fun markClosing(client: Client) {
        client.open.set(false)
        if (client.completed.get()) return
        closing[client.id] = client
        // completion과 detach가 교차해도 이미 끝난 연결을 pending으로 다시 넣지 않는다.
        if (client.completed.get()) closing.remove(client.id, client)
    }

    private fun retryClosing() {
        val started = nanoTime()
        for (client in closing.values) {
            if (nanoTime() - started >= CLOSE_SUBMISSION_BUDGET_NANOS) break
            submitClose(client)
        }
    }

    private fun submitClose(client: Client) {
        if (client.completed.get() || !client.closeStarted.compareAndSet(false, true)) return
        try {
            closeExecutor.execute {
                try { client.emitter.complete() }
                catch (_: Exception) { client.closeStarted.set(false) }
                // completion callback만 closing을 제거한다. complete() 반환은 socket 종료 증명이 아니다.
            }
        } catch (_: RejectedExecutionException) { client.closeStarted.set(false) }
    }

    private fun rejected(reason: ServerAdmissionDecision.Denied, response: HttpServletResponse): ResponseEntity<SseEmitter> {
        // ResponseEntity<SseEmitter>를 유지해야 Spring의 streaming return handler가 성공 본문을 처리한다.
        response.status = reason.httpStatus
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.setHeader("Cache-Control", "no-store")
        response.writer.write("""{"error":{"code":"${reason.code}"}}""")
        return ResponseEntity.status(reason.httpStatus).contentType(MediaType.APPLICATION_JSON).header("Cache-Control", "no-store").build()
    }

    internal fun eventFor(json: String): SseEmitter.SseEventBuilder =
        SseEmitter.event().name(eventNameOf(json)).data(json)

    private fun eventNameOf(json: String): String = runCatching {
        val node = Json.parseToJsonElement(json) as? JsonObject ?: return@runCatching null
        (node["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: DEFAULT_EVENT_NAME

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        scheduler.shutdownNow()
        closeAll()
        closeExecutor.shutdown()
    }

    companion object {
        const val DEFAULT_EVENT_NAME: String = "turnCompleted"
        private val CLOSE_SUBMISSION_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(1)
    }
}

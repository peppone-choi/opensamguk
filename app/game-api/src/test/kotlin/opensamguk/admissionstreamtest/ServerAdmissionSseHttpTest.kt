package opensamguk.admissionstreamtest

import opensamguk.gameapi.security.*
import opensamguk.gameapi.sse.RealtimeRelayController
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.context.request.async.DeferredResult
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor
import org.springframework.web.context.request.async.WebAsyncUtils
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class HttpAdmissionSource : ServerAdmissionSource {
    @Volatile var state: ServerPublicationState? = ServerPublicationState.PUBLIC
    val revision = AtomicLong(1)
    val reads = AtomicInteger()
    override fun readFresh(): ServerAdmissionRead {
        reads.incrementAndGet()
        return state?.let { ServerAdmissionRead.Known(ServerAdmissionSnapshot("pep", it, revision.get()), System.nanoTime(), TimeUnit.SECONDS.toNanos(2)) }
            ?: ServerAdmissionRead.Unavailable
    }
}

/** Observes actual MVC completion, including completion before emitter initialization. */
class HttpSseCompletionProbe {
    class InitializationGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
    class Registration {
        val completed = CountDownLatch(1)
        val completeCalled = CountDownLatch(1)
    }
    private val registrations = LinkedBlockingQueue<Registration>()
    private val nextGate = AtomicReference<InitializationGate?>()

    fun holdNextInitialization() = InitializationGate().also { check(nextGate.compareAndSet(null, it)) }

    fun emitter(): SseEmitter {
        val registration = Registration()
        val gate = nextGate.getAndSet(null)
        val request = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request
        // Registered before the controller's interceptor: reverse completion order observes its cleanup.
        WebAsyncUtils.getAsyncManager(request).registerDeferredResultInterceptor(registration,
            object : DeferredResultProcessingInterceptor {
                override fun <T : Any?> afterCompletion(request: NativeWebRequest, deferredResult: DeferredResult<T>) {
                    registration.completed.countDown()
                }
            })
        return object : SseEmitter(0L) {
            override fun extendResponse(outputMessage: ServerHttpResponse) {
                super.extendResponse(outputMessage)
                gate?.let {
                    it.entered.countDown()
                    check(it.release.await(5, TimeUnit.SECONDS)) { "Emitter initialization gate was not released" }
                }
            }
            override fun complete() {
                super.complete()
                registration.completeCalled.countDown()
            }
        }.also { registrations.add(registration) }
    }

    fun next(): Registration = requireNotNull(registrations.poll(5, TimeUnit.SECONDS)) {
        "SSE registration did not create an emitter"
    }
}

@SpringBootConfiguration
@EnableWebSecurity
@EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class, HibernateJpaAutoConfiguration::class,
    RedisAutoConfiguration::class, RedisRepositoriesAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
@Import(GameApiSecurityConfig::class)
private class AdmissionStreamTestApplication {
    @Bean fun source() = HttpAdmissionSource()
    @Bean fun completionProbe() = HttpSseCompletionProbe()
    @Bean fun policy(source: HttpAdmissionSource) = ServerAdmissionPolicy(source)
    @Bean fun verifier() = GameApiJwtVerifier("", java.util.Base64.getEncoder().encodeToString(ByteArray(48) { (it + 1).toByte() }), "2099-01-01T00:00:00Z")
    @Bean fun jwt(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
    // 명시적 시험 source를 쓰며 watchdog는 시험이 직접 실행한다.
    @Bean fun relay(policy: ServerAdmissionPolicy, probe: HttpSseCompletionProbe) =
        RealtimeRelayController(policy, System::nanoTime, probe::emitter, false)
}

@SpringBootTest(classes = [AdmissionStreamTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerAdmissionSseHttpTest {
    @LocalServerPort private var port = 0
    @Autowired lateinit var source: HttpAdmissionSource
    @Autowired lateinit var completionProbe: HttpSseCompletionProbe
    @Autowired lateinit var relay: RealtimeRelayController

    @Test fun `real HTTP SSE PUBLIC frame ends at VERIFYING and UNKNOWN watchdog`() {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().use { client ->
            for (failedState in listOf(ServerPublicationState.VERIFYING, null)) {
                source.state = ServerPublicationState.PUBLIC; source.revision.incrementAndGet()
                val readsBefore = source.reads.get()
                val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/sse/turn")).GET().timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofInputStream())
                assertEquals(200, response.statusCode())
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""))
                assertEquals(readsBefore + 1, source.reads.get(), "security filter와 등록은 같은 조회를 소비한다")
                val completion = completionProbe.next()
                val readerExecutor = Executors.newSingleThreadExecutor()
                try {
                    response.body().use { stream ->
                        val reader = stream.bufferedReader()
                        val initial = readerExecutor.submit<String> { reader.readLine() }
                        assertEquals(":connected", initial.get(5, TimeUnit.SECONDS))
                        source.state = failedState; source.revision.incrementAndGet()
                        val remainder = readerExecutor.submit<List<String>> { reader.lineSequence().toList() }
                        relay.admissionWatchdog()
                        assertTrue(remainder.get(5, TimeUnit.SECONDS).all { it.isBlank() }, "상태 실패 뒤 새 event/hb를 보내지 않고 EOF여야 한다")
                    }
                    assertTrue(completion.completed.await(5, TimeUnit.SECONDS), "EOF 뒤 MVC completion callback이 실행되어야 한다")
                    assertEquals(0, relay.emitterCount()); assertEquals(0, relay.pendingCloseCount())
                    println("admission_sse_http_eof_state=${failedState ?: "UNKNOWN"}")
                } finally { readerExecutor.shutdownNow() }
            }
        }
    }

    @Test fun `real HTTP early watchdog completion before emitter initialization releases pending close`() {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().use { client ->
            for (failedState in listOf(ServerPublicationState.VERIFYING, null)) {
                source.state = ServerPublicationState.PUBLIC; source.revision.incrementAndGet()
                val gate = completionProbe.holdNextInitialization()
                val response = client.sendAsync(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/sse/turn"))
                    .GET().timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString())
                try {
                    assertTrue(gate.entered.await(5, TimeUnit.SECONDS), "MVC must pause before emitter initialization")
                    val registration = completionProbe.next()
                    source.state = failedState; source.revision.incrementAndGet()
                    relay.admissionWatchdog()
                    assertTrue(registration.completeCalled.await(5, TimeUnit.SECONDS), "watchdog must complete before initialization")
                    assertEquals(0, relay.emitterCount())
                    assertEquals(1, relay.pendingCloseCount(), "complete() alone is not MVC completion evidence")
                    gate.release.countDown()
                    val closed = response.get(5, TimeUnit.SECONDS)
                    assertEquals(200, closed.statusCode())
                    assertEquals(":connected", closed.body().trim(), "no event/hb may follow admission failure")
                    assertTrue(registration.completed.await(5, TimeUnit.SECONDS), "EOF must be followed by actual MVC completion")
                    assertEquals(0, relay.pendingCloseCount(), "early completion must not retain a closed HTTP stream")
                    println("admission_sse_http_early_completion_state=${failedState ?: "UNKNOWN"}")
                } finally { gate.release.countDown(); response.cancel(true) }
            }
        }
    }

    @Test fun `real HTTP UNKNOWN registration returns503 before emitter`() {
        source.state = null
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().use { client ->
            val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/sse/turn")).GET().timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(503, response.statusCode()); assertTrue(response.body().contains("SERVER_ADMISSION_UNAVAILABLE"))
            assertEquals(0, relay.emitterCount())
        }
    }
}

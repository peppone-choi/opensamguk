package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPENSAM-94 — 프로필 아이콘 fan-out best-effort 테스트. JDK 내장 HttpServer(신규 의존성 없음)로
 * target 하나의 실패가 나머지 target 시도를 막지 않음(criterion 11)과 payload/토큰 헤더를 단언한다.
 */
class ProfileIconSyncPublisherTest {

    private val mapper = ObjectMapper()

    private class Stub(val status: Int) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hits = java.util.concurrent.atomic.AtomicInteger(0)
        val lastBody = java.util.concurrent.atomic.AtomicReference<String>("")
        val lastHeaders = ConcurrentHashMap<String, String>()
        val latch = CountDownLatch(1)
        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/api/internal/profile-icon-sync") { ex: HttpExchange ->
                hits.incrementAndGet()
                lastBody.set(ex.requestBody.readBytes().decodeToString())
                ex.requestHeaders.getFirst("X-Profile-Sync-Token")?.let { lastHeaders["token"] = it }
                ex.sendResponseHeaders(status, -1)
                ex.close()
                latch.countDown()
            }
            server.start()
        }

        fun stop() = server.stop(0)
    }

    private fun registry(vararg urls: String): ServerRegistry {
        val json = urls.mapIndexed { i, u -> """{"id":"srv$i","gameApiUrl":"$u"}""" }.joinToString(",", "[", "]")
        return ServerRegistry(json, "http://x", "http://y", "opensamguk", "srv", mapper)
    }

    @Test
    fun `a failing target does not stop the remaining targets`() {
        val failing = Stub(status = 500)
        val ok = Stub(status = 202)
        try {
            val publisher = ProfileIconSyncPublisher(registry(failing.baseUrl, ok.baseUrl), "")
            publisher.publish(userId = 7L, picture = "abcd1234.jpg", imgsvr = 1, grade = 5)

            assertTrue(ok.latch.await(3, TimeUnit.SECONDS), "second target must be attempted despite first 5xx")
            assertEquals(1, failing.hits.get())
            assertEquals(1, ok.hits.get())
            val body = mapper.readTree(ok.lastBody.get())
            assertEquals(7, body.path("userId").asInt())
            assertEquals("abcd1234.jpg", body.path("picture").asText())
            assertEquals(1, body.path("imgsvr").asInt())
            assertEquals(5, body.path("grade").asInt())
        } finally {
            failing.stop()
            ok.stop()
        }
    }

    @Test
    fun `configured token is sent as header`() {
        val ok = Stub(status = 202)
        try {
            ProfileIconSyncPublisher(registry(ok.baseUrl), "s3cret")
                .publish(userId = 7L, picture = "x.jpg", imgsvr = 0, grade = 1)
            assertTrue(ok.latch.await(3, TimeUnit.SECONDS))
            assertEquals("s3cret", ok.lastHeaders["token"])
        } finally {
            ok.stop()
        }
    }

    @Test
    fun `empty registry publishes to no target and does not throw`() {
        // 등록 서버 0개 — 조용한 no-op(초기 운영: 어드민이 서버 생성 전).
        ProfileIconSyncPublisher(registry(), "").publish(userId = 7L, picture = "x.jpg", imgsvr = 0, grade = 1)
    }
}

package opensamguk.gameapi.security

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class JdkServerAdmissionTransportTest {
    @Test fun `local HTTP body is completed and redirect is not followed`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { e ->
            val bytes = "{}".toByteArray()
            e.sendResponseHeaders(200, bytes.size.toLong())
            e.responseBody.use { it.write(bytes) }
        }
        server.createContext("/redirect") { e -> e.responseHeaders.add("Location", "/ok"); e.sendResponseHeaders(302, -1); e.close() }
        server.start()
        try {
            val transport = JdkServerAdmissionTransport()
            val base = "http://127.0.0.1:${server.address.port}"
            val ok = transport.fetch(URI("$base/ok"), "test-only-service", System.nanoTime(), ServerAdmissionDraftBudget.totalNanos)
            assertEquals(ServerAdmissionHttpResponse(200, "{}"), ok)
            assertEquals(302, transport.fetch(URI("$base/redirect"), "test-only-service", System.nanoTime(), ServerAdmissionDraftBudget.totalNanos).status)
        } finally { server.stop(0) }
    }

    @Test fun `oversized body is cancelled during consumption`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/large") { e ->
            val bytes = ByteArray(ServerAdmissionDraftBudget.MAX_BODY_BYTES + 1)
            e.sendResponseHeaders(200, bytes.size.toLong())
            e.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val error = assertThrows(Exception::class.java) {
                JdkServerAdmissionTransport().fetch(URI("http://127.0.0.1:${server.address.port}/large"),
                    "test-only-service", System.nanoTime(), ServerAdmissionDraftBudget.totalNanos)
            }
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it.message == "server admission body limit" })
        } finally { server.stop(0) }
    }

    @Test fun `headers alone do not complete body deadline and delayed body cannot become success`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        val headersSent = CountDownLatch(1)
        server.executor = executor
        server.createContext("/slow") { e ->
            e.sendResponseHeaders(200, 0)
            e.responseBody.flush()
            headersSent.countDown()
            try {
                check(release.await(5, TimeUnit.SECONDS))
                e.responseBody.use { it.write("{}".toByteArray()) }
            } catch (_: Exception) { e.close() }
        }
        server.start()
        try {
            val transport = JdkServerAdmissionTransport()
            val started = System.nanoTime()
            val error = assertThrows(Exception::class.java) {
                transport.fetch(URI("http://127.0.0.1:${server.address.port}/slow"),
                    "test-only-service", started, ServerAdmissionDraftBudget.totalNanos)
            }
            assertTrue(headersSent.await(1, TimeUnit.SECONDS), "시험 endpoint에 headers가 실제 도착해야 한다")
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is TimeoutException || it is HttpTimeoutException })
            println("admission_slow_body_elapsed_ms=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        } finally { release.countDown(); server.stop(0); executor.shutdownNow(); assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS)) }
    }
    @Test fun `elapsed queue time is deducted before real HTTP body wait`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor(); val release = CountDownLatch(1)
        val received = CountDownLatch(1)
        server.executor = executor
        server.createContext("/remaining") { e ->
            received.countDown(); e.sendResponseHeaders(200, 0); e.responseBody.flush()
            try { release.await(3, TimeUnit.SECONDS) } finally { e.close() }
        }
        server.start()
        try {
            val transport = JdkServerAdmissionTransport()
            val startFetch = System.nanoTime()
            val originalStart = startFetch - ServerAdmissionDraftBudget.totalNanos + TimeUnit.MILLISECONDS.toNanos(500)
            val error = assertThrows(Exception::class.java) {
                transport.fetch(URI("http://127.0.0.1:${server.address.port}/remaining"), "test-only-service",
                    originalStart, ServerAdmissionDraftBudget.totalNanos)
            }
            assertTrue(received.await(1, TimeUnit.SECONDS))
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is TimeoutException || it is HttpTimeoutException })
            assertTrue(System.nanoTime() - startFetch < ServerAdmissionDraftBudget.totalNanos)
        } finally { release.countDown(); server.stop(0); executor.shutdownNow(); assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS)) }
    }

    @Test fun `expired original deadline prevents even an HTTP request`() {
        val transport = JdkServerAdmissionTransport(nanoTime = { ServerAdmissionDraftBudget.totalNanos })
        val error = assertThrows(IllegalStateException::class.java) {
            transport.fetch(URI("http://127.0.0.1:1/must-not-send"), "test-only-service", 0, ServerAdmissionDraftBudget.totalNanos)
        }
        assertEquals("server admission deadline", error.message)
    }

}

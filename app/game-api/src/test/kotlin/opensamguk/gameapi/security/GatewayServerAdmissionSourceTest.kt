package opensamguk.gameapi.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
            if (clockReads++ == 0) 0L else ServerAdmissionDraftBudget.totalNanos
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

    @Test fun `one in-flight lookup makes overload fail immediately and releases its own permit`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = GatewayServerAdmissionSource("http://localhost", "pep", "test-only-service", ServerAdmissionTransport { _, _, _, _ ->
            started.countDown()
            check(release.await(1, TimeUnit.SECONDS))
            ServerAdmissionHttpResponse(200, body())
        }, { 0L }, maxConcurrent = 1)
        val running = CompletableFuture.supplyAsync { source.readFresh() }
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertEquals(ServerAdmissionRead.Unavailable, source.readFresh())
        } finally { release.countDown() }
        assertInstanceOf(ServerAdmissionRead.Known::class.java, running.get(1, TimeUnit.SECONDS))
        assertInstanceOf(ServerAdmissionRead.Known::class.java, source.readFresh())
    }
}

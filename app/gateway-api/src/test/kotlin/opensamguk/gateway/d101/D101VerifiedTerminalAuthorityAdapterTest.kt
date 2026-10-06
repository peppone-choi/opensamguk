package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.node.ObjectNode
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.infra.*
import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.junit.jupiter.api.Test

class D101VerifiedTerminalAuthorityAdapterTest {
    private val f = D101Fixture()
    private val origin = URI("http://127.0.0.1:9000")
    private val dispatch = D101DispatchIntentCandidate(2, "1".repeat(64), "2".repeat(64), "3".repeat(64))
    private val intentWire = f.mapper.writeValueAsBytes(f.intentTree())
    private val prepare = f.prepareBody()
    private val execution = D101Execution(
        f.codec.decode(intentWire, D101Fixture.hash(intentWire)), intentWire, prepare, D101Fixture.hash(prepare),
        D101ExecutionState.DISPATCH_INTENT, D101ExecutionState.DISPATCH_INTENT, 2, dispatch, null, null, null,
        Instant.ofEpochSecond(f.now - 20), Instant.ofEpochSecond(f.now - 10),
    )

    private fun result(status: String = "succeeded"): ObjectNode = f.mapper.valueToTree(linkedMapOf<String, Any?>(
        "schemaVersion" to 1, "serverId" to "pep", "worldId" to 1, "operationId" to f.operation,
        "kind" to "reset", "status" to status, "generation" to 0, "scenarioCode" to "scenario_3190",
        "serverName" to "빼섭", "approvalIntentSha256" to execution.intent.sha256,
        "approvalPlanSha256" to dispatch.approvalPlanSha256,
        "executionReceiptSha256" to dispatch.executionReceiptSha256,
        "targetFingerprint" to execution.intent.targetFingerprint,
        "rootRequestFingerprint" to dispatch.rootRequestFingerprint,
        "gatewayPayloadSha256" to execution.gatewayPayloadSha256,
        "verifyingRevision" to "2", "appSourceSha" to f.app, "imageDigests" to f.pins,
        "acceptedAtUtc" to Instant.ofEpochSecond(f.now - 8).toString(),
        "completedAtUtc" to Instant.ofEpochSecond(f.now - 4).toString(),
        "executionJournalSha256" to if (status == "succeeded") "4".repeat(64) else null,
        "actualRuntimeReceiptSha256" to if (status == "succeeded") "5".repeat(64) else null,
        "failureCode" to if (status == "succeeded") null else "WORKER_FAILED",
    ))

    private fun adapter(wire: ByteArray, proofValid: Boolean = true, calls: AtomicInteger = AtomicInteger()) =
        D101VerifiedTerminalAuthorityAdapter(D101RootExecutionResultClient(
            origin, { "fixture-token" }, f.authority(), f.clock, f.mapper,
            D101RootResultTransport { _, token, _ ->
                calls.incrementAndGet()
                assertEquals("fixture-token", token)
                val proof = if (proofValid) f.sign(D101RootExecutionResultClient.DOMAIN + wire) else ByteArray(64)
                D101RootResultHttpResponse(200, wire, listOf("rfc8032-fixture.${D101Fixture.b64(proof)}"))
            },
        ))

    @Test
    fun `signed success projects the exact original Root bytes and receipt`() {
        val wire = f.mapper.writeValueAsBytes(result())
        val sha = D101Fixture.hash(wire)
        val candidate = D101TerminalCandidate(2, sha)
        val calls = AtomicInteger()
        val evidence = adapter(wire, calls = calls).readVerified(execution, candidate)
        assertEquals(1, calls.get())
        assertEquals(sha, evidence.receiptSha256)
        assertContentEquals(wire, evidence.originalBytes())
        evidence.requireMatches(execution, candidate)
        evidence.originalBytes()[0] = 0
        assertContentEquals(wire, evidence.originalBytes())
    }

    @Test
    fun `a signed physical failure never becomes settlement evidence`() {
        val wire = f.mapper.writeValueAsBytes(result("failed"))
        assertFailsWith<D101ObservationUnavailable> {
            adapter(wire).readVerified(execution, D101TerminalCandidate(2, D101Fixture.hash(wire)))
        }
    }

    @Test
    fun `wrong proof operation and receipt are rejected by the verified client`() {
        val validWire = f.mapper.writeValueAsBytes(result())
        val sha = D101Fixture.hash(validWire)
        assertFailsWith<D101ObservationUnavailable> {
            adapter(validWire, proofValid = false).readVerified(execution, D101TerminalCandidate(2, sha))
        }
        val wrongOperation = f.mapper.writeValueAsBytes(result().put("operationId", "b".repeat(32)))
        assertFailsWith<D101ObservationUnavailable> {
            adapter(wrongOperation).readVerified(execution, D101TerminalCandidate(2, D101Fixture.hash(wrongOperation)))
        }
        assertFailsWith<D101ObservationUnavailable> {
            adapter(validWire).readVerified(execution, D101TerminalCandidate(2, "9".repeat(64)))
        }
    }

    @Test
    fun `candidate revision conflict and unavailable authority stay closed`() {
        val wire = f.mapper.writeValueAsBytes(result())
        val sha = D101Fixture.hash(wire)
        assertFailsWith<D101OperationConflict> {
            adapter(wire).readVerified(execution, D101TerminalCandidate(3, sha))
        }
        val calls = AtomicInteger()
        val unavailable = D101VerifiedTerminalAuthorityAdapter(D101RootExecutionResultClient(
            origin, { "fixture-token" }, clock = f.clock, mapper = f.mapper,
            transport = D101RootResultTransport { _, _, _ ->
                calls.incrementAndGet()
                error("must not fetch without trusted authority")
            },
        ))
        assertFailsWith<D101ObservationUnavailable> {
            unavailable.readVerified(execution, D101TerminalCandidate(2, sha))
        }
        assertEquals(0, calls.get())
    }
}

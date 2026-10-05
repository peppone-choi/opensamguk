package opensamguk.gateway.d101

import opensamguk.gateway.d101.api.D101ExecutionController
import opensamguk.gateway.d101.application.D101ExecutionService
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.infra.JdbcD101ExecutionStore
import opensamguk.gateway.d101.security.*
import opensamguk.gateway.security.InternalServiceTokenFilter
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/** Real service/controller/grant verification; synthetic state/source only.
 * The store mock proves refusal never reaches a settlement write. */
class D101TerminalStateGateTest {
    private val f = D101Fixture()
    private val resultWire = "synthetic Root proof".toByteArray()
    private val body = f.mapper.writeValueAsBytes(linkedMapOf(
        "schemaVersion" to 1, "verifyingRevision" to "2", "rootResultReceiptSha256" to D101Fixture.hash(resultWire),
    ))
    private val action = D101PurposeAction.SETTLE_REGISTRY

    @Test
    fun `prepared and recovery states return 409 before external proof or any write`() {
        for (state in listOf(D101ExecutionState.PREPARED, D101ExecutionState.RECOVERY_REQUIRED, D101ExecutionState.RECOVERED)) {
            val store = store(state)
            val calls = AtomicInteger()
            val source = D101TerminalAuthority { _, _ -> calls.incrementAndGet(); error("conflicting state read Root") }
            mvc(store, source).perform(request()).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("OPERATION_CONFLICT"))
            assertEquals(0, calls.get())
            Mockito.verify(store).query(f.operation)
            Mockito.verifyNoMoreInteractions(store)
        }
    }

    @Test
    fun `typed conflict from a verified source remains 409 instead of unavailable 503`() {
        val store = store(D101ExecutionState.DISPATCH_INTENT)
        val calls = AtomicInteger()
        val source = D101TerminalAuthority { _, _ -> calls.incrementAndGet(); throw D101OperationConflict() }
        mvc(store, source).perform(request()).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("OPERATION_CONFLICT"))
        assertEquals(1, calls.get())
        Mockito.verify(store).query(f.operation)
        Mockito.verifyNoMoreInteractions(store)
    }

    @Test
    fun `nonsemantic source failure still returns 503 without terminal writes`() {
        val store = store(D101ExecutionState.DISPATCH_INTENT)
        val calls = AtomicInteger()
        val source = D101TerminalAuthority { _, _ -> calls.incrementAndGet(); throw IllegalStateException("synthetic unavailable") }
        mvc(store, source).perform(request()).andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("OBSERVATION_UNAVAILABLE"))
        assertEquals(1, calls.get())
        Mockito.verify(store).query(f.operation)
        Mockito.verifyNoMoreInteractions(store)
    }

    @Test
    fun `existing authentication and purpose precede a conflicting terminal state`() {
        val store = store(D101ExecutionState.RECOVERED)
        val source = D101TerminalAuthority { _, _ -> error("unauthorized request read Root") }
        val mvc = mvc(store, source)
        mvc.perform(post(action.path(f.operation)).contentType(MediaType.APPLICATION_JSON).content(body).header("X-D101-Grant", header()))
            .andExpect(status().isUnauthorized)
        mvc.perform(post(action.path(f.operation)).contentType(MediaType.APPLICATION_JSON).content(body)
            .header("Authorization", "Bearer terminal-state-fixture").header("X-D101-Grant", "invalid"))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PURPOSE_GRANT_INVALID"))
        Mockito.verifyNoInteractions(store)
    }

    private fun mvc(store: JdbcD101ExecutionStore, source: D101TerminalAuthority) =
        MockMvcBuilders.standaloneSetup(D101ExecutionController(D101ExecutionService(f.json, f.requestCodec, f.verifier(), store, terminalAuthority = source)))
            .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(InternalServiceTokenFilter("terminal-state-fixture"))
            .build()

    private fun request() = post(action.path(f.operation)).contentType(MediaType.APPLICATION_JSON).content(body)
        .header("Authorization", "Bearer terminal-state-fixture").header("X-D101-Grant", header())

    private fun header(): String {
        val intent = f.intent()
        val request = D101PurposeRequest(action, f.operation, intent.targetFingerprint, intent.sha256, D101Fixture.hash(f.prepareBody()),
            intent.initialPublicRevision, action.method, action.path(f.operation), body)
        return f.header(f.claims(request))
    }

    private fun store(state: D101ExecutionState): JdbcD101ExecutionStore {
        val intent = f.intent()
        val safe = if (state in setOf(D101ExecutionState.RECOVERED, D101ExecutionState.RECOVERY_REQUIRED)) D101ExecutionState.DISPATCH_INTENT else state
        val prepare = f.prepareBody()
        val dispatch = if (safe == D101ExecutionState.PREPARED) null else D101DispatchIntentCandidate(2, "4".repeat(64), "5".repeat(64), "6".repeat(64))
        val instant = Instant.ofEpochSecond(f.now)
        val execution = D101Execution(intent, intent.originalBytes(), prepare, D101Fixture.hash(prepare), state, safe, 2, dispatch,
            null, null, null, instant, instant)
        return Mockito.mock(JdbcD101ExecutionStore::class.java).also { Mockito.`when`(it.query(f.operation)).thenReturn(execution) }
    }
}

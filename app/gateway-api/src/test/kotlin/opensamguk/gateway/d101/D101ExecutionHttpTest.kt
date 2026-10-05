package opensamguk.gateway.d101

import opensamguk.gateway.d101.api.D101ExecutionController
import opensamguk.gateway.d101.application.D101ExecutionService
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.infra.JdbcD101ExecutionStore
import opensamguk.gateway.d101.security.D101PurposeGrantVerifier
import opensamguk.gateway.security.InternalServiceTokenFilter
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.MediaType
import java.time.Instant
import kotlin.test.assertEquals
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

class D101ExecutionHttpTest {
    private val f = D101Fixture()
    private val base = D101PurposeAction.QUERY.path(f.operation)
    private val store = Mockito.mock(JdbcD101ExecutionStore::class.java)

    private fun mvc(
        verifier: D101PurposeGrantVerifier = D101PurposeGrantVerifier(f.json, clock = f.clock),
        reader: D101RecoveryBeginReader? = null,
    ) = MockMvcBuilders.standaloneSetup(D101ExecutionController(D101ExecutionService(f.json, f.requestCodec, verifier, store), reader))
            .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(InternalServiceTokenFilter("d101-internal-fixture"))
            .build()

    @Test
    fun `dedicated ingress preserves existing unauthorized schema and never invokes store`() {
        val mvc = mvc()
        mvc.perform(post(base + "/prepare").contentType(MediaType.APPLICATION_JSON).content(f.prepareBody()).header("X-D101-Grant", f.header()))
            .andExpect(status().isUnauthorized).andExpect(jsonPath("$.message").value("internal service authentication required"))
            .andExpect(jsonPath("$.status").value(401)).andExpect(jsonPath("$.code").doesNotExist())
        Mockito.verifyNoInteractions(store)
    }

    @Test
    fun `synthetically signed grant cannot replace missing production authority or touch database`() {
        mvc().perform(post(base + "/prepare").contentType(MediaType.APPLICATION_JSON).content(f.prepareBody())
            .header("Authorization", "Bearer d101-internal-fixture").header("X-D101-Grant", f.header()))
            .andExpect(status().isServiceUnavailable).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.schemaVersion").value(1)).andExpect(jsonPath("$.code").value("PURPOSE_AUTHORITY_UNAVAILABLE"))
            .andExpect(jsonPath("$.status").value(503))
        Mockito.verifyNoInteractions(store)
    }

    @Test
    fun `bounded raw intake query body duplicate headers and unknown fields fail closed`() {
        val mvc = mvc()
        for (wire in listOf(ByteArray(65537) { 0x20 }, """{"schemaVersion":1,"extra":1}""".toByteArray())) {
            mvc.perform(post(base + "/prepare").contentType(MediaType.APPLICATION_JSON).content(wire)
                .header("Authorization", "Bearer d101-internal-fixture").header("X-D101-Grant", f.header()))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }
        mvc.perform(get(base).content("x").header("Authorization", "Bearer d101-internal-fixture").header("X-D101-Grant", f.header(f.claims(f.request(D101PurposeAction.QUERY)))))
            .andExpect(status().isBadRequest)
        mvc.perform(get(base + "?bypass=true").header("Authorization", "Bearer d101-internal-fixture"))
            .andExpect(status().isBadRequest)
        mvc.perform(get(base.dropLast(1) + "%61").header("Authorization", "Bearer d101-internal-fixture"))
            .andExpect(status().is4xxClientError)
        mvc.perform(post(base + "/prepare").contentType(MediaType.APPLICATION_JSON).content(f.prepareBody())
            .header("Authorization", "Bearer d101-internal-fixture").header("X-D101-Grant", f.header(), f.header()))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PURPOSE_GRANT_INVALID"))
        Mockito.verifyNoInteractions(store)
    }

    @Test
    fun `query authenticates actual purpose before looking up absent operation`() {
        val request = f.request(D101PurposeAction.QUERY)
        mvc().perform(get(base).header("Authorization", "Bearer d101-internal-fixture").header("X-D101-Grant", f.header(f.claims(request))))
            .andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.code").value("PURPOSE_AUTHORITY_UNAVAILABLE"))
        Mockito.verifyNoInteractions(store)
        mvc(f.verifier()).perform(get(base).header("Authorization", "Bearer d101-internal-fixture").header("X-D101-Grant", f.header(f.claims(request))))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("OPERATION_NOT_FOUND"))
        Mockito.verify(store).query(f.operation)
    }
    @Test
    fun `recovery query admits purpose before committed begin and preserves original bytes`() {
        val request = f.request(D101PurposeAction.QUERY)
        val original = "SYNTHETIC committed BEGIN reader fixture\n".toByteArray()
        var reads = 0
        val reader = D101RecoveryBeginReader {
            reads++
            D101RecoveryBeginRead(it.intent.operationId, it.verifyingRevision, D101Fixture.hash(original), original)
        }
        mvc(reader = reader).perform(get(base).header("Authorization", "Bearer d101-internal-fixture")
            .header("X-D101-Grant", f.header(f.claims(request)))).andExpect(status().isServiceUnavailable)
        assertEquals(0, reads)
        Mockito.verifyNoInteractions(store)
        Mockito.`when`(store.query(f.operation)).thenReturn(queryExecution(D101ExecutionState.RECOVERY_REQUIRED))
        mvc(f.verifier(), reader).perform(get(base).header("Authorization", "Bearer d101-internal-fixture")
            .header("X-D101-Grant", f.header(f.claims(request)))).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.recoveryBeginReceiptSha256").value(D101Fixture.hash(original)))
            .andExpect(jsonPath("$.recoveryBeginReceiptBytesBase64url").value(D101Fixture.b64(original)))
        assertEquals(1, reads)
        Mockito.`when`(store.query(f.operation)).thenReturn(queryExecution(D101ExecutionState.DISPATCH_INTENT))
        mvc(f.verifier(), reader).perform(get(base).header("Authorization", "Bearer d101-internal-fixture")
            .header("X-D101-Grant", f.header(f.claims(request)))).andExpect(status().isOk)
            .andExpect(jsonPath("$.recoveryBeginReceiptSha256").doesNotExist())
            .andExpect(jsonPath("$.recoveryBeginReceiptBytesBase64url").doesNotExist())
        assertEquals(1, reads)
    }

    @Test
    fun `recovery query missing or stale committed reader cannot release empty receipt`() {
        Mockito.`when`(store.query(f.operation)).thenReturn(queryExecution(D101ExecutionState.RECOVERY_REQUIRED))
        val grant = f.header(f.claims(f.request(D101PurposeAction.QUERY)))
        mvc(f.verifier()).perform(get(base).header("Authorization", "Bearer d101-internal-fixture").header("X-D101-Grant", grant))
            .andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.code").value("OBSERVATION_UNAVAILABLE"))
        val original = "SYNTHETIC stale committed BEGIN fixture".toByteArray()
        val stale = D101RecoveryBeginReader { D101RecoveryBeginRead(it.intent.operationId, 3, D101Fixture.hash(original), original) }
        mvc(f.verifier(), stale).perform(get(base).header("Authorization", "Bearer d101-internal-fixture").header("X-D101-Grant", grant))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("OPERATION_CONFLICT"))
        val changed = D101RecoveryBeginReader { throw D101OperationConflict() }
        mvc(f.verifier(), changed).perform(get(base).header("Authorization", "Bearer d101-internal-fixture").header("X-D101-Grant", grant))
            .andExpect(status().isConflict)
        Mockito.verify(store, Mockito.times(3)).query(f.operation)
        Mockito.verifyNoMoreInteractions(store)
    }

    private fun queryExecution(state: D101ExecutionState): D101Execution {
        val intent = f.intent()
        val prepare = f.prepareBody()
        val now = Instant.ofEpochSecond(f.now)
        return D101Execution(intent, f.requestCodec.prepare(prepare).intentBytes(), prepare, D101Fixture.hash(prepare),
            state, D101ExecutionState.DISPATCH_INTENT, 2, D101DispatchIntentCandidate(2, "4".repeat(64), "5".repeat(64), "6".repeat(64)),
            null, null, null, now, now)
    }

}

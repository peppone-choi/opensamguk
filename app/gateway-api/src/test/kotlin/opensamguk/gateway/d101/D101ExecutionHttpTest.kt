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
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

class D101ExecutionHttpTest {
    private val f = D101Fixture()
    private val base = D101PurposeAction.QUERY.path(f.operation)
    private val store = Mockito.mock(JdbcD101ExecutionStore::class.java)

    private fun mvc(verifier: D101PurposeGrantVerifier = D101PurposeGrantVerifier(f.json, clock = f.clock)) =
        MockMvcBuilders.standaloneSetup(D101ExecutionController(D101ExecutionService(f.json, f.requestCodec, verifier, store)))
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
}

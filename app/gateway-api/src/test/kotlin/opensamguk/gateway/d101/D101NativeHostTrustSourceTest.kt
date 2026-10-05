package opensamguk.gateway.d101

import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.infra.*
import opensamguk.gateway.d101.security.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class D101NativeHostTrustSourceTest {
    private val f = D101Fixture()
    private val installation = "synthetic fixed bindings original".toByteArray()
    private val pin = D101Fixture.hash(installation)
    private fun source(action: String, response: Any) = D101NativeHostTrustSource(D101NativeHostReader {
        assertEquals(action,it); f.mapper.writeValueAsBytes(response)
    },pin)

    @Test fun `exact native bundle binds original reader bindings and freezes bytes`() {
        val originals=D101ApprovedPurposeAuthority.ORIGINAL_IDS.associateWith {
            D101Fixture.b64(if(it=="readerBindings") installation else "synthetic $it".toByteArray())
        }
        val trust=source("read-originals",mapOf("schemaVersion" to 1,"installationSha256" to pin,
            "manifestEnvelopeBase64url" to D101Fixture.b64("manifest".toByteArray()),
            "clockEnvelopeBase64url" to D101Fixture.b64("clock".toByteArray()),"originals" to originals)).readOriginals()
        assertArrayEquals(installation,trust.originals().getValue("readerBindings"))
        trust.originals().getValue("readerBindings").fill(0)
        assertArrayEquals(installation,trust.originals().getValue("readerBindings"))
    }
    @Test fun `token and selected envelope require fixed installation pin`() {
        assertEquals("synthetic-token", source("read-token",mapOf("schemaVersion" to 1,"installationSha256" to pin,
            "rootToken" to "synthetic-token")).rootToken())
        val wire="synthetic signed envelope".toByteArray()
        assertArrayEquals(wire,source("read-selected",mapOf("schemaVersion" to 1,"installationSha256" to pin,
            "selectedEnvelopeBase64url" to D101Fixture.b64(wire))).readOriginalEnvelope())
        for(response in listOf(mapOf("schemaVersion" to 1,"installationSha256" to "f".repeat(64),"rootToken" to "synthetic"),
            mapOf("schemaVersion" to 1,"installationSha256" to pin,"rootToken" to "bad\nvalue"),
            mapOf("schemaVersion" to 1,"installationSha256" to pin,"rootToken" to "synthetic","path" to "/caller"))) {
            assertThrows(D101PurposeAuthorityUnavailable::class.java) { source("read-token",response).rootToken() }
        }
    }
    @Test fun `missing or changed actual original cannot be replaced by matching outer labels`() {
        for(mode in listOf("missing","changed")) {
            val originals=D101ApprovedPurposeAuthority.ORIGINAL_IDS.associateWith { D101Fixture.b64(installation) }.toMutableMap()
            if(mode=="missing") originals.remove("approvalReceipt") else originals["readerBindings"]=D101Fixture.b64("changed".toByteArray())
            assertThrows(D101PurposeAuthorityUnavailable::class.java) {
                source("read-originals",mapOf("schemaVersion" to 1,"installationSha256" to pin,
                    "manifestEnvelopeBase64url" to D101Fixture.b64(installation),
                    "clockEnvelopeBase64url" to D101Fixture.b64(installation),"originals" to originals)).readOriginals()
            }
        }
    }
}

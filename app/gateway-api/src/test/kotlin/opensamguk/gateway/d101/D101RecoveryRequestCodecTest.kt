package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.D101RecoveryRequestCodec
import opensamguk.gateway.d101.domain.D101RequestInvalid
import opensamguk.gateway.d101.domain.D101StrictJson
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class D101RecoveryRequestCodecTest {
    private val codec = D101RecoveryRequestCodec(D101StrictJson(ObjectMapper()))
    private val root = "a".repeat(64)
    private val begin = "b".repeat(64)
    private val restored = "c".repeat(64)

    @Test
    fun `begin and close keep exact signed bindings`() {
        assertEquals(
            root,
            codec.begin("""{"schemaVersion":1,"verifyingRevision":"2","rootResultReceiptSha256":"$root"}""".toByteArray())
                .rootResultReceiptSha256,
        )
        val candidate = codec.close(
            """{"schemaVersion":1,"verifyingRevision":"2","recoveryBeginReceiptSha256":"$begin","recoveryResultReceiptSha256":"$restored"}""".toByteArray(),
        )
        assertEquals(2L, candidate.verifyingRevision)
        assertEquals(begin, candidate.recoveryBeginReceiptSha256)
        assertEquals(restored, candidate.recoveryResultReceiptSha256)
    }

    @Test
    fun `extra keys and noncanonical revision cannot acquire a mutation candidate`() {
        assertFailsWith<D101RequestInvalid> {
            codec.begin("""{"schemaVersion":1,"verifyingRevision":"2","rootResultReceiptSha256":"$root","allow":true}""".toByteArray())
        }
        assertFailsWith<D101RequestInvalid> {
            codec.close("""{"schemaVersion":1,"verifyingRevision":"02","recoveryBeginReceiptSha256":"$begin","recoveryResultReceiptSha256":"$restored"}""".toByteArray())
        }
    }
}

package opensamguk.gateway.d101.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.D101StrictJson
import opensamguk.gateway.d101.domain.D101RequestInvalid
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Immutable byte binding only. No origin, issuer, custody or purpose authority. */
internal class D101RawEvidenceBinding private constructor(
    val reference: D101Original6ScopeCheck.RawRef,
    private val bytes: ByteArray,
) {
    fun originalBytes(): ByteArray = bytes.copyOf()
    fun openOriginal(): InputStream = ByteArrayInputStream(bytes.copyOf())

    companion object {
        fun bind(reference: JsonNode, original: ByteArray, mapper: ObjectMapper = ObjectMapper()): D101RawEvidenceBinding {
            val json = D101StrictJson(mapper)
            val ref = D101Original6ScopeCheck(json).ref(reference)
            if (original.isEmpty() || original.size > 64 * 1024 * 1024 || original.size.toLong() != ref.byteLength) json.invalid()
            val frozen = original.copyOf()
            if (D101StrictJson.hash(frozen) != ref.sha256) json.invalid()
            return D101RawEvidenceBinding(ref, frozen)
        }

        fun requireUtf8(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
            if (offset < 0 || length <= 0 || offset > bytes.size || length > bytes.size - offset) throw D101RequestInvalid()
            try {
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val input = ByteBuffer.wrap(bytes, offset, length)
                val output = CharBuffer.allocate(8192)
                while (true) {
                    val result = decoder.decode(input, output, true)
                    if (result.isError) result.throwException()
                    if (result.isUnderflow) break
                    output.clear()
                }
                output.clear()
                while (true) {
                    val result = decoder.flush(output)
                    if (result.isError) result.throwException()
                    if (result.isUnderflow) break
                    output.clear()
                }
            } catch (_: Exception) { throw D101RequestInvalid() }
        }
    }
}

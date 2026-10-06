package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class D101RawEvidenceBindingTest {
    private val f = D101RawEvidenceFixture()

    @Test
    fun `byte binding preserves exact raw bytes and never normalizes opaque media`() {
        val bytes = byteArrayOf(0xff.toByte(), 0, 0x0a)
        val binding = D101RawEvidenceBinding.bind(f.ref("raw:opaque", bytes, "application/octet-stream"), bytes)
        assertArrayEquals(bytes, binding.originalBytes())
        assertArrayEquals(bytes, binding.openOriginal().use { it.readBytes() })
        val text = "한글\r\n".toByteArray()
        assertArrayEquals(text, D101RawEvidenceBinding.bind(f.ref("raw:text", text, "text/plain"), text).originalBytes())
    }

    @Test
    fun `original and reference mutations cannot change an existing frozen binding`() {
        val bytes = "AB".toByteArray()
        val ref = f.ref("raw:source", bytes)
        val binding = D101RawEvidenceBinding.bind(ref, bytes)
        bytes[0] = 0; ref.put("sha256", "f".repeat(64))
        val exposed = binding.originalBytes(); exposed[1] = 0
        assertArrayEquals("AB".toByteArray(), binding.originalBytes())
        assertEquals(D101StrictJson.hash("AB".toByteArray()), binding.reference.sha256)
        val first = binding.openOriginal(); first.close()
        assertArrayEquals("AB".toByteArray(), binding.openOriginal().use { it.readBytes() })
    }

    @Test
    fun `changed original bytes reject even when length and all reference labels are unchanged`() {
        val original = "AB".toByteArray()
        val ref = f.ref("raw:source", original)
        assertThrows<D101RequestInvalid> { D101RawEvidenceBinding.bind(ref, "AC".toByteArray()) }
        assertThrows<D101RequestInvalid> { D101RawEvidenceBinding.bind(ref.deepCopy().put("sha256", "e".repeat(64)), original) }
    }

    @Test
    fun `strict UTF8 scan crosses decoder buffers and refuses partial codepoints`() {
        val bytes = ("가".repeat(9000) + "🪐\n끝").toByteArray()
        D101RawEvidenceBinding.requireUtf8(bytes)
        assertThrows<D101RequestInvalid> { D101RawEvidenceBinding.requireUtf8(bytes, 1, 3) }
        assertThrows<D101RequestInvalid> { D101RawEvidenceBinding.requireUtf8(bytes, -1, 1) }
        assertThrows<D101RequestInvalid> { D101RawEvidenceBinding.requireUtf8(bytes, bytes.size, 1) }
    }

    @Test
    fun `RawRef exact shape types identity media and bounded length reject`() {
        val bytes = "{}".toByteArray()
        val changes = listOf<(ObjectNode) -> Unit>(
            { it.put("extra", true) }, { it.putNull("sha256") }, { it.remove("mediaType"); Unit },
            { it.put("byteLength", true) }, { it.put("byteLength", 2.0) }, { it.put("byteLength", 0) },
            { it.put("byteLength", 67108865) }, { it.put("byteLength", 3) },
            { it.put("logicalId", "/private/file") }, { it.put("logicalId", "raw:../escape") },
            { it.put("mediaType", "image/png") }, { it.put("sha256", "A".repeat(64)) },
        )
        for (change in changes) { val ref = f.ref("raw:source", bytes); change(ref)
            assertThrows<D101RequestInvalid> { D101RawEvidenceBinding.bind(ref, bytes) } }
        assertThrows<D101RequestInvalid> { D101RawEvidenceBinding.bind(f.ref("raw:source", bytes), byteArrayOf()) }
    }
}

/** Raw fixture only; no signer, issuer, native transport or installed authority. */
internal class D101RawEvidenceFixture {
    val mapper = ObjectMapper()
    fun obj(vararg pairs: Pair<String, Any>): ObjectNode = mapper.valueToTree(linkedMapOf(*pairs))
    fun wire(node: JsonNode): ByteArray = mapper.writeValueAsBytes(node)
    fun ref(id: String, bytes: ByteArray, media: String = "application/json") =
        obj("logicalId" to id, "sha256" to D101StrictJson.hash(bytes), "byteLength" to bytes.size, "mediaType" to media)
}

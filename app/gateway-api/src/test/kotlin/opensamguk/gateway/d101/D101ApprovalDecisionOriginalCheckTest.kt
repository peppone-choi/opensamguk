package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class D101ApprovalDecisionOriginalCheckTest {
    @Test
    fun `actual five parent bytes support twenty Unicode slices without rewriting newline bytes`() {
        val p = Packet()
        p.verify()
        // This is a decision byte subcheck, not a user scope or issuer grant.
        (p.receipt["issuer"] as ObjectNode).put("unproducedSyntheticLabel", true)
        p.verify()
    }

    @Test
    fun `rebound parent raw hashes do not excuse a changed decision fragment`() {
        val p = Packet()
        val id = "raw:parent-0"
        val changed = p.parents.getValue(id).copyOf()
        changed[p.start + "결정".toByteArray().size] = 'X'.code.toByte()
        p.parents[id] = changed
        p.rebindParent(0)
        assertThrows<D101RequestInvalid> { p.verify() }
        (p.receipt["decisionSlices"][0] as ObjectNode).put("startLine", 1)
        assertThrows<D101RequestInvalid> { p.verify() }
    }

    @Test
    fun `correctly rebound hashes cannot bless a slice inside a UTF8 codepoint`() {
        for (cutStart in listOf(true, false)) {
            val p = Packet()
            val slice = p.receipt["decisionSlices"][0] as ObjectNode
            val start = p.start + if (cutStart) 1 else 0
            val end = p.end - if (cutStart) 0 else 2
            slice.put("startByte", start); slice.put("endByte", end); slice.put("byteLength", end - start)
            slice.put("sha256", D101StrictJson.hash(p.parents.getValue("raw:parent-0").copyOfRange(start, end)))
            assertThrows<D101RequestInvalid> { p.verify() }
        }
    }

    @Test
    fun `whole parent malformed UTF8 rejects outside every referenced fragment`() {
        val p = Packet()
        val id = "raw:parent-0"
        val bytes = p.parents.getValue(id).copyOf(); bytes[0] = 0xc3.toByte(); bytes[1] = 0x28
        p.parents[id] = bytes; p.rebindParent(0)
        assertThrows<D101RequestInvalid> { p.verify() }
    }

    @Test
    fun `unused parent still requires valid whole original UTF8`() {
        val p = Packet()
        for (slice in p.receipt["decisionSlices"]) (slice as ObjectNode).put("parentLogicalId", "raw:parent-0")
        p.verify()
        val bytes = p.parents.getValue("raw:parent-4").copyOf()
        bytes[bytes.lastIndex] = 0xff.toByte()
        p.parents["raw:parent-4"] = bytes; p.rebindParent(4)
        assertThrows<D101RequestInvalid> { p.verify() }
    }

    @Test
    fun `line origin range count duplicate decision unknown parent and integral bounds reject`() {
        val changes = listOf<(Packet) -> Unit>(
            { (it.receipt["decisionSlices"][0] as ObjectNode).put("startLine", 1) },
            { (it.receipt["decisionSlices"][0] as ObjectNode).put("endByte", 9999) },
            { (it.receipt["decisionSlices"][0] as ObjectNode).put("startByte", true) },
            { (it.receipt["decisionSlices"][0] as ObjectNode).put("byteLength", 1.0) },
            { (it.receipt["decisionSlices"][1] as ObjectNode).put("decisionId", "D0") },
            { (it.receipt["decisionSlices"][0] as ObjectNode).put("parentLogicalId", "raw:unknown") },
            { (it.receipt["decisionSlices"] as ArrayNode).remove(0); Unit },
            { it.parents.remove("raw:parent-4"); Unit },
            { it.parents["raw:extra"] = "{}".toByteArray() },
            { (it.receipt["decisionParents"][0] as ObjectNode).put("sha256", "f".repeat(64)) },
        )
        for (change in changes) { val p = Packet(); change(p)
            assertThrows<D101RequestInvalid> { p.verify() } }
    }

    private class Packet {
        val f = D101RawEvidenceFixture()
        val prefix = "첫 줄\r\n".toByteArray()
        val fragment = "결정: 허용".toByteArray()
        val start = prefix.size
        val end = start + fragment.size
        val parents = (0..4).associate { "raw:parent-$it" to (prefix + fragment + "\n뒤 $it\n".toByteArray()) }.toMutableMap()
        val receipt = f.obj("schemaVersion" to 1, "kind" to "D101_APPROVAL_RECEIPT_V1", "scope" to emptyMap<String, String>(),
            "sourceAuthority" to "EXISTING_USER_DECISIONS_AND_FIXED_ISSUER", "documentCollectorId" to "synthetic-collector",
            "decisionParents" to (0..4).map { f.ref("raw:parent-$it", parents.getValue("raw:parent-$it"), "text/plain") },
            "decisionSlices" to (0..19).map { f.obj("decisionId" to "D$it", "parentLogicalId" to "raw:parent-${it / 4}",
                "startByte" to start, "endByte" to end, "startLine" to 2, "byteLength" to fragment.size, "sha256" to D101StrictJson.hash(fragment)) },
            "originalScopeRef" to f.ref("raw:scope", "{}".toByteArray()), "issuer" to emptyMap<String, String>(), "issuedAtUnix" to 1)
        fun rebindParent(index: Int) { (receipt["decisionParents"] as ArrayNode).set(index,
            f.ref("raw:parent-$index", parents.getValue("raw:parent-$index"), "text/plain")) }
        fun verify() = D101ApprovalDecisionOriginalCheck().verify(f.wire(receipt), parents)
    }
}

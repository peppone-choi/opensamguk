package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.JsonNode
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class D101RawEvidenceDagCheckTest {
    private val f = D101RawEvidenceFixture()
    private val check = D101RawEvidenceDagCheck()

    @Test
    fun `shared upstream graph binds all raw bytes while binary media is never parsed as JSON`() {
        val opaque = byteArrayOf(0xff.toByte(), 0, 0x7f)
        val leaf = f.wire(f.obj("origin" to "synthetic"))
        val a = f.wire(f.obj("leaf" to f.ref("raw:leaf", leaf), "opaque" to f.ref("raw:opaque", opaque, "application/octet-stream")))
        val root = f.wire(f.obj("first" to f.ref("raw:a", a), "again" to f.ref("raw:a", a)))
        check.verify("approvalReceipt", root, mapOf("raw:a" to a, "raw:leaf" to leaf, "raw:opaque" to opaque), emptyMap())
    }

    @Test
    fun `self and descendant original references reject through fully rebound raw aliases`() {
        for (owner in listOf("approvalReceipt", "combinedCiReceipt", "reviewBasis", "evidenceCatalog")) {
            for (key in listOf("deploymentCardSha256", "outerManifestSha256", "approvedReceiptProvenanceSha256")) {
                val alias = f.wire(f.obj("nested" to f.obj(key to "f".repeat(64))))
                val root = f.wire(f.obj("alias" to f.ref("raw:alias", alias)))
                assertThrows<D101RequestInvalid>("$owner $key") {
                    check.verify(owner, root, mapOf("raw:alias" to alias), emptyMap())
                }
            }
        }
        for (owner in listOf("approvalReceipt", "combinedCiReceipt")) {
            val alias = f.wire(f.obj("approvalIntentSha256" to "f".repeat(64)))
            assertThrows<D101RequestInvalid> {
                check.verify(owner, f.wire(f.obj("ref" to f.ref("raw:alias", alias))), mapOf("raw:alias" to alias), emptyMap())
            }
        }
    }

    @Test
    fun `future receipt kind and disguised known digest reject in raw JSON`() {
        val future = f.wire(f.obj("kind" to "D101_PEP_EXECUTION_CARD", "synthetic" to true))
        for (alias in listOf(f.wire(f.obj("kind" to "D101_EVIDENCE_CATALOG_V1")),
            f.wire(f.obj("unrecognizedField" to D101StrictJson.hash(future))),
            f.wire(f.obj("array" to listOf(D101StrictJson.hash(future)))))) {
            assertThrows<D101RequestInvalid> {
                check.verify("reviewBasis", f.wire(f.obj("ref" to f.ref("raw:alias", alias))),
                    mapOf("raw:alias" to alias), mapOf("deploymentCard" to future))
            }
        }
    }

    @Test
    fun `missing changed and contradictory logical references reject without fallback`() {
        val leaf = "{}".toByteArray()
        val ref = f.ref("raw:leaf", leaf)
        val root = f.wire(f.obj("ref" to ref))
        assertThrows<D101RequestInvalid> { check.verify("approvalReceipt", root, emptyMap(), emptyMap()) }
        assertThrows<D101RequestInvalid> { check.verify("approvalReceipt", root, mapOf("raw:leaf" to "[]".toByteArray()), emptyMap()) }
        val otherMedia = f.ref("raw:leaf", leaf, "application/octet-stream")
        assertThrows<D101RequestInvalid> {
            check.verify("approvalReceipt", f.wire(f.obj("a" to ref, "b" to otherMedia)), mapOf("raw:leaf" to leaf), emptyMap())
        }
        assertThrows<D101RequestInvalid> { check.verify("caller-owner", root, mapOf("raw:leaf" to leaf), emptyMap()) }
    }

    @Test
    fun `logical cycle rejects before revisiting an active original`() {
        val aPlaceholder = "{}".toByteArray()
        val b = f.wire(f.obj("back" to f.ref("raw:a", aPlaceholder)))
        val a = f.wire(f.obj("next" to f.ref("raw:b", b)))
        assertThrows<D101RequestInvalid> {
            check.verify("approvalReceipt", f.wire(f.obj("root" to f.ref("raw:a", a))), mapOf("raw:a" to a, "raw:b" to b), emptyMap())
        }
    }

    @Test
    fun `rebound malformed JSON and nested duplicate trailing BOM invalid UTF8 reject`() {
        val invalid = listOf("{\"nested\":{\"x\":1,\"x\":2}}".toByteArray(), "{} []".toByteArray(),
            "null".toByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "{}".toByteArray())
        for (bytes in invalid) {
            assertThrows<D101RequestInvalid> {
                check.verify("approvalReceipt", f.wire(f.obj("ref" to f.ref("raw:alias", bytes))), mapOf("raw:alias" to bytes), emptyMap())
            }
        }
    }

    @Test
    fun `named original stages remain fixed and catalog cannot absorb review or its descendants`() {
        val upstream = f.wire(f.obj("synthetic" to "input"))
        check.verify("reviewBasis", f.wire(f.obj("ref" to f.ref("commandPlan", upstream))), emptyMap(), mapOf("commandPlan" to upstream))
        for (id in listOf("reviewBasis", "evidenceCatalog", "deploymentCard", "approvedReceiptProvenance")) {
            assertThrows<D101RequestInvalid> {
                check.verify("evidenceCatalog", f.wire(f.obj("ref" to f.ref(id, upstream))), emptyMap(), mapOf(id to upstream))
            }
        }
        assertThrows<D101RequestInvalid> {
            check.verify("approvalReceipt", f.wire(f.obj("ref" to f.ref("approvalReceipt", upstream))), emptyMap(), mapOf("approvalReceipt" to upstream))
        }
    }
}

package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.node.ObjectNode
import opensamguk.gateway.d101.domain.D101PurposeAuthorityUnavailable
import opensamguk.gateway.d101.infra.D101DeploymentTrustInstaller
import opensamguk.gateway.d101.security.*
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.*

/** RFC8032 published test key and synthetic producers only. No host install. */
class D101ApprovedPurposeAuthorityTest {
    @Test fun `signature and raw originals alone cannot install authority`() {
        val h = HostFixture()
        assertFailsWith<D101PurposeAuthorityUnavailable> { h.authority(null).readVerified(h.intentSha) }
        assertFailsWith<D101PurposeAuthorityUnavailable> {
            h.authority(D101HostEvidenceVerifier { throw D101PurposeAuthorityUnavailable() }).readVerified(h.intentSha)
        }
        assertEquals(0, h.tokenReads)
    }

    @Test fun `fixed signed source returns exact intent and defensive original key bytes`() {
        val h = HostFixture()
        var reads = 0
        val authority = h.authority(D101HostEvidenceVerifier {
            reads++
            val raw = it.original("approvalIntent")
            assertContentEquals(h.originals.getValue("approvalIntent"), raw)
            raw.fill(0)
        })
        val result = authority.readVerified(h.intentSha)
        assertEquals(h.intentSha, result.approvedIntent.sha256)
        assertEquals(h.f.operation, result.approvedIntent.operationId)
        assertEquals(h.cardSha, result.deploymentCardSha256)
        val copy = result.publicKeySpki()
        copy.fill(0)
        assertContentEquals(h.f.publicDer, result.publicKeySpki())
        assertEquals(1, reads)
        assertFailsWith<D101PurposeAuthorityUnavailable> { authority.readVerified("f".repeat(64)) }
        assertEquals(1, reads)
    }

    @Test fun `missing extra or changed references never reach semantic producer`() {
        for (mode in listOf("missing", "extra", "changed", "source-failed")) {
            val h = HostFixture()
            var called = false
            when (mode) {
                "missing" -> h.originals.remove("selectedSourceReceipt")
                "extra" -> h.originals["unapproved"] = "{}".toByteArray()
                "changed" -> h.originals["selectedSourceReceipt"] = "changed".toByteArray()
                "source-failed" -> h.sourceFailure = true
            }
            assertFailsWith<D101PurposeAuthorityUnavailable>(mode) {
                h.authority(D101HostEvidenceVerifier { called = true }).readVerified(h.intentSha)
            }
            assertFalse(called, mode)
        }
    }

    @Test fun `original signature cannot use clock domain or changed manifest`() {
        for (mode in listOf("signature", "domain", "duplicate", "unknown", "null", "version-overflow", "trailing")) {
            val h = HostFixture()
            when (mode) {
                "signature" -> h.manifestEnvelope = h.envelope(h.manifestWire + byteArrayOf(32), D101ApprovedPurposeAuthority.TRUST_DOMAIN)
                "domain" -> h.manifestEnvelope = h.envelope(h.manifestWire, D101ApprovedPurposeAuthority.CLOCK_DOMAIN)
                "duplicate" -> h.manifestEnvelope = ("{\"schemaVersion\":1," + h.manifestEnvelope.toString(Charsets.UTF_8).drop(1)).toByteArray()
                "unknown" -> h.manifestEnvelope = h.f.mapper.writeValueAsBytes(h.f.mapper.readTree(h.manifestEnvelope).apply { (this as ObjectNode).put("unknown", true) })
                "null" -> h.manifestEnvelope = h.f.mapper.writeValueAsBytes(h.f.mapper.readTree(h.manifestEnvelope).apply { (this as ObjectNode).putNull("signatureBase64url") })
                "version-overflow" -> h.manifestEnvelope = h.manifestEnvelope.toString(Charsets.UTF_8).replace("\"schemaVersion\":1", "\"schemaVersion\":18446744073709551617").toByteArray()
                "trailing" -> h.manifestEnvelope += "{}".toByteArray()
            }
            assertFailsWith<D101PurposeAuthorityUnavailable>(mode) { h.authority().readVerified(h.intentSha) }
        }
    }

    @Test fun `signed manifest pins still require exact card origin and images`() {
        for (mode in listOf("op", "origin", "key", "image", "card", "provenance", "docker")) {
            val h = HostFixture()
            val manifest = h.f.mapper.readTree(h.manifestWire) as ObjectNode
            when (mode) {
                "op" -> manifest.put("operationId", "f".repeat(32))
                "origin" -> manifest.put("rootPrivateOrigin", "https://example.com:443")
                "key" -> manifest.put("keyId", "caller-key")
                "image" -> (manifest["newImageDigests"] as ObjectNode).put("game-api", "sha256:" + "f".repeat(64))
                "card" -> manifest.put("deploymentCardSha256", "f".repeat(64))
                "provenance" -> manifest.put("approvedReceiptProvenanceSha256", "f".repeat(64))
                "docker" -> manifest.put("dockerSourceSha", "f".repeat(40))
            }
            val changed = h.f.mapper.writeValueAsBytes(manifest)
            h.manifestEnvelope = h.envelope(changed, D101ApprovedPurposeAuthority.TRUST_DOMAIN)
            assertFailsWith<D101PurposeAuthorityUnavailable>(mode) {
                h.authority(manifestSha = D101Fixture.hash(changed)).readVerified(h.intentSha)
            }
        }
    }

    @Test fun `both observed clocks and completion freshness are required`() {
        for (mode in listOf("future-root", "future-gateway", "skew", "expired", "age", "long-lease", "callback-expiry", "rollback")) {
            val h = HostFixture()
            when (mode) {
                "future-root" -> h.clockTree.put("rootObservedAtUnix", h.f.now + 1)
                "future-gateway" -> h.clockTree.put("gatewayObservedAtUnix", h.f.now + 1)
                "skew" -> h.clockTree.put("gatewayObservedAtUnix", h.f.now - 2)
                "expired" -> h.clockTree.put("expiresAtUnix", h.f.now)
                "age" -> { h.clockTree.put("rootObservedAtUnix", h.f.now - 30); h.clockTree.put("gatewayObservedAtUnix", h.f.now - 30) }
                "long-lease" -> h.clockTree.put("expiresAtUnix", h.f.now + 31)
            }
            assertFailsWith<D101PurposeAuthorityUnavailable>(mode) {
                h.authority(D101HostEvidenceVerifier {
                    if (mode == "callback-expiry") h.f.clock.epoch += 30
                    if (mode == "rollback") h.f.clock.epoch--
                }).readVerified(h.intentSha)
            }
        }
    }

    @Test fun `atomic install verifies before credential and stale source releases no token`() {
        val h = HostFixture()
        assertFailsWith<D101PurposeAuthorityUnavailable> {
            D101DeploymentTrustInstaller(h.pins(), h.source, clock = h.f.clock, mapper = h.f.mapper).install()
        }
        val pair = D101DeploymentTrustInstaller(h.pins(), h.source, D101HostEvidenceVerifier {}, h.f.clock, h.f.mapper).install()
        assertEquals(0, h.tokenReads)
        assertEquals("synthetic-root-token", pair.root.token())
        assertEquals(1, h.tokenReads)
        h.f.clock.epoch += 30
        assertFailsWith<D101PurposeAuthorityUnavailable> { pair.root.token() }
        assertEquals(1, h.tokenReads)
    }

    private class HostFixture {
        val f = D101Fixture()
        val originals = D101ApprovedPurposeAuthority.ORIGINAL_IDS.filterNot { it in setOf("approvalIntent", "deploymentCard") }
            .associateWith { "SYNTHETIC_ONLY $it".toByteArray() }.toMutableMap()
        val intentSha: String
        val cardSha: String
        val manifestWire: ByteArray
        var manifestEnvelope: ByteArray
        val clockTree: ObjectNode
        var sourceFailure = false
        var tokenReads = 0
        val source = object : D101FixedHostTrustSource {
            override fun readOriginals(): D101HostTrustOriginals {
                if (sourceFailure) error("synthetic source missing")
                return D101HostTrustOriginals(manifestEnvelope,
                    envelope(f.mapper.writeValueAsBytes(clockTree), D101ApprovedPurposeAuthority.CLOCK_DOMAIN), originals)
            }
            override fun rootToken(): String { tokenReads++; return "synthetic-root-token" }
        }
        init {
            val tree = f.intentTree()
            for (id in listOf("approvalReceipt", "combinedCiReceipt", "selectedSourceReceipt", "isolatedSeedTickReceipt", "spaceInventoryReceipt")) {
                tree.put(id + "Sha256", D101Fixture.hash(originals.getValue(id)))
            }
            originals["approvalIntent"] = f.mapper.writeValueAsBytes(tree)
            intentSha = D101Fixture.hash(originals.getValue("approvalIntent"))
            val card = linkedMapOf<String, Any>(
                "schemaVersion" to 1, "kind" to "D101_PEP_EXECUTION_CARD", "operationId" to f.operation,
                "approvalIntentSha256" to intentSha, "appSourceSha" to f.app, "dockerSourceSha" to "c".repeat(40),
                "oldImageDigests" to f.pins, "newImageDigests" to f.pins)
            for (id in listOf("configInventory", "commandPlan", "recoveryPlan", "readerBindings", "evidenceCatalog", "reviewBasis")) {
                card[id + "Sha256"] = D101Fixture.hash(originals.getValue(id))
            }
            originals["deploymentCard"] = f.mapper.writeValueAsBytes(card)
            cardSha = D101Fixture.hash(originals.getValue("deploymentCard"))
            manifestWire = f.mapper.writeValueAsBytes(linkedMapOf(
                "schemaVersion" to 1, "kind" to "D101_HOST_TRUST_V1", "operationId" to f.operation,
                "approvalIntentSha256" to intentSha, "deploymentCardSha256" to cardSha,
                "approvedReceiptProvenanceSha256" to D101Fixture.hash(originals.getValue("approvedReceiptProvenance")),
                "keyId" to "rfc8032-fixture", "publicKeySpkiSha256" to D101Fixture.hash(f.publicDer),
                "signingKeyEnvelopeSha256" to "e".repeat(64), "rootPrivateOrigin" to "http://deployer:8080",
                "appSourceSha" to f.app, "dockerSourceSha" to "c".repeat(40), "oldImageDigests" to f.pins, "newImageDigests" to f.pins))
            manifestEnvelope = envelope(manifestWire, D101ApprovedPurposeAuthority.TRUST_DOMAIN)
            clockTree = f.mapper.valueToTree(linkedMapOf(
                "schemaVersion" to 1, "kind" to "D101_CLOCK_AGREEMENT_V1", "operationId" to f.operation,
                "approvalIntentSha256" to intentSha, "rootObservedAtUnix" to f.now, "gatewayObservedAtUnix" to f.now,
                "expiresAtUnix" to f.now + 30))
        }
        fun envelope(wire: ByteArray, domain: String): ByteArray = f.mapper.writeValueAsBytes(linkedMapOf(
            "schemaVersion" to 1, "originalBytesBase64url" to D101Fixture.b64(wire),
            "signatureBase64url" to D101Fixture.b64(f.sign(domain.toByteArray(Charsets.US_ASCII) + wire))))
        fun pins(manifestSha: String = D101Fixture.hash(manifestWire)) = D101DeploymentTrustPins(f.operation, intentSha, manifestSha,
            URI("http://deployer:8080"), "rfc8032-fixture", f.publicDer, D101Fixture.hash(f.publicDer),
            f.publicDer, D101Fixture.hash(f.publicDer), "e".repeat(64))
        fun authority(verifier: D101HostEvidenceVerifier? = D101HostEvidenceVerifier {},
                      manifestSha: String = D101Fixture.hash(manifestWire)) =
            D101ApprovedPurposeAuthority(pins(manifestSha), source, verifier, f.clock, f.mapper)
    }
}

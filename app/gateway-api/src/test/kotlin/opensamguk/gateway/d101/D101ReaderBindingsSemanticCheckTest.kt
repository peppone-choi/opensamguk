package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.node.ObjectNode
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.infra.*
import opensamguk.gateway.d101.security.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI

/** Synthetic native transport and published RFC key, never host installation evidence. */
class D101ReaderBindingsSemanticCheckTest {
    @Test
    fun `fixed reader consumes actual signed snapshots while unresolved originals stay closed`() {
        val packet = Packet()
        val checks = packet.checks()
        checks.getValue("readerBindings").verify(packet.reader(), packet.intent)
        assertEquals(2, packet.reads)
        val privateCopy = Packet(readerBindingPath = "/synthetic/reader-copy.json")
        privateCopy.checks().getValue("readerBindings").verify(privateCopy.reader(), privateCopy.intent)
        assertEquals(2, privateCopy.reads)
        for (id in D101ApprovedPurposeAuthority.ORIGINAL_IDS - setOf("approvalIntent", "deploymentCard", "commandPlan", "readerBindings")) {
            assertThrows<D101PurposeAuthorityUnavailable>(id) {
                checks.getValue(id).verify(packet.originals.getValue(id), packet.intent)
            }
        }
        assertThrows<D101PurposeAuthorityUnavailable> {
            D101HostSemanticVerifier(packet.f.codec, packet.intent.sha256, checks).verifyOriginals(packet.verified())
        }
    }

    @Test
    fun `absent fixed reader or authenticated clock never becomes a row success`() {
        val packet = Packet()
        assertThrows<D101PurposeAuthorityUnavailable> { packet.checks(withSource = false).getValue("readerBindings").verify(packet.reader(), packet.intent) }
        assertEquals(0, packet.reads)
        assertThrows<D101PurposeAuthorityUnavailable> { packet.checks(withClock = false).getValue("readerBindings").verify(packet.reader(), packet.intent) }
        assertEquals(0, packet.reads)
        assertThrows<D101PurposeAuthorityUnavailable> { packet.checks().getValue("readerBindings").verify("{}".toByteArray(), packet.intent) }
    }

    @Test
    fun `reader schema and logical paths reject with consistently rebound card and manifest hashes`() {
        val changes = listOf<(ObjectNode) -> Unit>(
            { it.put("schemaVersion", 2) }, { it.put("schemaVersion", "1") }, { it.putNull("kind") },
            { it.put("kind", "OTHER") }, { it.put("extra", true) }, { it.remove("clockFile"); Unit },
            { (it["originalFiles"] as ObjectNode).remove("reviewBasis"); Unit },
            { (it["originalFiles"] as ObjectNode).put("extra", "/synthetic/other.json") },
            { (it["originalFiles"] as ObjectNode).putNull("reviewBasis") },
            { (it["originalFiles"] as ObjectNode).put("approvalIntent", 1) },
            { (it["originalFiles"] as ObjectNode).put("approvalIntent", "relative.json") },
            { (it["originalFiles"] as ObjectNode).put("approvalIntent", "/synthetic/../approval.json") },
            { it.put("manifestFile", "relative.json") }, { it.put("clockFile", "/synthetic/./clock.json") },
            { it.put("rootTokenFile", "/") }, { it.put("selectedEnvelopeFile", "/synthetic//selected.json") },
            { it.put("selectedEnvelopeFile", "/synthetic/selected\u0000.json") },
        )
        for ((index, change) in changes.withIndex()) {
            val packet = Packet(changeReader = change)
            assertThrows<D101PurposeAuthorityUnavailable>("reader semantic case $index") {
                packet.checks().getValue("readerBindings").verify(packet.reader(), packet.intent)
            }
        }
    }

    @Test
    fun `reader malformed wire rejects despite matching parent raw hashes`() {
        val changes = listOf<(ByteArray) -> ByteArray>(
            { it.toString(Charsets.UTF_8).replaceFirst("{", "{\"schemaVersion\":1,").toByteArray() },
            { it + " {}".toByteArray() },
            { byteArrayOf(0xc3.toByte(), 0x28) },
            { byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + it },
        )
        for ((index, change) in changes.withIndex()) {
            val packet = Packet(changeWire = change)
            assertThrows<D101PurposeAuthorityUnavailable>("reader wire $index") {
                packet.checks().getValue("readerBindings").verify(packet.reader(), packet.intent)
            }
        }
    }

    @Test
    fun `native original fourteen drift rejects before and after the reader check`() {
        for (readNumber in listOf(1, 2)) {
            for (id in D101ApprovedPurposeAuthority.ORIGINAL_IDS) {
                val packet = Packet(driftId = id, driftRead = readNumber)
                assertThrows<D101PurposeAuthorityUnavailable>("read $readNumber original $id") {
                    packet.checks().getValue("readerBindings").verify(packet.reader(), packet.intent)
                }
            }
        }
    }

    @Test
    fun `actual envelope raw bytes signature domain and key cannot substitute for authenticated snapshots`() {
        val changes = listOf<(String, ByteArray) -> ByteArray>(
            { _, wire -> val f = D101Fixture(); val node = f.mapper.readTree(wire) as ObjectNode
                node.put("signatureBase64url", D101Fixture.b64(ByteArray(64))); f.mapper.writeValueAsBytes(node) },
            { _, wire -> val f = D101Fixture(); val node = f.mapper.readTree(wire) as ObjectNode
                node.put("originalBytesBase64url", D101Fixture.b64("{}".toByteArray()))
                node.put("signatureBase64url", D101Fixture.b64(f.sign("other-domain\n".toByteArray() + "{}".toByteArray())))
                f.mapper.writeValueAsBytes(node) },
            { domain, wire -> val f = D101Fixture(); val node = f.mapper.readTree(wire) as ObjectNode
                node.put("originalBytesBase64url", D101Fixture.b64("{}".toByteArray()))
                node.put("signatureBase64url", D101Fixture.b64(f.sign(domain.toByteArray() + "{}".toByteArray())))
                f.mapper.writeValueAsBytes(node) },
        )
        for (domain in listOf(D101ApprovedPurposeAuthority.TRUST_DOMAIN, D101ApprovedPurposeAuthority.CLOCK_DOMAIN)) {
            for ((index, change) in changes.withIndex()) {
                val packet = Packet(envelopeChange = { current, wire -> if (current == domain) change(current, wire) else wire })
                assertThrows<D101PurposeAuthorityUnavailable>("envelope $domain case $index") {
                    packet.checks().getValue("readerBindings").verify(packet.reader(), packet.intent)
                }
            }
        }
        val packet = Packet()
        val foreignAnchor = D101Fixture.hex("302a300506032b65700321003d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        assertThrows<D101PurposeAuthorityUnavailable> {
            packet.checks(anchor = foreignAnchor).getValue("readerBindings").verify(packet.reader(), packet.intent)
        }
    }

    @Test
    fun `signed clock scope and original freshness bounds remain mandatory`() {
        val now = D101Fixture().now
        val changes = listOf<(ObjectNode) -> Unit>(
            { it.put("schemaVersion", "1") }, { it.put("kind", "OTHER") },
            { it.put("operationId", "f".repeat(32)) }, { it.put("approvalIntentSha256", "f".repeat(64)) },
            { it.put("rootObservedAtUnix", now + 1) }, { it.put("gatewayObservedAtUnix", now + 1) },
            { it.put("rootObservedAtUnix", now - 2) }, { it.put("gatewayObservedAtUnix", now - 2) },
            { it.put("rootObservedAtUnix", now - 30) }, { it.put("gatewayObservedAtUnix", now - 30) },
            { it.put("expiresAtUnix", now) }, { it.put("expiresAtUnix", now + 31) },
            { it.put("rootObservedAtUnix", 0) }, { it.put("extra", true) },
        )
        for ((index, change) in changes.withIndex()) {
            val packet = Packet(changeClock = change)
            assertThrows<D101PurposeAuthorityUnavailable>("signed clock case $index") {
                packet.checks().getValue("readerBindings").verify(packet.reader(), packet.intent)
            }
        }
    }

    @Test
    fun `time movement during native reads and a different intent cannot refresh the original agreement`() {
        for (move in listOf(-1L, 31L)) {
            val packet = Packet(moveClockAfterRead = move)
            assertThrows<D101PurposeAuthorityUnavailable> {
                packet.checks().getValue("readerBindings").verify(packet.reader(), packet.intent)
            }
        }
        val packet = Packet()
        val other = packet.f.intent(packet.f.intentTree().put("initialPublicRevision", "2"))
        assertThrows<D101PurposeAuthorityUnavailable> { packet.checks().getValue("readerBindings").verify(packet.reader(), other) }
    }

    private class Packet(
        readerBindingPath: String = "/etc/opensamguk/d101/reader-installation.json",
        changeReader: (ObjectNode) -> Unit = {},
        changeWire: (ByteArray) -> ByteArray = { it },
        changeClock: (ObjectNode) -> Unit = {},
        private val driftId: String? = null,
        private val driftRead: Int = 2,
        private val envelopeChange: (String, ByteArray) -> ByteArray = { _, wire -> wire },
        private val moveClockAfterRead: Long = 0,
    ) {
        val f = D101Fixture()
        val originals = D101ApprovedPurposeAuthority.ORIGINAL_IDS.associateWith {
            f.mapper.writeValueAsBytes(mapOf("syntheticUnproduced" to it))
        }.toMutableMap()
        val intent: D101ApprovalIntent
        val manifest: ByteArray
        val agreement: ByteArray
        var reads = 0

        init {
            val files = D101ApprovedPurposeAuthority.ORIGINAL_IDS.associateWith { id ->
                if (id == "readerBindings") readerBindingPath else "/synthetic/$id.json"
            }
            val binding: ObjectNode = f.mapper.valueToTree(linkedMapOf(
                "schemaVersion" to 1, "kind" to "D101_NATIVE_READER_BINDINGS_V1", "manifestFile" to "/synthetic/manifest.json",
                "clockFile" to "/synthetic/clock.json", "originalFiles" to files,
                "rootTokenFile" to "/synthetic/token-reference.json", "selectedEnvelopeFile" to "/synthetic/selected.json",
            ))
            changeReader(binding)
            originals["readerBindings"] = changeWire(f.mapper.writeValueAsBytes(binding))
            val tree = f.intentTree()
            for (id in RECEIPTS) tree.put(id + "Sha256", D101Fixture.hash(originals.getValue(id)))
            originals["approvalIntent"] = f.mapper.writeValueAsBytes(tree)
            intent = f.codec.decode(originals.getValue("approvalIntent"), D101Fixture.hash(originals.getValue("approvalIntent")))
            val card: ObjectNode = f.mapper.valueToTree(linkedMapOf(
                "schemaVersion" to 1, "kind" to "D101_PEP_EXECUTION_CARD", "operationId" to f.operation,
                "approvalIntentSha256" to intent.sha256, "appSourceSha" to f.app, "dockerSourceSha" to "c".repeat(40),
                "oldImageDigests" to f.pins, "newImageDigests" to f.pins,
            ))
            for (id in REFERENCES) card.put(id + "Sha256", D101Fixture.hash(originals.getValue(id)))
            originals["deploymentCard"] = f.mapper.writeValueAsBytes(card)
            manifest = f.mapper.writeValueAsBytes(linkedMapOf(
                "schemaVersion" to 1, "kind" to "D101_HOST_TRUST_V1", "operationId" to f.operation,
                "approvalIntentSha256" to intent.sha256, "deploymentCardSha256" to D101Fixture.hash(originals.getValue("deploymentCard")),
                "approvedReceiptProvenanceSha256" to D101Fixture.hash(originals.getValue("approvedReceiptProvenance")),
                "keyId" to "rfc8032-fixture", "publicKeySpkiSha256" to D101Fixture.hash(f.publicDer),
                "signingKeyEnvelopeSha256" to "e".repeat(64), "rootPrivateOrigin" to "http://deployer:8080",
                "appSourceSha" to f.app, "dockerSourceSha" to "c".repeat(40), "oldImageDigests" to f.pins, "newImageDigests" to f.pins,
            ))
            val clock: ObjectNode = f.mapper.valueToTree(linkedMapOf(
                "schemaVersion" to 1, "kind" to "D101_CLOCK_AGREEMENT_V1", "operationId" to f.operation,
                "approvalIntentSha256" to intent.sha256, "rootObservedAtUnix" to f.now,
                "gatewayObservedAtUnix" to f.now, "expiresAtUnix" to f.now + 30,
            ))
            changeClock(clock)
            agreement = f.mapper.writeValueAsBytes(clock)
        }

        fun reader() = originals.getValue("readerBindings").copyOf()
        fun verified(withClock: Boolean = true) = D101VerifiedHostOriginals(originals + mapOf("trustManifest" to manifest) +
            if (withClock) mapOf("clockAgreement" to agreement) else emptyMap())
        fun checks(withSource: Boolean = true, withClock: Boolean = true, anchor: ByteArray = f.publicDer): Map<String, D101HostOriginalSemanticCheck> {
            val installationSha = D101Fixture.hash(reader())
            val source = if (!withSource) null else D101NativeHostTrustSource(D101NativeHostReader { action ->
                check(action == "read-originals") // Token or command files are never read here.
                reads++
                val current = originals.mapValues { it.value.copyOf() }.toMutableMap()
                if (driftId != null && reads == driftRead) current[driftId] = "different original".toByteArray()
                fun envelope(wire: ByteArray, domain: String) = envelopeChange(domain, f.mapper.writeValueAsBytes(mapOf(
                    "schemaVersion" to 1, "originalBytesBase64url" to D101Fixture.b64(wire),
                    "signatureBase64url" to D101Fixture.b64(f.sign(domain.toByteArray(Charsets.US_ASCII) + wire)),
                )))
                val response = f.mapper.writeValueAsBytes(mapOf(
                    "schemaVersion" to 1, "installationSha256" to installationSha,
                    "manifestEnvelopeBase64url" to D101Fixture.b64(envelope(manifest, D101ApprovedPurposeAuthority.TRUST_DOMAIN)),
                    "clockEnvelopeBase64url" to D101Fixture.b64(envelope(agreement, D101ApprovedPurposeAuthority.CLOCK_DOMAIN)),
                    "originals" to current.mapValues { D101Fixture.b64(it.value) },
                ))
                if (reads == 1) f.clock.epoch += moveClockAfterRead
                response
            }, installationSha, f.mapper)
            val pins = D101DeploymentTrustPins(f.operation, intent.sha256, D101Fixture.hash(manifest), URI("http://deployer:8080"),
                "rfc8032-fixture", anchor, D101Fixture.hash(anchor), f.publicDer, D101Fixture.hash(f.publicDer), "e".repeat(64))
            return D101InstalledSemanticChecks(verified(withClock), pins, f.mapper, source, null, f.clock).fixedChecks()
        }
    }

    companion object {
        private val RECEIPTS = listOf("approvalReceipt", "combinedCiReceipt", "selectedSourceReceipt", "isolatedSeedTickReceipt", "spaceInventoryReceipt")
        private val REFERENCES = listOf("configInventory", "commandPlan", "recoveryPlan", "readerBindings", "evidenceCatalog", "reviewBasis")
    }
}

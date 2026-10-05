package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.node.ObjectNode
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class D101PurposeGrantVerifierTest {
    private val f = D101Fixture()

    @Test
    fun `shared go jdk golden preserves lf original der and message bytes`() {
        val wire = requireNotNull(javaClass.getResourceAsStream("/d101/ed25519-interop-v1.json")).use { it.readBytes() }
        val fixture = f.mapper.readTree(wire)
        assertEquals(1, fixture["schemaVersion"].intValue())
        val der = D101Fixture.hex(fixture["publicKeySpkiDerHex"].textValue())
        assertArrayEquals(f.publicDer, der)
        val key = D101Ed25519.decodePublicKey(der, fixture["publicKeySpkiDerSha256"].textValue())
        assertEquals(2, fixture["vectors"].size())
        fixture["vectors"].forEach { vector ->
            val domain = D101Fixture.hex(vector["domainHex"].textValue())
            val original = D101Fixture.hex(vector["originalJsonHex"].textValue())
            val message = D101Fixture.hex(vector["messageHex"].textValue())
            val signature = D101Fixture.hex(vector["signatureHex"].textValue())
            val expectedDomain = when (vector["name"].textValue()) {
                "grant-lf-original-json" -> D101PurposeGrantVerifier.DOMAIN
                "result-lf-original-json" -> "OPENSAMGUK-D101-RESULT-V1\n".toByteArray(Charsets.US_ASCII)
                else -> error("unknown shared fixture vector")
            }
            assertArrayEquals(expectedDomain, domain)
            assertEquals(0x0a.toByte(), domain.last())
            assertEquals(1, domain.count { it == 0x0a.toByte() })
            assertArrayEquals(domain + original, message)
            assertTrue(D101Ed25519.verify(key, message, signature))
            assertArrayEquals(signature, f.sign(message))
            assertFalse(D101Ed25519.verify(key, domain.dropLast(1).toByteArray() + original, signature))
            assertFalse(D101Ed25519.verify(key, message + byteArrayOf(0x0a), signature))
        }
    }

    @Test
    fun `published rfc8032 test1 signature and imported fixture seed agree`() {
        val expected = D101Fixture.hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        )
        val key = D101Ed25519.decodePublicKey(f.publicDer, D101Fixture.hash(f.publicDer))
        assertTrue(D101Ed25519.verify(key, byteArrayOf(), expected))
        assertArrayEquals(expected, f.sign(byteArrayOf()))
        assertFalse(D101Ed25519.verify(key, byteArrayOf(1), expected))
    }

    @Test
    fun `original claim bytes lf domain and nonce are separate from logical identity`() {
        assertEquals(0x0a.toByte(), D101PurposeGrantVerifier.DOMAIN.last())
        assertEquals(1, D101PurposeGrantVerifier.DOMAIN.count { it == 0x0a.toByte() })
        val request = f.request()
        val claims = f.claims(request)
        val compact = f.mapper.writeValueAsBytes(claims)
        val pretty = f.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(claims)
        val verified = f.verifier().verify(listOf(f.headerBytes(compact)), request)
        val prettyVerified = f.verifier().verify(listOf(f.headerBytes(pretty)), request)
        assertEquals(D101Fixture.hash(compact), verified.grantSha256)
        assertNotEquals(verified.grantSha256, prettyVerified.grantSha256)
        val changedNonce = f.claims(request).put("nonce", "4".repeat(32))
        val second = f.verifier().verify(listOf(f.header(changedNonce)), request)
        assertEquals(verified.operationId, second.operationId)
        assertEquals(verified.gatewayPayloadSha256, second.gatewayPayloadSha256)
        assertEquals(verified.targetFingerprint, second.targetFingerprint)
        val domains = listOf(
            "OPENSAMGUK-D101-GRANT-V1".toByteArray(),
            "OPENSAMGUK-D101-GRANT-V1\\n".toByteArray(),
            "OPENSAMGUK-D101-GRANT-V1\n\n".toByteArray(),
            "OPENSAMGUK-D101-RESULT-V1\n".toByteArray(),
        )
        domains.forEach { domain ->
            assertThrows(D101PurposeGrantInvalid::class.java) { f.verifier().verify(listOf(f.headerBytes(compact, domain)), request) }
        }
        val staleSignature = f.headerBytes(compact).substringAfter('.')
        assertThrows(D101PurposeGrantInvalid::class.java) {
            f.verifier().verify(listOf(D101Fixture.b64(compact + byteArrayOf(0x0a)) + "." + staleSignature), request)
        }
    }

    @Test
    fun `bare valid key and synthetically valid grant cannot replace unavailable authority`() {
        val request = f.request()
        assertThrows(D101PurposeAuthorityUnavailable::class.java) {
            D101PurposeGrantVerifier(f.json, clock = f.clock).verify(listOf(f.header()), request)
        }
        assertThrows(D101PurposeAuthorityUnavailable::class.java) {
            f.verifier(D101PurposeAuthority { throw IOException("fixture source missing") }).verify(listOf(f.header()), request)
        }
        val source = D101PurposeAuthority {
            D101VerifiedPurposeAuthority("rfc8032-fixture", f.publicDer, D101Fixture.hash(f.publicDer),
                "", "5".repeat(64), "6".repeat(64), f.intent())
        }
        assertThrows(D101PurposeAuthorityUnavailable::class.java) { f.verifier(source).verify(listOf(f.header()), request) }
    }

    @Test
    fun `correctly signed wrong claims and every missing or null claim are refused`() {
        val request = f.request()
        val changes: List<(ObjectNode) -> Unit> = listOf(
            { it.put("schemaVersion", 2) }, { it.put("schemaVersion", 1.0) },
            { it.put("keyId", "other-key") }, { it.put("issuer", "ordinary-admin") },
            { it.put("subject", "ordinary-admin") }, { it.put("audience", "other") },
            { it.put("purpose", "QUERY_ANY") }, { it.put("action", "QUERY") }, { it.put("serverId", "other") },
            { it.put("operationId", "b".repeat(32)) }, { it.put("targetFingerprint", "9".repeat(64)) },
            { it.put("approvalIntentSha256", "9".repeat(64)) }, { it.put("gatewayPayloadSha256", "9".repeat(64)) },
            { it.put("initialPublicRevision", "2") }, { it.put("method", "GET") },
            { it.put("path", request.path + "?bypass=1") }, { it.put("requestBodySha256", "9".repeat(64)) },
            { it.put("issuedAtUnix", f.now + 1) }, { it.put("expiresAtUnix", f.now) },
            { it.put("expiresAtUnix", f.now + 61) }, { it.put("nonce", "bad") },
            { it.put("approved", true) }, { it.put("SchemaVersion", 1); it.remove("schemaVersion") },
        )
        changes.forEachIndexed { index, change ->
            val claims = f.claims(request); change(claims)
            assertThrows(D101PurposeGrantInvalid::class.java, {
                f.verifier().verify(listOf(f.header(claims)), request)
            }, "signed mutation=" + index)
        }
        D101PurposeGrantVerifier.CLAIM_KEYS.forEach { key ->
            val missing = f.claims(request); missing.remove(key)
            val nullValue = f.claims(request); nullValue.putNull(key)
            listOf(missing, nullValue).forEach {
                assertThrows(D101PurposeGrantInvalid::class.java) { f.verifier().verify(listOf(f.header(it)), request) }
            }
        }
    }

    @Test
    fun `malformed duplicate header claim signature base64 and utf8 are refused`() {
        val request = f.request()
        val header = f.header()
        val raw = f.mapper.writeValueAsString(f.claims(request))
        val badHeaders = listOf(
            emptyList(), listOf(header, header), listOf("jwt.header.signature"), listOf(header + "."),
            listOf(header.substringBefore('.') + "=." + header.substringAfter('.')),
            listOf(header.substringBefore('.') + "." + D101Fixture.b64(ByteArray(63))),
            listOf(f.headerBytes(raw.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1").toByteArray())),
            listOf(f.headerBytes((raw + "{}").toByteArray())), listOf(f.headerBytes(byteArrayOf(0xc3.toByte()))),
            listOf("a".repeat(8193)), listOf(header + "한"),
        )
        badHeaders.forEach { headers ->
            assertThrows(D101PurposeGrantInvalid::class.java) { f.verifier().verify(headers, request) }
        }
        val noIngress = D101PurposeRequest(request.action, request.operationId, request.targetFingerprint,
            request.approvalIntentSha256, request.gatewayPayloadSha256, 1, request.method, request.path,
            request.body(), authorizationHeaderCount = 0)
        assertThrows(D101PurposeGrantInvalid::class.java) { f.verifier().verify(listOf(header), noIngress) }
        val changedBody = D101PurposeRequest(request.action, request.operationId, request.targetFingerprint,
            request.approvalIntentSha256, request.gatewayPayloadSha256, 1, request.method, request.path,
            request.body() + byteArrayOf(0x0a))
        assertThrows(D101PurposeGrantInvalid::class.java) { f.verifier().verify(listOf(header), changedBody) }
    }

    @Test
    fun `original der hash wrong oid parameters and wrong key are refused`() {
        val publicHex = java.util.HexFormat.of().formatHex(f.publicDer.copyOfRange(12, f.publicDer.size))
        val invalidDer = listOf(
            f.publicDer + byteArrayOf(0x0a),
            D101Fixture.hex("302c300706032b65700500032100" + publicHex), // Forbidden NULL parameters.
            D101Fixture.hex("302a300506032b656e032100" + publicHex), // X25519 OID.
            f.publicDer.copyOf(f.publicDer.size - 1),
        )
        invalidDer.forEach {
            assertThrows(D101PurposeAuthorityUnavailable::class.java) {
                D101Ed25519.decodePublicKey(it, D101Fixture.hash(it))
            }
        }
        assertThrows(D101PurposeAuthorityUnavailable::class.java) { D101Ed25519.decodePublicKey(f.publicDer, "9".repeat(64)) }
        val otherKey = f.publicDer.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(D101PurposeGrantInvalid::class.java) {
            f.verifier(f.authority(der = otherKey)).verify(listOf(f.header()), f.request())
        }
    }

    @Test
    fun `fresh query can read history outside window but every write remains closed`() {
        listOf(f.now - 2, f.now + 8000).forEach { outsideWindow ->
            f.clock.epoch = outsideWindow
            val query = f.request(D101PurposeAction.QUERY)
            val queryClaims = f.claims(query).put("issuedAtUnix", f.clock.epoch).put("expiresAtUnix", f.clock.epoch + 60)
            assertEquals(D101PurposeAction.QUERY, f.verifier().verify(listOf(f.header(queryClaims)), query).action)
            listOf(D101PurposeAction.PREPARE, D101PurposeAction.DISPATCH_INTENT, D101PurposeAction.SETTLE_REGISTRY).forEach { action ->
                val request = f.request(action)
                val claims = f.claims(request).put("issuedAtUnix", f.clock.epoch).put("expiresAtUnix", f.clock.epoch + 60)
                assertThrows(D101PurposeGrantInvalid::class.java) { f.verifier().verify(listOf(f.header(claims)), request) }
            }
            queryClaims.put("expiresAtUnix", f.clock.epoch)
            assertThrows(D101PurposeGrantInvalid::class.java) { f.verifier().verify(listOf(f.header(queryClaims)), query) }
        }
    }

    @Test
    fun `authority fetch is never cached and cannot outlive grant`() {
        val calls = AtomicInteger()
        val source = D101PurposeAuthority { intentSha ->
            calls.incrementAndGet()
            f.authority().readVerified(intentSha)
        }
        repeat(2) { f.verifier(source).verify(listOf(f.header()), f.request()) }
        assertEquals(2, calls.get())
        val slow = D101PurposeAuthority { intentSha ->
            f.clock.epoch += 61
            f.authority().readVerified(intentSha)
        }
        assertThrows(D101PurposeGrantInvalid::class.java) { f.verifier(slow).verify(listOf(f.header()), f.request()) }
    }

    @Test
    fun `window guard is rechecked before mutation and lifetime does not renew cutoff`() {
        f.clock.epoch = f.now + 3599
        val request = f.request()
        val claims = f.claims(request).put("issuedAtUnix", f.clock.epoch).put("expiresAtUnix", f.clock.epoch + 60)
        val verified = f.verifier().verify(listOf(f.header(claims)), request)
        f.clock.epoch++
        assertThrows(D101PurposeGrantInvalid::class.java) { verified.requireNewExecutionWindow() }
    }
}

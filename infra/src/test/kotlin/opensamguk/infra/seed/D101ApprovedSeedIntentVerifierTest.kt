package opensamguk.infra.seed

import org.junit.jupiter.api.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import kotlin.test.*

class D101ApprovedSeedIntentVerifierTest {
    private class Input {
        val f=D101OptionFactsFixture();val now=Instant.parse("2026-10-06T09:00:00Z")
        val publicDer=HexFormat.of().parseHex("302a300506032b6570032100d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val privateDer=HexFormat.of().parseHex("302e020100300506032b6570042204209d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        fun b64(bytes:ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val intent=f.mapper.writeValueAsBytes(linkedMapOf<String,Any>("schemaVersion" to 1,"serverId" to "pep","worldId" to 1,"operationId" to f.op,"serverName" to "빼섭","targetFingerprint" to f.target,"rootTargetBytesBase64url" to b64(f.targetOriginal),"appSourceSha" to f.app,"oldImageDigests" to f.images,"newImageDigests" to f.images,"initialPublicRevision" to "1","windowOpensAtUnix" to now.epochSecond-60,"destructiveCutoffUnix" to now.epochSecond+300,"recoveryDeadlineUnix" to now.epochSecond+3600,"approvalReceiptSha256" to "d".repeat(64),"combinedCiReceiptSha256" to "e".repeat(64),"selectedSourceReceiptSha256" to "f".repeat(64),"isolatedSeedTickReceiptSha256" to "1".repeat(64),"spaceInventoryReceiptSha256" to "2".repeat(64),"spaceBudget" to mapOf("CandidateUnpackedBytes" to 1,"BackupBytes" to 1,"RecoveryBytes" to 1,"TemporaryBytes" to 1,"NewFileCount" to 1,"InodeReserve" to 1)))
        val sha=selectedOriginalSha(intent)
        val prepare=f.mapper.writeValueAsBytes(mapOf("schemaVersion" to 1,"approvalIntentSha256" to sha,"approvalIntentBytesBase64url" to b64(intent)))
        fun material(action:String="QUERY",issued:Long=now.epochSecond,key:String="synthetic"):ByteArray {
            val claims=f.mapper.writeValueAsBytes(mapOf("schemaVersion" to 1,"keyId" to key,"issuer" to "opensamguk-root-issuer","subject" to "opensamguk-d101-root-executor","audience" to "opensamguk-gateway-d101-v1","purpose" to "D101_PEP_RESET","action" to action,"serverId" to "pep","operationId" to f.op,"targetFingerprint" to f.target,"approvalIntentSha256" to sha,"gatewayPayloadSha256" to selectedOriginalSha(prepare),"initialPublicRevision" to "1","method" to "GET","path" to "/internal/d101/servers/pep/operations/${f.op}","requestBodySha256" to selectedOriginalSha(byteArrayOf()),"issuedAtUnix" to issued,"expiresAtUnix" to issued+60,"nonce" to "3".repeat(32)))
            val signature=Signature.getInstance("Ed25519").run {initSign(KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(privateDer)));update("OPENSAMGUK-D101-GRANT-V1\n".toByteArray(Charsets.US_ASCII)+claims);sign()}
            return f.mapper.writeValueAsBytes(mapOf("schemaVersion" to 1,"approvalIntentOriginalBase64url" to b64(intent),"preparePayloadOriginalBase64url" to b64(prepare),"purposeGrant" to b64(claims)+"."+b64(signature)))
        }
        fun verifier(wire:ByteArray=material(),present:Boolean=true)=D101ApprovedSeedIntentVerifier(if(present) D101SeedAuthorityReader {wire} else null,publicDer,selectedOriginalSha(publicDer),"synthetic",f.op,sha,Clock.fixed(now,ZoneOffset.UTC))
    }
    @Test fun `same approved original is released only after pinned independent Root signature`() {
        val i=Input();val verified=i.verifier().readVerified()
        assertEquals(i.sha,verified.sha256);assertContentEquals(i.intent,verified.originalBytes())
        verified.originalBytes().fill(0);assertContentEquals(i.intent,verified.originalBytes())
    }
    @Test fun `missing source wrong purpose key stale or changed original remains unavailable`() {
        val i=Input()
        assertFailsWith<SelectedSourceUnavailable>{i.verifier(present=false).readVerified()}
        for(wire in listOf(i.material(action="DISPATCH_INTENT"),i.material(key="other"),i.material(issued=i.now.epochSecond-60),i.material(issued=i.now.epochSecond+1))) assertFailsWith<SelectedSourceUnavailable>{i.verifier(wire).readVerified()}
        val tree=i.f.mapper.readTree(i.material()) as com.fasterxml.jackson.databind.node.ObjectNode
        tree.put("approvalIntentOriginalBase64url",i.b64(i.intent+byteArrayOf(32)))
        assertFailsWith<SelectedSourceUnavailable>{i.verifier(i.f.mapper.writeValueAsBytes(tree)).readVerified()}
    }
}

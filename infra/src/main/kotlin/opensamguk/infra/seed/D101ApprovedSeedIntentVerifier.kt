package opensamguk.infra.seed

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.util.Base64

/** Fixed Root source transport, installed separately from JDBC and never selected
 * by an HTTP request, importer option, or an approval Boolean. */
fun interface D101SeedAuthorityReader {fun readOriginalMaterial():ByteArray}

/** Verifies the existing independent Root authority's signed QUERY facts. Root
 * checks its current physical lease/dispatch/original cutoff before producing
 * the material. QUERY facts are not authority to perform a seed write. */
class D101ApprovedSeedIntentVerifier(
    private val fixedSource:D101SeedAuthorityReader?,
    rootProducerSpki:ByteArray,
    private val rootProducerSpkiSha256:String,
    private val fixedRootKeyId:String,
    private val originalOp:String,
    private val expectedIntentSha256:String,
    private val clock:Clock=Clock.systemUTC(),
) {
    private val spki=rootProducerSpki.copyOf()
    private val mapper=ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    class D101VerifiedSeedApproval private constructor(private val original:ByteArray,val sha256:String) {
        fun originalBytes()=original.copyOf()
        companion object {internal fun fromVerified(original:ByteArray,sha:String)=D101VerifiedSeedApproval(original.copyOf(),sha)}
    }
    fun readVerified():D101VerifiedSeedApproval = try {
        val reader=fixedSource ?: unavailable()
        if (!Regex("[a-f0-9]{32}").matches(originalOp) || !SHA.matches(expectedIntentSha256) || !Regex("[a-z0-9._-]{1,64}").matches(fixedRootKeyId) ||
            spki.size!=44 || selectedOriginalSha(spki)!=rootProducerSpkiSha256 || !spki.copyOfRange(0,12).contentEquals(java.util.HexFormat.of().parseHex("302a300506032b6570032100"))) unavailable()
        val started=System.nanoTime()
        val material=objectBytes(reader.readOriginalMaterial(),setOf("schemaVersion","approvalIntentOriginalBase64url","preparePayloadOriginalBase64url","purposeGrant"),128*1024)
        if (number(material["schemaVersion"])!=1L) unavailable()
        val original=base64(text(material["approvalIntentOriginalBase64url"]),32*1024)
        if (selectedOriginalSha(original)!=expectedIntentSha256) unavailable()
        val intent=objectBytes(original,INTENT_KEYS,32*1024)
        if (number(intent["schemaVersion"])!=1L || number(intent["worldId"])!=1L || text(intent["serverId"])!="pep" || text(intent["serverName"])!="빼섭" || text(intent["operationId"])!=originalOp || !Regex("[a-f0-9]{40}").matches(text(intent["appSourceSha"]))) unavailable()
        val target=base64(text(intent["rootTargetBytesBase64url"]),16*1024)
        val fingerprint=text(intent["targetFingerprint"])
        if (!SHA.matches(fingerprint) || selectedOriginalSha(target)!=fingerprint) unavailable()
        val targetObject=objectBytes(target,setOf("id","target"),16*1024)
        if (text(targetObject["id"])!="pep") unavailable()
        val options=targetObject["target"]
        keys(options,setOf("storageImageDigests","scenarioCode","generation","scenarioSeedEnabled","updates","imageDigests"))
        if (text(options["scenarioCode"])!="scenario_3190" || number(options["generation"])!=0L || !options["scenarioSeedEnabled"].isBoolean || !options["scenarioSeedEnabled"].booleanValue()) unavailable()
        val images=strings(intent["newImageDigests"],FIVE_IMAGES)
        strings(intent["oldImageDigests"],FIVE_IMAGES)
        if (strings(options["imageDigests"],setOf("game-api","game-engine","web-game"))+strings(options["storageImageDigests"],setOf("game-postgres","game-redis"))!=images) unavailable()
        for (key in setOf("approvalReceiptSha256","combinedCiReceiptSha256","selectedSourceReceiptSha256","isolatedSeedTickReceiptSha256","spaceInventoryReceiptSha256")) if (!SHA.matches(text(intent[key]))) unavailable()
        val opens=number(intent["windowOpensAtUnix"]);val cutoff=number(intent["destructiveCutoffUnix"]);val deadline=number(intent["recoveryDeadlineUnix"])
        val now=clock.instant().epochSecond
        if (opens<=0 || opens>=cutoff || cutoff>=deadline || now<opens || now>=cutoff) unavailable()
        val prepare=base64(text(material["preparePayloadOriginalBase64url"]),64*1024)
        val prepareObject=objectBytes(prepare,setOf("schemaVersion","approvalIntentSha256","approvalIntentBytesBase64url"),64*1024)
        if (number(prepareObject["schemaVersion"])!=1L || text(prepareObject["approvalIntentSha256"])!=expectedIntentSha256 || !base64(text(prepareObject["approvalIntentBytesBase64url"]),32*1024).contentEquals(original)) unavailable()
        val token=text(material["purposeGrant"]);if (token.length>8192) unavailable()
        val parts=token.split('.');if (parts.size!=2) unavailable()
        val claimsOriginal=base64(parts[0],4096);val signature=base64(parts[1],64)
        val claims=objectBytes(claimsOriginal,CLAIM_KEYS,4096)
        if (signature.size!=64 || !Signature.getInstance("Ed25519").run {initVerify(KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki)));update("OPENSAMGUK-D101-GRANT-V1\n".toByteArray(Charsets.US_ASCII)+claimsOriginal);verify(signature)}) unavailable()
        val fixed=mapOf("keyId" to fixedRootKeyId,"issuer" to "opensamguk-root-issuer","subject" to "opensamguk-d101-root-executor","audience" to "opensamguk-gateway-d101-v1","purpose" to "D101_PEP_RESET","action" to "QUERY","serverId" to "pep","operationId" to originalOp,
            "targetFingerprint" to fingerprint,"approvalIntentSha256" to expectedIntentSha256,"gatewayPayloadSha256" to selectedOriginalSha(prepare),"initialPublicRevision" to text(intent["initialPublicRevision"]),"method" to "GET","path" to "/internal/d101/servers/pep/operations/$originalOp","requestBodySha256" to selectedOriginalSha(byteArrayOf()))
        if (number(claims["schemaVersion"])!=1L || fixed.any {(key,value)->text(claims[key])!=value} || !Regex("[a-f0-9]{32}").matches(text(claims["nonce"]))) unavailable()
        val issued=number(claims["issuedAtUnix"]);val expires=number(claims["expiresAtUnix"])
        val completed=clock.instant().epochSecond
        if (issued<=0 || issued>now || expires<=issued || expires-issued>60 || completed>=expires || completed>=cutoff || completed<now || System.nanoTime()-started>=java.util.concurrent.TimeUnit.SECONDS.toNanos(2)) unavailable()
        D101VerifiedSeedApproval.fromVerified(original,expectedIntentSha256)
    } catch (_:Exception) {unavailable()}
    private fun objectBytes(wire:ByteArray,expected:Set<String>,limit:Int):JsonNode {
        if (wire.isEmpty() || wire.size>limit) unavailable()
        Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(wire))
        return mapper.readTree(wire).also {keys(it,expected)}
    }
    private fun keys(node:JsonNode,expected:Set<String>){if (!node.isObject || node.fieldNames().asSequence().toSet()!=expected || expected.any {node[it].isNull}) unavailable()}
    private fun text(node:JsonNode):String {if (!node.isTextual) unavailable();return node.textValue()}
    private fun number(node:JsonNode):Long {if (!node.isIntegralNumber || !node.canConvertToLong()) unavailable();return node.longValue()}
    private fun strings(node:JsonNode,expected:Set<String>):Map<String,String>{keys(node,expected);return expected.associateWith {text(node[it]).also {value->if (!Regex("sha256:[a-f0-9]{64}").matches(value)) unavailable()}}}
    private fun base64(value:String,limit:Int):ByteArray {if (!Regex("[A-Za-z0-9_-]+").matches(value) || value.length>(limit*4+2)/3) unavailable();return Base64.getUrlDecoder().decode(value).also {if (it.size>limit || Base64.getUrlEncoder().withoutPadding().encodeToString(it)!=value) unavailable()}}
    private fun unavailable():Nothing=throw SelectedSourceUnavailable()
    companion object {
        private val SHA=Regex("[a-f0-9]{64}")
        private val FIVE_IMAGES=setOf("game-api","game-engine","web-game","game-postgres","game-redis")
        private val INTENT_KEYS=setOf("schemaVersion","serverId","worldId","operationId","serverName","targetFingerprint","rootTargetBytesBase64url","appSourceSha","oldImageDigests","newImageDigests","initialPublicRevision","windowOpensAtUnix","destructiveCutoffUnix","recoveryDeadlineUnix","approvalReceiptSha256","combinedCiReceiptSha256","selectedSourceReceiptSha256","isolatedSeedTickReceiptSha256","spaceInventoryReceiptSha256","spaceBudget")
        private val CLAIM_KEYS=setOf("schemaVersion","keyId","issuer","subject","audience","purpose","action","serverId","operationId","targetFingerprint","approvalIntentSha256","gatewayPayloadSha256","initialPublicRevision","method","path","requestBodySha256","issuedAtUnix","expiresAtUnix","nonce")
    }
}

package opensamguk.infra.seed

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.util.Base64

/** Fixed host source, supplied by installation, not a request or resolver path. */
fun interface D101SignedSelectedReceiptReader { fun readOriginalEnvelope():ByteArray }

/** Independently verifies the producer signature and actual same-capture bytes,
 * selected world and observed importer options. Missing source never succeeds. */
class D101SelectedSourceReceiptSource(
    private val fixedSource:D101SignedSelectedReceiptReader?,
    producerSpki:ByteArray,
    private val producerSpkiSha256:String,
    private val producerIdentity:String,
    private val selectedWorld:D101WorldArtifactCapture,
    private val observedOptions:D101EffectiveSeedOptionsProvenance,
    private val topologyAlgorithmBytecode:ByteArray,
    typedRootTargetOriginal:ByteArray,
    resolverDecisionOriginal:ByteArray,
    private val clock:Clock=Clock.systemUTC(),
):SelectedSourceCustodySource {
    private val spki=producerSpki.copyOf()
    private val algorithm=topologyAlgorithmBytecode.copyOf()
    private val targetOriginal=typedRootTargetOriginal.copyOf()
    private val decisionOriginal=resolverDecisionOriginal.copyOf()
    private val mapper=ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    override fun readVerified(originalOp:String,typedTargetFingerprint:String,appSourceSha:String,
        imagePins:Map<String,String>,originals:SelectedCapturedOriginals):SelectedBundleBinding = try {
        val reader=fixedSource ?: unavailable()
        if (originalOp!=observedOptions.originalOp || typedTargetFingerprint!=observedOptions.targetFingerprint ||
            appSourceSha!=observedOptions.appSourceSha || imagePins!=observedOptions.imagePins() ||
            !producerIdentity.matches(Regex("[a-z0-9._-]{1,64}")) || algorithm.isEmpty() || algorithm.size>2*1024*1024 ||
            spki.size!=44 || selectedOriginalSha(spki)!=producerSpkiSha256 ||
            !spki.copyOfRange(0,12).contentEquals(java.util.HexFormat.of().parseHex("302a300506032b6570032100"))) unavailable()
        fun objectBytes(wire:ByteArray,keys:Set<String>,limit:Int):com.fasterxml.jackson.databind.JsonNode {
            if (wire.isEmpty() || wire.size>limit) unavailable()
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(wire))
            return mapper.readTree(wire).also { node->
                if (!node.isObject || node.fieldNames().asSequence().toSet()!=keys || keys.any { node[it].isNull }) unavailable()
            }
        }
        fun text(node:com.fasterxml.jackson.databind.JsonNode):String { if (!node.isTextual) unavailable();return node.textValue() }
        fun base64(text:String,limit:Int):ByteArray {
            if (!text.matches(Regex("[A-Za-z0-9_-]+")) || text.length>(limit*4+2)/3) unavailable()
            val value=Base64.getUrlDecoder().decode(text)
            if (value.size>limit || Base64.getUrlEncoder().withoutPadding().encodeToString(value)!=text) unavailable()
            return value
        }
        if (selectedOriginalSha(targetOriginal)!=typedTargetFingerprint) unavailable()
        val targetWrapper=objectBytes(targetOriginal,setOf("id","target"),16*1024)
        if (text(targetWrapper["id"])!="pep") unavailable()
        val target=targetWrapper["target"]
        if (!target.isObject || target.fieldNames().asSequence().toSet()!=setOf("storageImageDigests","scenarioCode","generation",
                "scenarioSeedEnabled","updates","imageDigests") || text(target["scenarioCode"])!="scenario_3190" ||
            !target["generation"].isIntegralNumber || target["generation"].bigIntegerValue()!=java.math.BigInteger.ZERO ||
            !target["scenarioSeedEnabled"].isBoolean || !target["scenarioSeedEnabled"].booleanValue()) unavailable()
        val updates=target["updates"]
        if (!updates.isObject || observedOptions.effectiveOptions().any { (name,value)-> updates[name]==null || text(updates[name])!=value }) unavailable()
        val decision=objectBytes(decisionOriginal,setOf("schemaVersion","kind","originalOp","typedTargetFingerprint","appSourceSha",
            "imagePins","selectedOrigin","selectedLogicalId","classpathLogicalId","originalPins"),64*1024)
        if (!decision["schemaVersion"].isIntegralNumber || decision["schemaVersion"].bigIntegerValue()!=java.math.BigInteger.ONE ||
            text(decision["kind"])!="D101_RESOLVER_DECISION_V1" || text(decision["originalOp"])!=originalOp ||
            text(decision["typedTargetFingerprint"])!=typedTargetFingerprint || text(decision["appSourceSha"])!=appSourceSha ||
            text(decision["selectedOrigin"])!=originals.selectedOrigin.name || text(decision["selectedLogicalId"])!=originals.selectedLogicalId ||
            text(decision["classpathLogicalId"])!=originals.classpathLogicalId) unavailable()
        val envelope=objectBytes(reader.readOriginalEnvelope(),setOf("schemaVersion","originalBytesBase64url","signatureBase64url"),96*1024)
        if (!envelope["schemaVersion"].isIntegralNumber || envelope["schemaVersion"].bigIntegerValue()!=java.math.BigInteger.ONE) unavailable()
        val receipt=base64(text(envelope["originalBytesBase64url"]),64*1024)
        val signature=base64(text(envelope["signatureBase64url"]),64)
        val key=KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
        if (signature.size!=64 || !Signature.getInstance("Ed25519").run {
                initVerify(key);update(DOMAIN.toByteArray(Charsets.US_ASCII)+receipt);verify(signature)
            }) unavailable()
        val root=objectBytes(receipt,KEYS,64*1024)
        if (!root["schemaVersion"].isIntegralNumber || root["schemaVersion"].bigIntegerValue()!=java.math.BigInteger.ONE ||
            text(root["kind"])!="D101_FINAL_SELECTED_SOURCE_V1" || text(root["selectionStatus"])!="FINAL_SELECTED" ||
            text(root["originalOp"])!=originalOp || text(root["typedTargetFingerprint"])!=typedTargetFingerprint ||
            text(root["appSourceSha"])!=appSourceSha || text(root["trustedProducerIdentity"])!=producerIdentity ||
            text(root["scenarioOrigin"])!=originals.selectedOrigin.name || text(root["scenarioLogicalId"])!=originals.selectedLogicalId ||
            text(root["classpathLogicalId"])!=originals.classpathLogicalId ||
            text(root["artifactSetId"])!=selectedWorld.artifactSetId || text(root["variant"])!=selectedWorld.variant ||
            text(root["topologyRevision"])!=selectedWorld.topologyRevision ||
            text(root["topologyContentHash"])!=selectedWorld.topologyContentHash ||
            text(root["configurationSha256"])!=observedOptions.configurationSha256 ||
            text(root["parserBytecodeSha256"])!=observedOptions.parserBytecodeSha256) unavailable()
        fun stringMap(node:com.fasterxml.jackson.databind.JsonNode):Map<String,String> {
            if (!node.isObject) unavailable()
            return node.fieldNames().asSequence().associateWith { text(node[it]) }
        }
        if (target["imageDigests"].fieldNames().asSequence().toSet()!=setOf("game-api","game-engine","web-game") ||
            target["storageImageDigests"].fieldNames().asSequence().toSet()!=setOf("game-postgres","game-redis")) unavailable()
        if (stringMap(root["imagePins"])!=imagePins || stringMap(decision["imagePins"])!=imagePins ||
            (stringMap(target["imageDigests"])+stringMap(target["storageImageDigests"]))!=imagePins ||
            stringMap(root["effectiveOptions"])!=observedOptions.effectiveOptions() ||
            stringMap(root["optionProvenance"])!=observedOptions.optionProvenance()) unavailable()
        val pinNode=root["originalPins"]
        val actual=originals.pins()
        if (decision["originalPins"]!=pinNode) unavailable()
        if (!pinNode.isObject || pinNode.fieldNames().asSequence().toSet()!=actual.keys) unavailable()
        for ((id,pin) in actual) {
            val node=pinNode[id]
            if (!node.isObject || node.fieldNames().asSequence().toSet()!=setOf("logicalArtifactId","rawSha256","byteLength") ||
                text(node["logicalArtifactId"])!=id || text(node["rawSha256"])!=pin.rawSha256 ||
                !node["byteLength"].isIntegralNumber || !node["byteLength"].canConvertToLong() ||
                node["byteLength"].longValue()!=pin.byteLength) unavailable()
            // Independent complete stream SHA/length, not the producer's labels.
            val wire=originals.openOriginal(id).use { it.readNBytes(64*1024*1024+1) }
            if (wire.size.toLong()!=pin.byteLength || selectedOriginalSha(wire)!=pin.rawSha256) unavailable()
        }
        for ((id,wire) in selectedWorld.mapOriginals()) {
            if (actual[id]?.rawSha256!=selectedOriginalSha(wire) || actual[id]?.byteLength!=wire.size.toLong()) unavailable()
        }
        val topologyProvenance=mapper.writeValueAsBytes(linkedMapOf(
            "artifactSetId" to selectedWorld.artifactSetId,"variant" to selectedWorld.variant,
            "topologyRevision" to selectedWorld.topologyRevision,"contentHash" to selectedWorld.topologyContentHash,
            "algorithmBytecodeSha256" to selectedOriginalSha(algorithm),
            "canonicalInputSha256" to selectedOriginalSha(selectedWorld.canonicalTopologyBytes()),
            "inputArtifacts" to selectedWorld.topologyOriginals().toSortedMap().mapValues { (id,wire)->
                linkedMapOf("logicalArtifactId" to id,"rawSha256" to selectedOriginalSha(wire),"byteLength" to wire.size.toLong()) }))
        val topologySha=selectedOriginalSha(topologyProvenance)
        if (text(root["topologyContentHashProvenanceSha256"])!=topologySha ||
            text(root["resolverDecisionReceiptSha256"])!=selectedOriginalSha(decisionOriginal)) unavailable()
        val capturedText=text(root["capturedAtUtc"])
        if (!capturedText.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z")) ||
            Instant.parse(capturedText)>clock.instant()) unavailable()
        FrozenSelectedBundleBinding(object:SelectedBundleBinding {
            override val originalOp=originalOp
            override val typedTargetFingerprint=typedTargetFingerprint
            override val appSourceSha=appSourceSha
            override val selectionStatus="FINAL_SELECTED"
            override val scenarioOrigin=originals.selectedOrigin
            override val scenarioLogicalId=originals.selectedLogicalId
            override val classpathLogicalId=originals.classpathLogicalId
            override val selectedSourceReceiptSha256=selectedOriginalSha(receipt)
            override val artifactSetId=selectedWorld.artifactSetId
            override val variant=selectedWorld.variant
            override val topologyRevision=selectedWorld.topologyRevision
            override val topologyContentHash=selectedWorld.topologyContentHash
            override val topologyContentHashProvenanceSha256=topologySha
            override fun imagePins()=imagePins.toMap()
            override fun originalReceipt()=receipt.copyOf()
            override fun originals()=actual.toMap()
            override fun effectiveOptions()=observedOptions.effectiveOptions()
            override fun optionProvenance()=observedOptions.optionProvenance()
        })
    } catch (_:Exception) { unavailable() }
    private fun unavailable():Nothing=throw SelectedSourceUnavailable()
    companion object {
        const val DOMAIN="OPENSAMGUK-D101-SELECTED-SOURCE-V1\n"
        private val KEYS=setOf("schemaVersion","kind","selectionStatus","originalOp","typedTargetFingerprint","appSourceSha",
            "imagePins","scenarioOrigin","scenarioLogicalId","classpathLogicalId","originalPins","artifactSetId","variant",
            "topologyRevision","topologyContentHash","topologyContentHashProvenanceSha256","effectiveOptions","optionProvenance",
            "configurationSha256","parserBytecodeSha256","resolverDecisionReceiptSha256","capturedAtUtc","trustedProducerIdentity")
    }
}

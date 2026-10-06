package opensamguk.infra.seed

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Clock

/** Actual unsigned capture producer for the fixed native host signer. It uses
 * the same selected immutable objects and actual parse facts as the importer.
 * These bytes alone are not FINAL_SELECTED authority or an installed signer. */
class D101SelectedSourceProducer(private val clock:Clock=Clock.systemUTC()) {
    private val mapper=ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    /** Unsigned transport entry: no verified handle, receipt or approval is created. */
    fun produceCapturedBytes(rawOriginals:Map<String,ByteArray>, selectedScenario:CapturedScenarioOriginal,
        classpathLogicalId:String, world:D101WorldArtifactCapture, options:D101EffectiveSeedOptionsProvenance,
        algorithmBytecode:ByteArray, typedTargetOriginal:ByteArray, resolverDecisionOriginal:ByteArray,
        fixedProducerIdentity:String):ByteArray {
        val raw=rawOriginals.mapValues { it.value.copyOf() }
        if (classpathLogicalId!="scenario/scenario_3190.json" ||
            raw["selected-scenario.json"]?.contentEquals(selectedScenario.originalBytes())!=true ||
            raw["classpath-scenario.json"]?.let { it.isNotEmpty() && it.size<=16*1024*1024 }!=true ||
            world.mapOriginals().any { (id,bytes)->raw[id]?.contentEquals(bytes)!=true }) unavailable()
        return produce(SelectedCapturedOriginals(raw,selectedScenario.origin,selectedScenario.logicalId,classpathLogicalId),
            world,options,algorithmBytecode,typedTargetOriginal,resolverDecisionOriginal,fixedProducerIdentity)
    }


    fun produce(originals:SelectedCapturedOriginals,world:D101WorldArtifactCapture,
        options:D101EffectiveSeedOptionsProvenance,algorithmBytecode:ByteArray,
        typedTargetOriginal:ByteArray,resolverDecisionOriginal:ByteArray,
        fixedProducerIdentity:String):ByteArray = try {
        if (!fixedProducerIdentity.matches(Regex("[a-z0-9._-]{1,64}")) || algorithmBytecode.isEmpty() ||
            algorithmBytecode.size>2*1024*1024 || selectedOriginalSha(typedTargetOriginal)!=options.targetFingerprint) unavailable()
        fun objectBytes(wire:ByteArray,keys:Set<String>,limit:Int):com.fasterxml.jackson.databind.JsonNode {
            if (wire.isEmpty() || wire.size>limit) unavailable()
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(wire))
            return mapper.readTree(wire).also { if (!it.isObject || it.fieldNames().asSequence().toSet()!=keys || keys.any {key->it[key].isNull}) unavailable() }
        }
        fun text(node:com.fasterxml.jackson.databind.JsonNode):String {if (!node.isTextual) unavailable();return node.textValue()}
        fun strings(node:com.fasterxml.jackson.databind.JsonNode):Map<String,String>{if (!node.isObject) unavailable();return node.fieldNames().asSequence().associateWith{text(node[it])}}
        val target=objectBytes(typedTargetOriginal,setOf("id","target"),16*1024)
        if (text(target["id"])!="pep" || !target["target"].isObject || target["target"].fieldNames().asSequence().toSet()!=setOf("storageImageDigests","scenarioCode","generation","scenarioSeedEnabled","updates","imageDigests") ||
            text(target["target"]["scenarioCode"])!="scenario_3190" || !target["target"]["generation"].isIntegralNumber || target["target"]["generation"].bigIntegerValue()!=java.math.BigInteger.ZERO || !target["target"]["scenarioSeedEnabled"].isBoolean || !target["target"]["scenarioSeedEnabled"].booleanValue() ||
            options.effectiveOptions().any { (key,value)->target["target"]["updates"][key]==null || text(target["target"]["updates"][key])!=value } ||
            strings(target["target"]["imageDigests"])+strings(target["target"]["storageImageDigests"])!=options.imagePins()) unavailable()
        val pins=originals.pins()
        if (pins.keys!=setOf("tiles.json","world.json","roads.json","selected-scenario.json","classpath-scenario.json")) unavailable()
        for ((id,pin) in pins) {
            val wire=originals.openOriginal(id).use {it.readNBytes(64*1024*1024+1)}
            if (wire.size.toLong()!=pin.byteLength || selectedOriginalSha(wire)!=pin.rawSha256) unavailable()
        }
        for ((id,wire) in world.mapOriginals()) {
            if (pins[id]?.rawSha256!=selectedOriginalSha(wire) || pins[id]?.byteLength!=wire.size.toLong()) unavailable()
        }
        if (originals.selectedOrigin==SelectedScenarioOrigin.CLASSPATH &&
            (originals.selectedLogicalId!=originals.classpathLogicalId || pins.getValue("selected-scenario.json").rawSha256!=pins.getValue("classpath-scenario.json").rawSha256)) unavailable()
        val decision=objectBytes(resolverDecisionOriginal,setOf("schemaVersion","kind","originalOp","typedTargetFingerprint","appSourceSha","imagePins","selectedOrigin","selectedLogicalId","classpathLogicalId","originalPins"),64*1024)
        if (!decision["schemaVersion"].isIntegralNumber || decision["schemaVersion"].bigIntegerValue()!=java.math.BigInteger.ONE || text(decision["kind"])!="D101_RESOLVER_DECISION_V1" ||
            text(decision["originalOp"])!=options.originalOp || text(decision["typedTargetFingerprint"])!=options.targetFingerprint || text(decision["appSourceSha"])!=options.appSourceSha ||
            strings(decision["imagePins"])!=options.imagePins() || text(decision["selectedOrigin"])!=originals.selectedOrigin.name || text(decision["selectedLogicalId"])!=originals.selectedLogicalId ||
            text(decision["classpathLogicalId"])!=originals.classpathLogicalId) unavailable()
        // Parsed JSON chooses IntNode for small lengths; the measured Kotlin
        // Long serializes as LongNode in valueToTree. Compare exact integer
        // values and shape, never Jackson's different numeric node classes.
        val decisionPins=decision["originalPins"]
        if (!decisionPins.isObject || decisionPins.fieldNames().asSequence().toSet()!=pins.keys) unavailable()
        for ((id,pin) in pins) {
            val node=decisionPins[id]
            if (!node.isObject || node.fieldNames().asSequence().toSet()!=setOf("logicalArtifactId","rawSha256","byteLength") ||
                text(node["logicalArtifactId"])!=id || text(node["rawSha256"])!=pin.rawSha256 ||
                !node["byteLength"].isIntegralNumber || !node["byteLength"].canConvertToLong() ||
                node["byteLength"].longValue()!=pin.byteLength) unavailable()
        }
        val topology=mapper.writeValueAsBytes(linkedMapOf("artifactSetId" to world.artifactSetId,"variant" to world.variant,
            "topologyRevision" to world.topologyRevision,"contentHash" to world.topologyContentHash,
            "algorithmBytecodeSha256" to selectedOriginalSha(algorithmBytecode),"canonicalInputSha256" to selectedOriginalSha(world.canonicalTopologyBytes()),
            "inputArtifacts" to world.topologyOriginals().toSortedMap().mapValues {(id,wire)->linkedMapOf("logicalArtifactId" to id,"rawSha256" to selectedOriginalSha(wire),"byteLength" to wire.size.toLong())}))
        mapper.writeValueAsBytes(linkedMapOf<String,Any>("schemaVersion" to 1,"kind" to "D101_FINAL_SELECTED_SOURCE_V1","selectionStatus" to "FINAL_SELECTED",
            "originalOp" to options.originalOp,"typedTargetFingerprint" to options.targetFingerprint,"appSourceSha" to options.appSourceSha,"imagePins" to options.imagePins(),
            "scenarioOrigin" to originals.selectedOrigin.name,"scenarioLogicalId" to originals.selectedLogicalId,"classpathLogicalId" to originals.classpathLogicalId,
            "originalPins" to pins,"artifactSetId" to world.artifactSetId,"variant" to world.variant,"topologyRevision" to world.topologyRevision,"topologyContentHash" to world.topologyContentHash,
            "topologyContentHashProvenanceSha256" to selectedOriginalSha(topology),"effectiveOptions" to options.effectiveOptions(),"optionProvenance" to options.optionProvenance(),
            "configurationSha256" to options.configurationSha256,"parserBytecodeSha256" to options.parserBytecodeSha256,"resolverDecisionReceiptSha256" to selectedOriginalSha(resolverDecisionOriginal),
            "capturedAtUtc" to clock.instant().toString(),"trustedProducerIdentity" to fixedProducerIdentity))
    } catch (_:Exception) {unavailable()}
    private fun unavailable():Nothing=throw SelectedSourceUnavailable()
}

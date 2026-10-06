package opensamguk.infra.seed

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Collections

/** Facts from the actual importer parse and raw configuration original.
 * This object is not a receipt or authority and supplies no default values. */
class D101EffectiveSeedOptionsProvenance private constructor(
    val configurationSha256: String,
    val parserBytecodeSha256: String,
    val originalOp: String,
    val targetFingerprint: String,
    val appSourceSha: String,
    imagePins: Map<String,String>,
    options: Map<String,String>,
    provenance: Map<String,String>,
) {
    private val images=Collections.unmodifiableMap(imagePins.toMap())
    private val options=Collections.unmodifiableMap(options.toMap())
    private val provenance=Collections.unmodifiableMap(provenance.toMap())
    fun imagePins()=images
    fun effectiveOptions()=options
    fun optionProvenance()=provenance

    companion object {
        val REQUIRED=setOf("SERVER_NAME","SERVER_GENERATION","SCENARIO_CODE","SCENARIO_SEED_ENABLED","SCENARIO_LOOKUP_DIR",
            "RESET_MAXGENERAL","RESET_FIRST_TURN","RESET_EXTEND","RESET_TURNTERM","RESET_BLOCK_GENERAL_CREATE","RESET_NPCMODE","RESET_SHOW_IMG_LEVEL")
        val ALLOWED=REQUIRED+"RESET_FICTION"
        private val numeric=setOf("SERVER_GENERATION","RESET_MAXGENERAL","RESET_EXTEND","RESET_TURNTERM","RESET_BLOCK_GENERAL_CREATE",
            "RESET_NPCMODE","RESET_SHOW_IMG_LEVEL","RESET_FICTION")
        private val mapper=ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

        fun capture(configurationOriginal:ByteArray,expectedConfigurationSha256:String,
            actualParsedOptions:Map<String,String>,actualParserBytecode:ByteArray,
            expectedParserBytecodeSha256:String):D101EffectiveSeedOptionsProvenance = try {
            if (configurationOriginal.isEmpty() || configurationOriginal.size>64*1024 ||
                selectedOriginalSha(configurationOriginal)!=expectedConfigurationSha256 ||
                actualParserBytecode.isEmpty() || actualParserBytecode.size>2*1024*1024 ||
                selectedOriginalSha(actualParserBytecode)!=expectedParserBytecodeSha256) unavailable()
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(configurationOriginal))
            val root=mapper.readTree(configurationOriginal)
            val keys=setOf("schemaVersion","kind","originalOp","typedTargetFingerprint","appSourceSha","imagePins","rawInputs")
            if (!root.isObject || root.fieldNames().asSequence().toSet()!=keys || keys.any { root[it].isNull } ||
                !root["schemaVersion"].isIntegralNumber || root["schemaVersion"].bigIntegerValue()!=java.math.BigInteger.ONE ||
                root["kind"].asText()!="D101_EFFECTIVE_SEED_INPUTS_V1") unavailable()
            fun text(node:com.fasterxml.jackson.databind.JsonNode):String {
                if (!node.isTextual) unavailable()
                return node.textValue()
            }
            val op=text(root["originalOp"]);val target=text(root["typedTargetFingerprint"]);val app=text(root["appSourceSha"])
            if (!op.matches(Regex("[a-f0-9]{32}")) || !target.matches(Regex("[a-f0-9]{64}")) || !app.matches(Regex("[a-f0-9]{40}"))) unavailable()
            val imageKeys=setOf("game-api","game-engine","web-game","game-postgres","game-redis")
            val imageNode=root["imagePins"]
            if (!imageNode.isObject || imageNode.fieldNames().asSequence().toSet()!=imageKeys) unavailable()
            val images=imageKeys.associateWith { text(imageNode[it]).also { pin->if (!pin.matches(Regex("sha256:[a-f0-9]{64}"))) unavailable() } }
            val raw=root["rawInputs"]
            if (!raw.isObject || raw.fieldNames().asSequence().toSet()!=actualParsedOptions.keys ||
                !actualParsedOptions.keys.containsAll(REQUIRED) || !ALLOWED.containsAll(actualParsedOptions.keys)) unavailable()
            val normalized=actualParsedOptions.mapValues { (key,effective)->
                val input=text(raw[key])
                val observed=if (key in numeric) {
                    val trimmed=input.trim()
                    if (!trimmed.matches(Regex("[0-9]+"))) unavailable()
                    trimmed.toIntOrNull()?.toString() ?: unavailable()
                } else input
                if (observed!=effective) unavailable()
                effective
            }
            if (normalized["SERVER_NAME"]!="빼섭" || normalized["SERVER_GENERATION"]!="0" ||
                normalized["SCENARIO_CODE"]!="scenario_3190" || normalized["SCENARIO_SEED_ENABLED"]!="true" ||
                normalized["SCENARIO_LOOKUP_DIR"]!="" || normalized["RESET_MAXGENERAL"]!="50" ||
                normalized["RESET_FIRST_TURN"]!="immediate" || normalized["RESET_TURNTERM"]!="60" ||
                normalized["RESET_BLOCK_GENERAL_CREATE"]!="1" || normalized["RESET_EXTEND"] !in setOf("0","1") ||
                (normalized.containsKey("RESET_FICTION") && normalized["RESET_FICTION"] !in setOf("0","1")) ||
                normalized["RESET_NPCMODE"] !in setOf("0","1","2") || normalized["RESET_SHOW_IMG_LEVEL"] !in setOf("0","1","2","3")) unavailable()
            // Each decision original names raw input, observed importer result and
            // exact parser bytes. Hashes are recalculated, never filled with defaults.
            val provenance=normalized.keys.associateWith { key-> selectedOriginalSha(mapper.writeValueAsBytes(linkedMapOf(
                "configurationSha256" to expectedConfigurationSha256,"parserBytecodeSha256" to expectedParserBytecodeSha256,
                "option" to key,"rawInput" to text(raw[key]),"effectiveValue" to normalized.getValue(key)))) }
            D101EffectiveSeedOptionsProvenance(expectedConfigurationSha256,expectedParserBytecodeSha256,op,target,app,images,normalized,provenance)
        } catch (_:Exception) { unavailable() }
        private fun unavailable():Nothing=throw SelectedSourceUnavailable()
    }
}

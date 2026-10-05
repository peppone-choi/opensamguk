package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.*

internal class D101OptionFactsFixture {
    val mapper=ObjectMapper()
    val op="a".repeat(32); val app="b".repeat(40)
    val images=setOf("game-api","game-engine","web-game","game-postgres","game-redis").associateWith {"sha256:"+"c".repeat(64)}
    val options=linkedMapOf("SERVER_NAME" to "빼섭","SERVER_GENERATION" to "0","SCENARIO_CODE" to "scenario_3190",
        "SCENARIO_SEED_ENABLED" to "true","SCENARIO_LOOKUP_DIR" to "","RESET_MAXGENERAL" to "50",
        "RESET_FIRST_TURN" to "immediate","RESET_EXTEND" to "1","RESET_TURNTERM" to "60",
        "RESET_BLOCK_GENERAL_CREATE" to "1","RESET_NPCMODE" to "0","RESET_SHOW_IMG_LEVEL" to "3")
    val parser="synthetic actual parser bytes".toByteArray()
    val targetOriginal=mapper.writeValueAsBytes(linkedMapOf("id" to "pep","target" to linkedMapOf(
        "storageImageDigests" to images.filterKeys {it in setOf("game-postgres","game-redis")},"scenarioCode" to "scenario_3190",
        "generation" to 0,"scenarioSeedEnabled" to true,"updates" to options,
        "imageDigests" to images.filterKeys {it !in setOf("game-postgres","game-redis")})))
    val target=selectedOriginalSha(targetOriginal)
    fun raw(inputs:Map<String,String> = options)=mapper.writeValueAsBytes(linkedMapOf("schemaVersion" to 1,
        "kind" to "D101_EFFECTIVE_SEED_INPUTS_V1","originalOp" to op,"typedTargetFingerprint" to target,
        "appSourceSha" to app,"imagePins" to images,"rawInputs" to inputs))
    fun facts(raw:ByteArray=raw(),parsed:Map<String,String> = options)=D101EffectiveSeedOptionsProvenance.capture(
        raw,selectedOriginalSha(raw),parsed,parser,selectedOriginalSha(parser))
}
class D101EffectiveSeedOptionsProvenanceTest {
    @Test fun `actual raw input and parser bytes bind every observed option with no defaults`() {
        val f=D101OptionFactsFixture(); val raw=f.raw(f.options+mapOf("RESET_MAXGENERAL" to " 050 "))
        val facts=f.facts(raw)
        assertEquals("50",facts.effectiveOptions()["RESET_MAXGENERAL"])
        assertEquals(f.options.keys,facts.optionProvenance().keys)
        val normal=f.facts()
        assertNotEquals(normal.optionProvenance()["RESET_MAXGENERAL"],facts.optionProvenance()["RESET_MAXGENERAL"])
        assertFailsWith<UnsupportedOperationException> {(facts.effectiveOptions() as MutableMap)["RESET_EXTEND"]="0"}
    }
    @Test fun `missing unknown or differing actual parser decisions stay unavailable`() {
        val f=D101OptionFactsFixture()
        for(input in listOf(f.options-"RESET_NPCMODE",f.options+mapOf("ADMIN_PASSWORD" to "forbidden"),
            f.options+mapOf("RESET_MAXGENERAL" to "500"),f.options+mapOf("RESET_NPCMODE" to "4"))) {
            assertFailsWith<SelectedSourceUnavailable> {f.facts(f.raw(input),input)}
        }
        assertFailsWith<SelectedSourceUnavailable> {f.facts(f.raw(),f.options+mapOf("RESET_EXTEND" to "0"))}
        assertFailsWith<SelectedSourceUnavailable> {D101EffectiveSeedOptionsProvenance.capture(f.raw(),"f".repeat(64),f.options,f.parser,selectedOriginalSha(f.parser))}
    }
    @Test fun `duplicate trailing and nontext actual raw values remain unavailable`() {
        val f=D101OptionFactsFixture();val raw=f.raw().toString(Charsets.UTF_8)
        for(wire in listOf(raw.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"),
            raw+"{}",raw.replace("\"RESET_MAXGENERAL\":\"50\"","\"RESET_MAXGENERAL\":50"))) {
            assertFailsWith<SelectedSourceUnavailable> {f.facts(wire.toByteArray())}
        }
    }
}

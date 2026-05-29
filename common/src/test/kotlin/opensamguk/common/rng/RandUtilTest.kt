package opensamguk.common.rng

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class RandUtilTest {
    private val ru = Json.parseToJsonElement(this::class.java.getResource("/rng/rng-fixtures.json")!!.readText())
        .jsonObject["randUtil"]!!.jsonObject
    private fun seed() = RandUtil(LiteHashDrbg("randUtilSeed"))

    @Test fun `nextInt exclusive span-1`() = assertEquals(
        ru["nextInt_5_10"]!!.jsonArray.map { it.jsonPrimitive.int }, seed().let { r -> List(8) { r.nextInt(5, 10) } })
    @Test fun `nextRangeInt inclusive`() = assertEquals(
        ru["nextRangeInt_0_9"]!!.jsonArray.map { it.jsonPrimitive.int }, seed().let { r -> List(8) { r.nextRangeInt(0, 9) } })
    @Test fun `nextBool half == nextBit`() = assertEquals(
        ru["nextBool_half"]!!.jsonArray.map { it.jsonPrimitive.boolean }, seed().let { r -> List(16) { r.nextBool(0.5) } })
    @Test fun `nextBool 0_3`() = assertEquals(
        ru["nextBool_0_3"]!!.jsonArray.map { it.jsonPrimitive.boolean }, seed().let { r -> List(16) { r.nextBool(0.3) } })
    @Test fun `nextBit`() = assertEquals(
        ru["nextBit"]!!.jsonArray.map { it.jsonPrimitive.boolean }, seed().let { r -> List(16) { r.nextBit() } })
    @Test fun `shuffle n in 0,1,2,8,10,17`() {
        val sh = ru["shuffle"]!!.jsonObject
        for (n in listOf(0, 1, 2, 8, 10, 17))
            assertEquals(sh[n.toString()]!!.jsonArray.map { it.jsonPrimitive.int }, seed().shuffle((0 until n).toList()), "n=$n")
    }
    @Test fun `choice array`() = assertEquals(
        ru["choiceArray"]!!.jsonArray.map { it.jsonPrimitive.int }, seed().let { r -> List(6) { r.choice(listOf(0,1,2,3,4,5)) } })
    @Test fun `choice set preserves order`() = assertEquals(ru["choiceSet"]!!.jsonPrimitive.int, seed().choiceSet(linkedSetOf(5,3,1,2,8,0)))
    @Test fun `choice record uses JS key order`() =
        assertEquals(ru["choiceRecord"]!!.jsonPrimitive.content,
            seed().choiceMap(linkedMapOf("c" to "c","a" to "a","b" to "b","4" to "x","2" to "t","3" to "q")))
    @Test fun `choiceUsingWeight string keys`() =
        assertEquals(ru["choiceWeight"]!!.jsonPrimitive.content,
            seed().choiceUsingWeight(linkedMapOf("a" to 0.1,"b" to 10.0,"tt" to 2.0,"x" to -1.0,"c" to 20.0,"d" to 0.0,"e" to 6.0)))
    @Test fun `choiceUsingWeight numeric keys iterate ascending (JS divergence from PHP)`() =
        assertEquals(ru["choiceWeightNumeric"]!!.jsonPrimitive.content,
            seed().choiceUsingWeight(linkedMapOf("10" to 5.0,"2" to 5.0,"1" to 5.0,"3" to 5.0)))
}

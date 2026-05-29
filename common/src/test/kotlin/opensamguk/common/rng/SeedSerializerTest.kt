package opensamguk.common.rng

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class SeedSerializerTest {
    private val fx = Json.parseToJsonElement(this::class.java.getResource("/rng/rng-fixtures.json")!!.readText()).jsonObject
    private val seeds = fx["seeds"]!!.jsonObject
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun `mixed seed str+int with hangul length`() =
        assertEquals(seeds["mixed"]!!.jsonPrimitive.content, serializeSeed("ConquerCity", 190, 3, "attacker가나", 42))
    @Test fun `floor of doubles`() = assertEquals(seeds["floored"]!!.jsonPrimitive.content, serializeSeed(3.9, -2.1))
    @Test fun `tournament key no game`() =
        assertEquals(seeds["tournamentNoGame"]!!.jsonPrimitive.content, buildTournamentSeedKey("base", TournamentRngContext(200,1,0,0,0,0)))
    @Test fun `tournament key double-pipe quirk`() {
        val actual = buildTournamentSeedKey("base", TournamentRngContext(200,1,0,0,0,0, gameIndex = 7, extraSeed = "xs"))
        assertEquals(seeds["tournamentWithGame"]!!.jsonPrimitive.content, actual)
        assert(actual.contains("participant:0||game:7||extra:xs")) { "double-pipe quirk lost: $actual" }
    }
    @Test fun `serializeSeed feeds DRBG end-to-end`() =
        assertEquals(fx["seedDrawMixed"]!!.jsonPrimitive.content,
            hex(LiteHashDrbg(serializeSeed("ConquerCity", 190, 3, "attacker가나", 42)).nextBytes(16)))
}

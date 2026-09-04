package opensamguk.infra.persistence

import kotlin.test.*

class WaterControlRowCodecTest {
    @Test fun `contesting IDs roundtrip exactly including values larger than int`() {
        assertEquals(listOf(2L, 5000000000L), WaterControlRowCodec.decodeContestingNationIds("[2,5000000000]"))
        assertEquals(emptyList(), WaterControlRowCodec.decodeContestingNationIds("[]"))
    }

    @Test fun `corrupt JSON fractional string duplicate unsorted or nonpositive IDs fail closed`() {
        for (value in listOf("null", "{}", "[1.0]", "[1e0]", "[\"1\"]", "[2,1]", "[1,1]", "[0]", "[-1]", "[null]", "[true]", "[9223372036854775808]", "[1]x")) {
            assertFailsWith<IllegalArgumentException>(value) { WaterControlRowCodec.decodeContestingNationIds(value) }
        }
    }
}

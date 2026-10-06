package opensamguk.logic.creation

import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CreationPlayerCapTest {
    @Test fun exactPositiveIntegerIsTheOnlyUsableWorldCap() {
        assertEquals(50, CreationPlayerCap.maxGeneral(mapOf("maxgeneral" to 50)))
        assertEquals(Int.MAX_VALUE, CreationPlayerCap.maxGeneral(mapOf("maxgeneral" to Int.MAX_VALUE.toLong())))
        assertEquals(50, CreationPlayerCap.maxGeneral(mapOf("maxgeneral" to BigInteger.valueOf(50))))
        assertEquals(50, CreationPlayerCap.maxGeneral(mapOf("maxgeneral" to BigDecimal("50"))))

        val invalid = listOf<Any?>(null, "50", false, 0, -1, 50.0, 50.5,
            BigDecimal("50.0"), Double.NaN, Int.MAX_VALUE.toLong() + 1, 4_294_967_346L)
        for (value in invalid) {
            assertNull(CreationPlayerCap.maxGeneral(mapOf("maxgeneral" to value)), "maxgeneral=$value")
        }
        assertNull(CreationPlayerCap.maxGeneral(emptyMap()))
    }
}

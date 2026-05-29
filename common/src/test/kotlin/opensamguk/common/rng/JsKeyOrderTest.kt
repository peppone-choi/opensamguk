package opensamguk.common.rng
import kotlin.test.Test; import kotlin.test.assertEquals
class JsKeyOrderTest {
    @Test fun `numeric keys ascending then string keys insertion order`() =
        assertEquals(listOf("2","3","4","c","a","b"), jsKeyOrder(listOf("c","a","b","4","2","3")))
    @Test fun `leading zero is a string key`() = assertEquals(listOf("1","01"), jsKeyOrder(listOf("01","1")))
}

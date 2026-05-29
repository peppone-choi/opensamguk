package opensamguk.common.rng
import kotlin.test.Test; import kotlin.test.assertEquals
class RngKernelParityGateTest {
    @Test fun `same seed yields identical draw streams`() {
        fun draw(): List<Any> {
            val ru = RandUtil(LiteHashDrbg(serializeSeed("ConquerCity", 190, 3, 42L)))
            return listOf(ru.nextInt(0, 100), ru.nextRangeInt(1, 6), ru.nextBool(0.5), ru.shuffle((0 until 12).toList()),
                "%016x".format(java.lang.Double.doubleToRawLongBits(ru.nextFloat1())),
                ru.choiceUsingWeight(linkedMapOf("a" to 1.0, "b" to 2.0, "c" to 3.0)))
        }
        assertEquals(draw(), draw())
    }
}

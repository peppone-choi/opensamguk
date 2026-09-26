package opensamguk.logic.world

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostUpdateMonthlyTailTest {
    private fun rng() = RandUtil(LiteHashDrbg("monthly|200|1"))

    @Test
    fun `wander callback runs only after opening period`() {
        var wanderRan = false
        val consumeWander: RngConsumer = { it.nextRange(0.0, 1.0); wanderRan = true }

        val skipped = postUpdateMonthlyTail(
            year = 185, startYear = 184, rng = rng(),
            checkWander = consumeWander,
            setNationFront = { emptyList() },
        )
        assertFalse(wanderRan)
        assertFalse(skipped.checkWanderRan)

        val active = postUpdateMonthlyTail(
            year = 186, startYear = 184, rng = rng(),
            checkWander = consumeWander,
            setNationFront = { emptyList() },
        )
        assertTrue(wanderRan)
        assertTrue(active.checkWanderRan)
    }

    @Test
    fun `active monthly callbacks share one RNG and preserve order`() {
        val reference = rng()
        val expectedWanderDraw = reference.nextRange(0.0, 1.0)
        val expectedNextDraw = reference.nextRange(0.0, 1.0)
        val live = rng()
        val captured = mutableListOf<Double>()
        val order = mutableListOf<String>()
        val result = postUpdateMonthlyTail(
            year = 200, startYear = 184, rng = live,
            checkWander = { captured += it.nextRange(0.0, 1.0); order += "wander" },
            updateGeneralNumber = { order += "generals" },
            setNationFront = { order += "front"; listOf(PostFrontResult(nationId = 1)) },
        )
        assertEquals(listOf(expectedWanderDraw), captured)
        // Q16(중립 경매) 은퇴 뒤 꼬리는 Q11 말고 월 RNG 를 한 번도 소비하지 않는다.
        assertEquals(expectedNextDraw, live.nextRange(0.0, 1.0))
        assertEquals(listOf("wander", "generals", "front"), order)
        assertEquals(listOf("Q11"), result.rngDrawOrder)
        assertEquals(listOf(PostFrontResult(nationId = 1)), result.frontResults)
    }

    @Test
    fun `opening period skips wander but retains settlement callbacks`() {
        val order = mutableListOf<String>()
        val result = postUpdateMonthlyTail(
            year = 185, startYear = 184, rng = rng(),
            checkWander = { order += "wander" },
            updateGeneralNumber = { order += "generals" },
            setNationFront = { order += "front"; emptyList() },
        )
        assertEquals(listOf("generals", "front"), order)
        assertEquals(emptyList<String>(), result.rngDrawOrder)
    }
}

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
            checkWander = consumeWander, registerAuction = {},
            setNationFront = { emptyList() },
        )
        assertFalse(wanderRan)
        assertFalse(skipped.checkWanderRan)

        val active = postUpdateMonthlyTail(
            year = 186, startYear = 184, rng = rng(),
            checkWander = consumeWander, registerAuction = {},
            setNationFront = { emptyList() },
        )
        assertTrue(wanderRan)
        assertTrue(active.checkWanderRan)
    }

    @Test
    fun `active monthly callbacks share one RNG and preserve order`() {
        val reference = rng()
        val expected = listOf(
            reference.nextRange(0.0, 1.0),
            reference.nextRange(0.0, 1.0),
            reference.nextRange(0.0, 1.0),
        )
        val captured = mutableListOf<Double>()
        val order = mutableListOf<String>()
        val result = postUpdateMonthlyTail(
            year = 200, startYear = 184, rng = rng(),
            checkWander = { captured += it.nextRange(0.0, 1.0); order += "wander" },
            updateGeneralNumber = { order += "generals" },
            checkEmperior = { order += "unification" },
            registerAuction = {
                captured += it.nextRange(0.0, 1.0)
                captured += it.nextRange(0.0, 1.0)
                order += "auction"
            },
            setNationFront = { order += "front"; listOf(PostFrontResult(nationId = 1)) },
        )
        assertEquals(expected, captured)
        assertEquals(listOf("wander", "generals", "unification", "auction", "front"), order)
        assertEquals(listOf("Q11", "Q16"), result.rngDrawOrder)
        assertEquals(listOf(PostFrontResult(nationId = 1)), result.frontResults)
    }

    @Test
    fun `opening period skips wander but retains settlement callbacks`() {
        val order = mutableListOf<String>()
        val result = postUpdateMonthlyTail(
            year = 185, startYear = 184, rng = rng(),
            checkWander = { order += "wander" },
            checkEmperior = { order += "unification" },
            registerAuction = { order += "auction" },
            setNationFront = { order += "front"; emptyList() },
        )
        assertEquals(listOf("unification", "auction", "front"), order)
        assertEquals(listOf("Q16"), result.rngDrawOrder)
    }
}

package opensamguk.logic.tick

import opensamguk.logic.event.EventTarget
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * FT2 — `MonthlyPipeline` skeleton: the 6-step SEQUENTIAL interleave.
 *
 * Port target: PHP `TurnExecutionHelper.php:461-481` (consolidated OQ #1 RESOLVED):
 *   L4 build monthlyRng (pre-advance) → L5 PreMonth events (OLD date) → L6 preUpdateMonthly →
 *   L7 turnDate (advance year/month) → L8 if month==1 checkStatistic → L9 Month events (NEW date) →
 *   L10 postUpdateMonthly(monthlyRng).
 *
 * The recording stubs assert the EXACT call order, that PreMonth sees the OLD `(year,month)` while
 * Month sees the NEW one, that `monthlyRng` is built ONCE and reaches ONLY postUpdateMonthly, that
 * `checkStatistic` fires only in month 1, and that preUpdateMonthly returning false ABORTS the tick.
 */
class MonthlyPipelineOrderTest {

    private class Marker(val seq: Int)

    /** A recording dispatcher: notes each (target, year, month) call in invocation order. */
    private class RecordingDispatcher(val log: MutableList<String>) : EventDispatcher {
        override fun run(target: EventTarget, env: MonthlyEnv) {
            log.add("dispatch:$target:${env.year}-${env.month}")
        }
    }

    private fun pipeline(
        log: MutableList<String>,
        preResult: Boolean = true,
        rngHolder: Array<Any?> = arrayOfNulls(1),
        rngSeenByPost: Array<Any?> = arrayOfNulls(1),
        rngBuildCount: IntArray = IntArray(1),
        // turnDate advances OLD (180,12) -> NEW (181,1)
        oldYear: Int = 180, oldMonth: Int = 12,
        newYear: Int = 181, newMonth: Int = 1,
    ): MonthlyPipeline<Any?> {
        val marker = Marker(1)
        return MonthlyPipeline(
            monthlyRngFactory = { _, _ -> rngBuildCount[0]++; marker.also { rngHolder[0] = it } },
            clock = { _, _ -> newYear to newMonth },
            preUpdateMonthly = { log.add("preUpdateMonthly"); preResult },
            checkStatistic = { log.add("checkStatistic") },
            postUpdateMonthly = { rng -> rngSeenByPost[0] = rng; log.add("postUpdateMonthly") },
        )
    }

    @Test
    fun `interleave is PreMonth-old then preUpd then turnDate then Month-new then postUpd`() {
        val log = mutableListOf<String>()
        val rngHolder = arrayOfNulls<Any?>(1)
        val seenByPost = arrayOfNulls<Any?>(1)
        val p = pipeline(log, rngHolder = rngHolder, rngSeenByPost = seenByPost)
        val dispatcher = RecordingDispatcher(log)

        p.runMonth(nextTurn = java.time.Instant.EPOCH, startYear = 0,
            startTime = java.time.Instant.EPOCH, turnTerm = 120,
            oldYear = 180, oldMonth = 12, dispatcher = dispatcher)

        assertEquals(
            listOf(
                "dispatch:PRE_MONTH:180-12",  // PreMonth sees the OLD date
                "preUpdateMonthly",
                "checkStatistic",             // new month is 1 (Jan) -> fires
                "dispatch:MONTH:181-1",       // Month sees the NEW date
                "postUpdateMonthly",
            ),
            log,
        )
        // monthlyRng built ONCE and is the same instance postUpdateMonthly received.
        assertSame(rngHolder[0], seenByPost[0])
    }

    @Test
    fun `checkStatistic fires only when new month is 1`() {
        val log = mutableListOf<String>()
        val p = pipeline(log, newYear = 180, newMonth = 7) // not January
        p.runMonth(java.time.Instant.EPOCH, 0, java.time.Instant.EPOCH, 120,
            oldYear = 180, oldMonth = 6, dispatcher = RecordingDispatcher(log))
        assertFalse(log.contains("checkStatistic"))
        assertTrue(log.indexOf("dispatch:MONTH:180-7") > log.indexOf("preUpdateMonthly"))
    }

    @Test
    fun `monthlyRng is built once before PreMonth and passed only to postUpdateMonthly`() {
        val log = mutableListOf<String>()
        val buildCount = IntArray(1)
        val seenByPost = arrayOfNulls<Any?>(1)
        val p = pipeline(log, rngBuildCount = buildCount, rngSeenByPost = seenByPost,
            newYear = 180, newMonth = 4)
        p.runMonth(java.time.Instant.EPOCH, 0, java.time.Instant.EPOCH, 120,
            oldYear = 180, oldMonth = 3, dispatcher = RecordingDispatcher(log))
        assertEquals(1, buildCount[0]) // exactly one monthlyRng per month
        assertTrue(seenByPost[0] != null) // postUpdateMonthly got it
    }

    @Test
    fun `preUpdateMonthly returning false aborts before turnDate and Month and postUpd`() {
        val log = mutableListOf<String>()
        val p = pipeline(log, preResult = false, newYear = 181, newMonth = 1)
        p.runMonth(java.time.Instant.EPOCH, 0, java.time.Instant.EPOCH, 120,
            oldYear = 180, oldMonth = 12, dispatcher = RecordingDispatcher(log))
        assertEquals(listOf("dispatch:PRE_MONTH:180-12", "preUpdateMonthly"), log)
        assertFalse(log.contains("dispatch:MONTH:181-1"))
        assertFalse(log.contains("postUpdateMonthly"))
        assertFalse(log.contains("checkStatistic"))
    }
}

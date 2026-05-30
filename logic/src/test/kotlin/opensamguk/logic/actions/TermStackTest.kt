package opensamguk.logic.actions

import opensamguk.logic.domain.LastTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FS2 — capset-seq term-stack helper.
 *
 * Byte-faithful port of the CUSTOM `addTermStack` shared by 감축/증축/천도
 * (`che_감축.php:98-133`, `che_천도.php:131-174` — identical algorithm). The 4-field
 * [LastTurn] `(command, arg, term, seq)` rides the actor's prior turn; `seq` carries the
 * NATION `capset` at the time the stack was opened. The PHP check ORDER is load-bearing:
 *
 *  1. command/arg differs  → reset `LastTurn(name, arg, 1, capset)`, NOT ready
 *  2. `lastTurn.seq < capset` (a recent 감축/증축/천도 bumped capset) → reset `(.., 1, capset)`, NOT ready
 *  3. `lastTurn.term < preReqTurn` → `(.., term+1, capset)`, NOT ready
 *  4. else → READY
 *
 * On success the command calls `setResultTurn(new LastTurn(name, arg, 0))` (che_감축.php:206) —
 * term reset to 0, seq OMITTED (null). The base no-stack short-circuit (`preReqTurn == 0 → ready`,
 * BaseCommand.php:423-425) is preserved.
 */
class TermStackTest {

    @Test
    fun `preReqTurn 0 is immediately ready with no stack mutation`() {
        val prior = LastTurn("증축", arg = mapOf("destCityID" to 5), term = 0, seq = 1)
        val r = addTermStack(prior, command = "증축", arg = mapOf("destCityID" to 5), preReqTurn = 0, capset = 1)
        assertTrue(r.ready)
        assertEquals(prior, r.resultTurn)   // unchanged
    }

    @Test
    fun `differing command resets term to 1 and stamps the current capset`() {
        val prior = LastTurn("천도", arg = mapOf("destCityID" to 9), term = 3, seq = 4)
        val r = addTermStack(prior, command = "감축", arg = mapOf("destCityID" to 5), preReqTurn = 5, capset = 4)
        assertFalse(r.ready)
        assertEquals(LastTurn("감축", arg = mapOf("destCityID" to 5), term = 1, seq = 4), r.resultTurn)
    }

    @Test
    fun `differing arg resets term to 1 even when command matches`() {
        val prior = LastTurn("감축", arg = mapOf("destCityID" to 5), term = 2, seq = 4)
        val r = addTermStack(prior, command = "감축", arg = mapOf("destCityID" to 6), preReqTurn = 5, capset = 4)
        assertFalse(r.ready)
        assertEquals(LastTurn("감축", arg = mapOf("destCityID" to 6), term = 1, seq = 4), r.resultTurn)
    }

    @Test
    fun `seq less than capset resets the in-flight stack (a recent capset bump invalidates it)`() {
        // same command + same arg, but capset advanced from 4 -> 5 since the stack opened
        val prior = LastTurn("감축", arg = mapOf("destCityID" to 5), term = 3, seq = 4)
        val r = addTermStack(prior, command = "감축", arg = mapOf("destCityID" to 5), preReqTurn = 5, capset = 5)
        assertFalse(r.ready)
        assertEquals(LastTurn("감축", arg = mapOf("destCityID" to 5), term = 1, seq = 5), r.resultTurn)
    }

    @Test
    fun `term increments toward preReqTurn while command arg and seq stay aligned`() {
        var lt = LastTurn("증축", arg = mapOf("destCityID" to 5), term = 1, seq = 7)
        // poll until ready: preReqTurn = 5
        for (expected in 2..5) {
            val r = addTermStack(lt, command = "증축", arg = mapOf("destCityID" to 5), preReqTurn = 5, capset = 7)
            assertFalse(r.ready, "term $expected should still be charging")
            assertEquals(LastTurn("증축", arg = mapOf("destCityID" to 5), term = expected, seq = 7), r.resultTurn)
            lt = r.resultTurn
        }
        // now term == preReqTurn (5) → ready
        val rReady = addTermStack(lt, command = "증축", arg = mapOf("destCityID" to 5), preReqTurn = 5, capset = 7)
        assertTrue(rReady.ready)
        assertEquals(lt, rReady.resultTurn)   // no further mutation once ready
    }

    @Test
    fun `term equal to preReqTurn is ready`() {
        val prior = LastTurn("감축", arg = mapOf("destCityID" to 5), term = 5, seq = 2)
        val r = addTermStack(prior, command = "감축", arg = mapOf("destCityID" to 5), preReqTurn = 5, capset = 2)
        assertTrue(r.ready)
        assertEquals(prior, r.resultTurn)
    }

    @Test
    fun `the check order is command-arg before seq before term`() {
        // command differs AND seq<capset AND term<preReqTurn — branch 1 must win (term=1, capset stamped)
        val prior = LastTurn("천도", arg = mapOf("destCityID" to 9), term = 2, seq = 1)
        val r = addTermStack(prior, command = "감축", arg = mapOf("destCityID" to 5), preReqTurn = 5, capset = 9)
        assertEquals(LastTurn("감축", arg = mapOf("destCityID" to 5), term = 1, seq = 9), r.resultTurn)
    }

    @Test
    fun `null arg matches null arg and resets when one side has an arg`() {
        // both null arg, same command, aligned seq -> increments
        val prior = LastTurn("감축", arg = null, term = 2, seq = 3)
        val r = addTermStack(prior, command = "감축", arg = null, preReqTurn = 5, capset = 3)
        assertFalse(r.ready)
        assertEquals(LastTurn("감축", arg = null, term = 3, seq = 3), r.resultTurn)

        // prior null arg vs incoming non-null arg -> differs -> reset to term 1
        val r2 = addTermStack(prior, command = "감축", arg = mapOf("destCityID" to 5), preReqTurn = 5, capset = 3)
        assertEquals(LastTurn("감축", arg = mapOf("destCityID" to 5), term = 1, seq = 3), r2.resultTurn)
    }

    @Test
    fun `onTermStackSuccess resets term to 0 and OMITS seq`() {
        val success = onTermStackSuccess(command = "감축", arg = mapOf("destCityID" to 5))
        assertEquals("감축", success.command)
        assertEquals(mapOf("destCityID" to 5), success.arg)
        assertEquals(0, success.term)
        assertNull(success.seq)
        // and the resulting toRaw drops seq (delete-on-default jsonb), keeping command/arg/term
        assertEquals(listOf("command", "arg", "term"), success.toRaw().keys.toList())
    }

    @Test
    fun `bumpCapset advances the nation capset by one (any 감축 증축 천도 invalidates in-flight stacks)`() {
        assertEquals(5, bumpCapset(4))
        // an in-flight stack opened at the OLD capset is now stale: seq(4) < capset(5) -> reset
        val prior = LastTurn("증축", arg = mapOf("destCityID" to 5), term = 4, seq = 4)
        val r = addTermStack(prior, command = "증축", arg = mapOf("destCityID" to 5), preReqTurn = 5, capset = bumpCapset(4))
        assertFalse(r.ready)
        assertEquals(1, r.resultTurn.term)
        assertEquals(5, r.resultTurn.seq)
    }
}

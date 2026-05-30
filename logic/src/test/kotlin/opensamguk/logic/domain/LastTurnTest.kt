package opensamguk.logic.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * FD0 — `LastTurn.toRaw()` delete-on-default shape, faithful to `LastTurn.php:74-88`.
 *
 * `command` is ALWAYS emitted (default `휴식`); `arg`/`term`/`seq` appear ONLY when non-null, in
 * command→arg→term→seq insertion order (the byte-comparable jsonb the golden last_turn dump wants).
 */
class LastTurnTest {

    @Test
    fun `default LastTurn toRaw is just command 휴식 with no arg term or seq`() {
        val raw = LastTurn("휴식").toRaw()
        assertEquals(listOf("command"), raw.keys.toList())
        assertEquals("휴식", raw["command"])
    }

    @Test
    fun `no-arg constructor defaults command to 휴식`() {
        assertEquals("휴식", LastTurn().command)
        assertEquals(mapOf("command" to "휴식"), LastTurn().toRaw())
    }

    @Test
    fun `a fully-populated LastTurn emits all four keys in command arg term seq order`() {
        val raw = LastTurn(
            command = "증축",
            arg = linkedMapOf("destCityID" to 5),
            term = 2,
            seq = 4,
        ).toRaw()
        assertEquals(listOf("command", "arg", "term", "seq"), raw.keys.toList())
        assertEquals("증축", raw["command"])
        assertEquals(linkedMapOf("destCityID" to 5), raw["arg"])
        assertEquals(2, raw["term"])
        assertEquals(4, raw["seq"])
    }

    @Test
    fun `arg present but term and seq null emits only command and arg`() {
        val raw = LastTurn(command = "상업 투자", arg = linkedMapOf("amount" to 100)).toRaw()
        assertEquals(listOf("command", "arg"), raw.keys.toList())
        assertEquals(linkedMapOf("amount" to 100), raw["arg"])
    }

    @Test
    fun `term present but arg null still preserves command then term order`() {
        // PHP includes arg only when non-null, so a null arg is skipped even when term is present.
        val raw = LastTurn(command = "감축", term = 3).toRaw()
        assertEquals(listOf("command", "term"), raw.keys.toList())
        assertEquals(3, raw["term"])
    }

    @Test
    fun `fromRaw of null or empty yields the default 휴식`() {
        assertEquals(LastTurn(), LastTurn.fromRaw(null))
        assertEquals(LastTurn(), LastTurn.fromRaw(emptyMap()))
        assertEquals("휴식", LastTurn.fromRaw(null).command)
    }

    @Test
    fun `fromRaw round-trips a populated raw map`() {
        val src = LastTurn(command = "천도", arg = linkedMapOf("destCityID" to 10), term = 1, seq = 6)
        val back = LastTurn.fromRaw(src.toRaw())
        assertEquals(src.command, back.command)
        assertEquals(src.arg, back.arg)
        assertEquals(src.term, back.term)
        assertEquals(src.seq, back.seq)
    }

    @Test
    fun `fromRaw defaults a null command back to 휴식`() {
        val raw = mapOf<String, Any?>("command" to null, "term" to 2)
        val lt = LastTurn.fromRaw(raw)
        assertEquals("휴식", lt.command)
        assertEquals(2, lt.term)
        assertNull(lt.arg)
    }
}

package opensamguk.logic.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FS2 — [AiUtils] order-then-pick helper parity tests.
 *
 * Port target = the deterministic candidate-ordering idioms in PHP `GeneralAI.php` — the
 * `usort($x, fn(lhs,rhs)=>lhs<=>rhs)` value-asc sorts (`:1247/1344/1447/1544/1584/1663/1702`), the
 * `uasort` key-preserving sorts (`:4027/4069`), the `arsort($x)` value-DESC key-preserving sorts
 * (`:1813` 천도, `:2075` 천도-score), the `count($list) - $idx` rank-weight (`:1288` 포상), and the
 * `choiceUsingWeightPair($candidateArgs)` candidate-list build (`:1302`).
 *
 * PHP 8 sorts are UNCONDITIONALLY STABLE — equal elements keep their original (insertion) relative order.
 * The wrappers add NO secondary comparator on ties (CLAUDE.md parity law). The candidate list order seeds
 * every downstream `choiceUsingWeightPair` draw — a reorder on a tie shifts the idx → shifts the weight →
 * shifts the single draw → total desync.
 */
class AiUtilsTest {

    // --- (1) usort: value-asc, STABLE on ties (no secondary comparator) ---

    @Test
    fun `usort sorts ascending and keeps insertion order on equal keys`() {
        // Tagged distinct objects with equal sort keys must keep their insertion order.
        data class G(val tag: String, val res: Int)
        val src = listOf(G("a", 5), G("b", 3), G("c", 5), G("d", 3), G("e", 1))
        val out = AiUtils.usort(src) { lhs, rhs -> lhs.res.compareTo(rhs.res) }
        // sorted asc by res; ties (res 3: b,d) (res 5: a,c) keep insertion order
        assertEquals(listOf("e", "b", "d", "a", "c"), out.map { it.tag })
        // source list not mutated
        assertEquals(listOf("a", "b", "c", "d", "e"), src.map { it.tag })
    }

    @Test
    fun `usort with spaceship-style comparator matches PHP lhs cmp rhs ascending`() {
        val src = listOf(5, 1, 5, 3, 1)
        val out = AiUtils.usort(src) { a, b -> a.compareTo(b) }
        assertEquals(listOf(1, 1, 3, 5, 5), out)
    }

    // --- (2) uasort: same stable order, key-preserving (LinkedHashMap entries) ---

    @Test
    fun `uasort preserves keys and is stable on ties (descending stat example)`() {
        // mirrors GeneralAI.php:4069 uasort(generals, -(stat <=> stat)) — DESC, key-preserving, stable
        val src = linkedMapOf(10 to 50, 11 to 70, 12 to 50, 13 to 70)
        val out = AiUtils.uasort(src) { lhs, rhs -> -(lhs.compareTo(rhs)) }
        // desc by value; ties (70: keys 11,13) (50: keys 10,12) keep insertion order
        assertEquals(listOf(11, 13, 10, 12), out.keys.toList())
        assertEquals(listOf(70, 70, 50, 50), out.values.toList())
    }

    // --- (3) arsort: value-DESC, key-preserving, STABLE on ties ---

    @Test
    fun `arsort sorts values descending preserving keys and is stable on ties`() {
        // mirrors GeneralAI.php:1813/2075 arsort(candidateList) — value DESC, keys preserved, stable
        val src = linkedMapOf("n5" to 100.0, "n3" to 60.0, "n7" to 100.0, "n2" to 60.0, "n9" to 20.0)
        val out = AiUtils.arsort(src)
        // desc by value; ties (100: n5,n7) (60: n3,n2) keep insertion order
        assertEquals(listOf("n5", "n7", "n3", "n2", "n9"), out.keys.toList())
        assertEquals(listOf(100.0, 100.0, 60.0, 60.0, 20.0), out.values.toList())
    }

    @Test
    fun `arsort firstKey returns the highest-value key (array_key_first after arsort)`() {
        val src = linkedMapOf("a" to 30.0, "b" to 90.0, "c" to 90.0)
        val out = AiUtils.arsort(src)
        // tie at 90 (b,c) → b stays first → array_key_first == b
        assertEquals("b", out.keys.first())
    }

    // --- (4) count - idx rank-weight (GeneralAI.php:1288) ---

    @Test
    fun `rankWeight is count minus idx (positional rank after a stable sort)`() {
        // count=5: idx 0..4 → weights 5,4,3,2,1 (best/first highest)
        assertEquals(5, AiUtils.rankWeight(count = 5, idx = 0))
        assertEquals(4, AiUtils.rankWeight(count = 5, idx = 1))
        assertEquals(1, AiUtils.rankWeight(count = 5, idx = 4))
    }

    // --- (5) choiceUsingWeightPair candidate-list builder: insertion order preserved ---

    @Test
    fun `weightedPair builder preserves append order (List of item to weight)`() {
        // build the [[arg, weight], ...] list the AI feeds rng.choiceUsingWeightPair, in append order
        val builder = AiUtils.weightedPairBuilder<String>()
        builder.add("first", 5.0)
        builder.add("second", 4.0)
        builder.add("third", 3.0)
        val pairs = builder.build()
        assertEquals(listOf("first" to 5.0, "second" to 4.0, "third" to 3.0), pairs)
    }

    @Test
    fun `weightedPair builder with count-idx weight matches the reward idiom`() {
        // simulate GeneralAI.php:1282-1289: usort then append [arg, count-idx]
        data class Cand(val tag: String, val res: Int)
        val src = listOf(Cand("hi", 9), Cand("lo", 1), Cand("mid", 5))
        val sorted = AiUtils.usort(src) { a, b -> a.res.compareTo(b.res) } // asc: lo,mid,hi
        val builder = AiUtils.weightedPairBuilder<String>()
        val count = sorted.size
        sorted.forEachIndexed { idx, c -> builder.add(c.tag, AiUtils.rankWeight(count, idx).toDouble()) }
        // lo idx0 weight3, mid idx1 weight2, hi idx2 weight1
        assertEquals(listOf("lo" to 3.0, "mid" to 2.0, "hi" to 1.0), builder.build())
    }

    // --- (6) insertion-ordered LinkedHashMap builder ---

    @Test
    fun `linkedMapOf builder preserves insertion order, never re-keyed`() {
        val m = AiUtils.orderedMap<Int, Double>()
        m[7] = 1.0
        m[3] = 2.0
        m[9] = 3.0
        // insertion order 7,3,9 — NOT numerically sorted 3,7,9
        assertEquals(listOf(7, 3, 9), m.keys.toList())
    }

    @Test
    fun `usort returns a new list and never aliases the source`() {
        val src = listOf(3, 1, 2)
        val out = AiUtils.usort(src) { a, b -> a.compareTo(b) }
        assert(out !== src)
        assertEquals(listOf(1, 2, 3), out)
        assertEquals(listOf(3, 1, 2), src)
    }

    @Test
    fun `arsort returns a new LinkedHashMap and does not mutate the source`() {
        val src = linkedMapOf("a" to 1.0, "b" to 2.0)
        val out = AiUtils.arsort(src)
        assert(out !== src)
        assertEquals(listOf("a", "b"), src.keys.toList()) // source untouched
        // returns a concrete LinkedHashMap (insertion-ordered, never re-keyed)
        assertEquals(LinkedHashMap::class, (out as Any)::class)
    }
}

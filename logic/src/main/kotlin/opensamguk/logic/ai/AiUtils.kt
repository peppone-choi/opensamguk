package opensamguk.logic.ai

/**
 * F-SEED — the order-then-pick helpers: stable `usort`/`uasort`/`arsort` wrappers, insertion-ordered
 * `LinkedHashMap` builders, the `count - idx` rank-weight pattern, and the `choiceUsingWeightPair`
 * candidate-list builder.
 *
 * ## Why these exist (the parity contract)
 * Every `do<한글>` decision builds a candidate list, sorts it deterministically, then pulls ONE draw off
 * the shared `"GeneralAI"` stream (`choice`/`choiceUsingWeight`/`choiceUsingWeightPair`). The candidate
 * ORDER is itself a byte-parity target: a reorder on a tie shifts the positional `idx` → shifts the
 * `count - idx` weight → shifts the single draw → desyncs every downstream draw. PHP 8 sorts are
 * **unconditionally STABLE** (equal elements keep their original relative order); these wrappers reproduce
 * that exactly and add **NO secondary comparator on ties** (CLAUDE.md parity law). Kotlin's
 * [List.sortedWith] / [List.sortWith] are documented stable, so a faithful comparator port is byte-equal.
 *
 * Port targets in `GeneralAI.php`:
 * - `usort($x, fn(lhs,rhs)=>lhs<=>rhs)` value-asc reward sorts: `:1247/1344/1447/1544/1584/1663/1702`.
 * - `uasort($x, ...)` key-preserving sorts: `:4027` (chief-pick), `:4069` (stat-desc).
 * - `arsort($x)` value-DESC key-preserving sorts: `:1813` (천도 candidate), `:2075` (천도 city-score).
 * - `count($userWarGenerals) - $idx` rank-weight: `:1288` (포상).
 * - `$this->rng->choiceUsingWeightPair($candidateArgs)`: `:1302` (포상 final pick).
 */
object AiUtils {

    /**
     * PHP `usort($arr, $cmp)` — value sort, re-index, STABLE on ties (PHP 8). Returns a NEW list; the
     * source is never mutated (mirrors that the AI snapshots `$this->userWarGenerals` into a local before
     * sorting). [cmp] returns negative/zero/positive exactly like PHP's `<=>` spaceship — NO secondary
     * comparator is added on a zero (tie) result.
     */
    fun <T> usort(items: List<T>, cmp: (T, T) -> Int): List<T> =
        items.sortedWith(Comparator(cmp))

    /**
     * PHP `uasort($arr, $cmp)` — sort BY VALUE while PRESERVING the keys, STABLE on ties (PHP 8). Returns a
     * NEW [LinkedHashMap] in the sorted entry order; the source is never mutated. The comparator receives
     * the VALUES (PHP `uasort` passes `$a, $b` = the values).
     */
    fun <K, V> uasort(items: Map<K, V>, cmp: (V, V) -> Int): LinkedHashMap<K, V> {
        val out = LinkedHashMap<K, V>(items.size)
        for (e in items.entries.sortedWith(compareBy(Comparator(cmp)) { it.value })) {
            out[e.key] = e.value
        }
        return out
    }

    /**
     * PHP `arsort($arr)` — sort BY VALUE in DESCENDING order while PRESERVING the keys, STABLE on ties
     * (PHP 8). Returns a NEW [LinkedHashMap] in the sorted order; the source is never mutated. Equal values
     * keep their original insertion order (so `array_key_first` after `arsort` is the first-inserted of the
     * top-value tie group). Values are [Comparable] (the AI's score/amount maps are numeric).
     */
    fun <K, V : Comparable<V>> arsort(items: Map<K, V>): LinkedHashMap<K, V> {
        val out = LinkedHashMap<K, V>(items.size)
        // DESC by value, STABLE: negate the comparison; sortedWith is stable so ties keep insertion order.
        for (e in items.entries.sortedWith(compareByDescending { it.value })) {
            out[e.key] = e.value
        }
        return out
    }

    /**
     * The positional rank-weight `count - idx` (GeneralAI.php:1288): the best (idx 0, first after a stable
     * sort) gets the highest weight `count`; the last (idx `count-1`) gets weight `1`. This feeds
     * `choiceUsingWeightPair`, biasing the single draw toward the front of the sorted candidate list.
     */
    fun rankWeight(count: Int, idx: Int): Int = count - idx

    /** A fresh insertion-ordered map (never re-keyed by id) for building candidate/score lists. */
    fun <K, V> orderedMap(): LinkedHashMap<K, V> = LinkedHashMap()

    /** Build the `[item, weight]` candidate list (append order preserved) that `choiceUsingWeightPair` walks. */
    fun <T> weightedPairBuilder(): WeightedPairBuilder<T> = WeightedPairBuilder()

    /**
     * Accumulates `(item, weight)` candidate pairs in APPEND order — the exact `$candidateArgs[] = [arg, w]`
     * idiom (GeneralAI.php:1282-1289). The built list is fed verbatim to `RandUtil.choiceUsingWeightPair`,
     * whose walk depends on this order. Never re-sorted/re-keyed after build.
     */
    class WeightedPairBuilder<T> {
        private val pairs = ArrayList<Pair<T, Double>>()

        fun add(item: T, weight: Double): WeightedPairBuilder<T> {
            pairs.add(item to weight)
            return this
        }

        fun isEmpty(): Boolean = pairs.isEmpty()

        fun build(): List<Pair<T, Double>> = pairs.toList()
    }
}

package opensamguk.common.rng

// `open` so battle draw-order tests (P4 war triggers) can subclass with a recording/scripted RNG that
// captures the semantic nextBool(prob)/choice(keys) sequence — the load-bearing draw-order parity surface.
// Behavior is unchanged; this is a test-enabling visibility change only.
open class RandUtil(private val rng: LiteHashDrbg) {
    open fun nextFloat1(): Double = rng.nextFloat1()
    open fun nextRange(min: Double, max: Double): Double = nextFloat1() * (max - min) + min
    open fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int =
        rng.nextInt((maxInclusive - minInclusive).toLong()).toInt() + minInclusive
    open fun nextInt(minInclusive: Int, maxExclusive: Int): Int {
        val span = maxExclusive - minInclusive
        if (span <= 1) return minInclusive
        return minInclusive + rng.nextInt((span - 1).toLong()).toInt()
    }
    open fun nextBit(): Boolean = rng.nextBits(1)[0].toInt() != 0
    open fun nextBool(prob: Double = 0.5): Boolean {
        if (prob >= 1) return true
        if (prob == 0.5) return nextBit()
        if (prob <= 0) return false
        return nextFloat1() < prob
    }
    open fun <T> shuffle(srcArray: List<T>): List<T> {
        val cnt = srcArray.size
        if (cnt == 0) return emptyList()
        if (cnt.toLong() > rng.getMaxInt()) throw IllegalStateException("Invalid random int range")
        val result = ArrayList(srcArray)
        for (srcIdx in 0 until cnt) {
            val destIdx = rng.nextInt((cnt - srcIdx - 1).toLong()).toInt() + srcIdx
            if (srcIdx == destIdx) continue
            val tmp = result[srcIdx]; result[srcIdx] = result[destIdx]; result[destIdx] = tmp
        }
        return result
    }
    open fun <T> choice(items: List<T>): T {
        if (items.isEmpty()) throw IllegalArgumentException("Empty items")
        return items[rng.nextInt((items.size - 1).toLong()).toInt()]
    }
    fun <T> choiceSet(items: Set<T>): T = choice(items.toList())
    fun <T> choiceMap(items: Map<String, T>): T = items.getValue(choice(jsKeyOrder(items.keys)))
    // `open` (like the other draw helpers above) so the AiDrawRecorder can log the weighted pick as its OWN
    // wrapper entry. The body is unchanged; the inner `nextFloat1()` still records through virtual dispatch, so
    // the wrapper rides ON TOP of the inner float draw (same before-cursor). The engine-side gate COLLAPSES these
    // zero-byte wrappers on both golden + live, so only the inner RNG draw is asserted.
    //
    // KEY ORDER = INSERTION (PHP-faithful). PHP `RandUtil::choiceUsingWeight` (src/sammo/RandUtil.php:104-130)
    // walks `foreach ($items as $item => $value)` — PHP arrays are insertion-ordered, so we iterate
    // `items.keys` (LinkedHashMap insertion order, what `linkedMapOf`/`mapOf` produce) directly. We do NOT use
    // `jsKeyOrder` here: that helper models frozen historical core2026 TS Object-key order
    // (ADR-LITE-042; not current product authority; integer keys ascending
    // FIRST, then string keys). jsKeyOrder == insertion order for all-string or ascending-int keys — so every
    // existing caller is byte-unchanged — but it DIVERGES for an int-like key inserted AFTER string keys, e.g.
    // SpecialityHelper's `pAbs["0"] = max(0,100-sum)` sentinel: PHP keeps the int key last, jsKeyOrder floats
    // it first, which silently changed the war-/domestic-special pick. The frozen historical PHP comparison
    // (ADR-LITE-042; not current product authority) records insertion order, which the retained order contract preserves.
    // (`choiceMap` still uses jsKeyOrder pending its frozen regression disposition; no divergent caller exercises it today.)
    open fun choiceUsingWeight(items: Map<String, Double>): String {
        if (items.isEmpty()) throw IllegalArgumentException("Empty items")
        val keys = items.keys
        var sum = 0.0; for (k in keys) { val v = items.getValue(k); if (v > 0) sum += v }
        var rd = nextFloat1() * sum
        for (k in keys) { val v = items.getValue(k); if (v <= 0) { if (rd <= 0) return k; continue }; if (rd <= v) return k; rd -= v }
        throw IllegalStateException("Unreacheable")
    }
    open fun <T> choiceUsingWeightPair(items: List<Pair<T, Double>>): T {
        if (items.isEmpty()) throw IllegalArgumentException("Empty items")
        var sum = 0.0; for ((_, v) in items) if (v > 0) sum += v
        var rd = nextFloat1() * sum
        for ((item, v) in items) { if (v <= 0) { if (rd <= 0) return item; continue }; if (rd <= v) return item; rd -= v }
        throw IllegalStateException("Unreacheable")
    }
}

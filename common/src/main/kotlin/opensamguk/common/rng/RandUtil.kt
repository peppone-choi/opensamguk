package opensamguk.common.rng

class RandUtil(private val rng: LiteHashDrbg) {
    fun nextFloat1(): Double = rng.nextFloat1()
    fun nextRange(min: Double, max: Double): Double = nextFloat1() * (max - min) + min
    fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int =
        rng.nextInt((maxInclusive - minInclusive).toLong()).toInt() + minInclusive
    fun nextInt(minInclusive: Int, maxExclusive: Int): Int {
        val span = maxExclusive - minInclusive
        if (span <= 1) return minInclusive
        return minInclusive + rng.nextInt((span - 1).toLong()).toInt()
    }
    fun nextBit(): Boolean = rng.nextBits(1)[0].toInt() != 0
    fun nextBool(prob: Double = 0.5): Boolean {
        if (prob >= 1) return true
        if (prob == 0.5) return nextBit()
        if (prob <= 0) return false
        return nextFloat1() < prob
    }
    fun <T> shuffle(srcArray: List<T>): List<T> {
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
    fun <T> choice(items: List<T>): T {
        if (items.isEmpty()) throw IllegalArgumentException("Empty items")
        return items[rng.nextInt((items.size - 1).toLong()).toInt()]
    }
    fun <T> choiceSet(items: Set<T>): T = choice(items.toList())
    fun <T> choiceMap(items: Map<String, T>): T = items.getValue(choice(jsKeyOrder(items.keys)))
    fun choiceUsingWeight(items: Map<String, Double>): String {
        if (items.isEmpty()) throw IllegalArgumentException("Empty items")
        val keys = jsKeyOrder(items.keys)
        var sum = 0.0; for (k in keys) { val v = items.getValue(k); if (v > 0) sum += v }
        var rd = nextFloat1() * sum
        for (k in keys) { val v = items.getValue(k); if (v <= 0) { if (rd <= 0) return k; continue }; if (rd <= v) return k; rd -= v }
        throw IllegalStateException("Unreacheable")
    }
    fun <T> choiceUsingWeightPair(items: List<Pair<T, Double>>): T {
        if (items.isEmpty()) throw IllegalArgumentException("Empty items")
        var sum = 0.0; for ((_, v) in items) if (v > 0) sum += v
        var rd = nextFloat1() * sum
        for ((item, v) in items) { if (v <= 0) { if (rd <= 0) return item; continue }; if (rd <= v) return item; rd -= v }
        throw IllegalStateException("Unreacheable")
    }
}

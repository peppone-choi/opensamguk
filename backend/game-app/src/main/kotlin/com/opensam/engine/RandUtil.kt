package com.opensam.engine

class RandUtil(val rng: LiteHashDRBG) {
    fun nextFloat1(): Double {
        return rng.nextFloat1()
    }

    fun nextRange(min: Number, max: Number): Double {
        val minValue = min.toDouble()
        val maxValue = max.toDouble()
        val range = maxValue - minValue
        return nextFloat1() * range + minValue
    }

    fun nextRangeInt(min: Int, max: Int): Int {
        val range = max - min
        if (range.toLong() > rng.getMaxInt()) {
            throw IllegalArgumentException("Invalid random int range")
        }
        return rng.nextLegacyInt(range.toLong()).toInt() + min
    }

    fun nextInt(max: Long? = null): Long {
        return rng.nextLegacyInt(max)
    }

    fun nextBit(): Boolean {
        return rng.nextBitsBytes(1)[0] != 0.toByte()
    }

    fun nextBool(prob: Number = 0.5): Boolean {
        val p = prob.toDouble()
        if (p >= 1.0) {
            return true
        }
        if (p == 0.5) {
            return nextBit()
        }
        if (p <= 0.0) {
            return false
        }
        return nextFloat1() < p
    }

    fun <T> shuffle(srcList: List<T>): List<T> {
        if (srcList.isEmpty()) {
            return srcList
        }

        val cnt = srcList.size
        if (cnt.toLong() > rng.getMaxInt()) {
            throw IllegalArgumentException("Invalid random int range")
        }

        val result = srcList.toMutableList()
        for (srcIdx in 0 until cnt) {
            val destIdx = rng.nextLegacyInt((cnt - srcIdx - 1).toLong()).toInt() + srcIdx
            if (srcIdx == destIdx) {
                continue
            }
            val tmp = result[srcIdx]
            result[srcIdx] = result[destIdx]
            result[destIdx] = tmp
        }

        return result
    }

    fun <K, V> shuffleAssoc(srcMap: Map<K, V>): Map<K, V> {
        if (srcMap.isEmpty()) {
            return srcMap
        }

        val result = linkedMapOf<K, V>()
        for (key in shuffle(srcMap.keys.toList())) {
            result[key] = srcMap.getValue(key)
        }
        return result
    }

    fun <T> choice(items: List<T>): T {
        if (items.isEmpty()) {
            throw IllegalArgumentException()
        }
        val keyIdx = rng.nextLegacyInt((items.size - 1).toLong()).toInt()
        return items[keyIdx]
    }

    fun <K, V> choice(items: Map<K, V>): V {
        if (items.isEmpty()) {
            throw IllegalArgumentException()
        }
        val keys = items.keys.toList()
        val keyIdx = rng.nextLegacyInt((keys.size - 1).toLong()).toInt()
        return items.getValue(keys[keyIdx])
    }

    fun <T> choiceUsingWeight(items: Map<T, Number>): T {
        if (items.isEmpty()) {
            throw IllegalArgumentException()
        }

        var sum = 0.0
        for (value in items.values) {
            val numericValue = value.toDouble()
            if (numericValue <= 0.0) {
                continue
            }
            sum += numericValue
        }

        var rd = nextFloat1() * sum
        for ((item, valueRaw) in items) {
            var value = valueRaw.toDouble()
            if (value <= 0.0) {
                value = 0.0
            }
            if (rd <= value) {
                return item
            }
            rd -= value
        }

        return items.keys.last()
    }

    fun <T> choiceUsingWeightPair(items: List<Pair<T, Number>>): T {
        if (items.isEmpty()) {
            throw IllegalArgumentException()
        }

        var sum = 0.0
        for ((_, value) in items) {
            val numericValue = value.toDouble()
            if (numericValue <= 0.0) {
                continue
            }
            sum += numericValue
        }

        var rd = nextFloat1() * sum
        for ((item, valueRaw) in items) {
            var value = valueRaw.toDouble()
            if (value <= 0.0) {
                value = 0.0
            }
            if (rd <= value) {
                return item
            }
            rd -= value
        }

        return items.last().first
    }
}

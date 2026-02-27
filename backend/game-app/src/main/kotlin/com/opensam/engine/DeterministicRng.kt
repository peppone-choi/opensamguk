package com.opensam.engine

import kotlin.random.Random

object DeterministicRng {
    fun create(hiddenSeed: String, vararg tags: Any): Random {
        val combined = (listOf(hiddenSeed) + tags.map { it.toString() }).joinToString("|")
        return LiteHashDRBG.build(combined)
    }
}

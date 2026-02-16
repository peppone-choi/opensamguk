package com.opensam.engine

import java.security.MessageDigest
import kotlin.random.Random

object DeterministicRng {
    fun create(hiddenSeed: String, vararg tags: Any): Random {
        val combined = (listOf(hiddenSeed) + tags.map { it.toString() }).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(combined.toByteArray(Charsets.UTF_8))
        var seed = 0L
        for (i in 0 until 8) {
            seed = (seed shl 8) or (digest[i].toLong() and 0xFF)
        }
        return Random(seed)
    }
}

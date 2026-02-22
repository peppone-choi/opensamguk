package com.opensam.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeterministicRngTest {

    @Test
    fun `same seed and tags produce same sequence`() {
        val rng1 = DeterministicRng.create("world1", "general", 1L, 200, 1)
        val rng2 = DeterministicRng.create("world1", "general", 1L, 200, 1)

        val values1 = (1..10).map { rng1.nextInt() }
        val values2 = (1..10).map { rng2.nextInt() }

        assertEquals(values1, values2)
    }

    @Test
    fun `different seeds produce different sequences`() {
        val rng1 = DeterministicRng.create("world1", "general", 1L, 200, 1)
        val rng2 = DeterministicRng.create("world2", "general", 1L, 200, 1)

        val values1 = (1..10).map { rng1.nextInt() }
        val values2 = (1..10).map { rng2.nextInt() }

        assertNotEquals(values1, values2)
    }

    @Test
    fun `different tags produce different sequences`() {
        val rng1 = DeterministicRng.create("world1", "general", 1L, 200, 1)
        val rng2 = DeterministicRng.create("world1", "nation", 1L, 200, 1)

        val values1 = (1..10).map { rng1.nextInt() }
        val values2 = (1..10).map { rng2.nextInt() }

        assertNotEquals(values1, values2)
    }

    @Test
    fun `different general ids produce different sequences`() {
        val rng1 = DeterministicRng.create("world1", "general", 1L, 200, 1)
        val rng2 = DeterministicRng.create("world1", "general", 2L, 200, 1)

        val values1 = (1..5).map { rng1.nextInt() }
        val values2 = (1..5).map { rng2.nextInt() }

        assertNotEquals(values1, values2)
    }

    @Test
    fun `different months produce different sequences`() {
        val rng1 = DeterministicRng.create("world1", "general", 1L, 200, 1)
        val rng2 = DeterministicRng.create("world1", "general", 1L, 200, 2)

        val v1 = rng1.nextInt()
        val v2 = rng2.nextInt()

        assertNotEquals(v1, v2)
    }

    @Test
    fun `rng produces values in expected range`() {
        val rng = DeterministicRng.create("test", "seed")
        val value = rng.nextDouble()
        assertTrue(value in 0.0..1.0)
    }
}

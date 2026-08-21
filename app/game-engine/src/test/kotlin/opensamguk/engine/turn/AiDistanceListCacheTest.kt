package opensamguk.engine.turn

import kotlin.test.Test
import kotlin.test.assertEquals

class AiDistanceListCacheTest {

    @Test
    fun `least recently used matrix is evicted and recomputes deterministically`() {
        val cache = AiDistanceListCache(maxEntries = 2)
        val computeCounts = linkedMapOf<List<Int>, Int>()

        fun distances(cityIds: List<Int>): Map<Int, Map<Int, Int>> =
            cache.getOrCompute("han", cityIds) { orderedIds ->
                computeCounts[orderedIds] = computeCounts.getOrDefault(orderedIds, 0) + 1
                orderedIds.associateWith { from ->
                    orderedIds.associateWith { to -> kotlin.math.abs(from - to) }
                }
            }

        val first = distances(listOf(3, 421))
        val evictedValue = distances(listOf(3, 29, 421))
        assertEquals(first, distances(listOf(3, 421)), "cache hit refreshes the access-order entry")

        distances(listOf(24, 29))
        val recomputed = distances(listOf(3, 29, 421))

        assertEquals(evictedValue, recomputed, "eviction must not change distance values or insertion order")
        assertEquals(2, computeCounts[listOf(3, 29, 421)], "the least-recently-used entry was recomputed")
        assertEquals(1, computeCounts[listOf(3, 421)], "the refreshed entry remained cached")
    }

    @Test
    fun `map and ordered membership are both part of the cache key`() {
        val cache = AiDistanceListCache(maxEntries = 4)
        var computations = 0

        fun lookup(mapName: String, cityIds: List<Int>) = cache.getOrCompute(mapName, cityIds) { orderedIds ->
            computations += 1
            orderedIds.associateWith { linkedMapOf(it to 0) }
        }

        lookup("han", listOf(3, 421))
        lookup("han", listOf(421, 3))
        lookup("che", listOf(3, 421))
        lookup("han", listOf(3, 421))

        assertEquals(3, computations)
    }
}

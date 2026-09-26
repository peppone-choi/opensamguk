package opensamguk.logic.ai.bfs

import opensamguk.logic.world.CityConstRegistry
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapDistancePerformanceTest {
    @Test
    fun `baseline map all-city distances preserve order within five second budget`() {
        val map = CityConstRegistry.of("han")
        val cityIds = map.all().keys.toList()
        lateinit var result: Map<Int, Map<Int, Int>>

        val elapsedNanos = measureNanoTime {
            result = AiDistance.searchAllDistanceByCityList(cityIds, map)
        }
        val elapsedMillis = elapsedNanos / 1_000_000.0

        assertEquals(774, result.size)
        assertEquals(cityIds, result.keys.toList())
        assertEquals(cityIds.first(), result.getValue(cityIds.first()).keys.first())
        assertTrue(elapsedMillis < 5_000.0, "774-city distance build took $elapsedMillis ms")
        println("MAP_DISTANCE_BUDGET elapsedMs=$elapsedMillis budgetMs=5000 cities=${result.size}")
    }
}

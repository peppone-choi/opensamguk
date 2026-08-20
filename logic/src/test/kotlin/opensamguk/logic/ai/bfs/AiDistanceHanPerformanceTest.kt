package opensamguk.logic.ai.bfs

import opensamguk.logic.world.CityConstRegistry
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiDistanceHanPerformanceTest {
    @Test
    fun `han all-city distances preserve order within five second budget`() {
        val han = CityConstRegistry.of("han")
        val cityIds = han.all().keys.toList()
        lateinit var result: Map<Int, Map<Int, Int>>

        val elapsedNanos = measureNanoTime {
            result = AiDistance.searchAllDistanceByCityList(cityIds, han)
        }
        val elapsedMillis = elapsedNanos / 1_000_000.0

        assertEquals(780, result.size)
        assertEquals(cityIds, result.keys.toList())
        assertEquals(cityIds.first(), result.getValue(cityIds.first()).keys.first())
        assertTrue(elapsedMillis < 5_000.0, "780-city distance build took $elapsedMillis ms")
        println("HAN_DISTANCE_BUDGET elapsedMs=$elapsedMillis budgetMs=5000 cities=${result.size}")
    }
}

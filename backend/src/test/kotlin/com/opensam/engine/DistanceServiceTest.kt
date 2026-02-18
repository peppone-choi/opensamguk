package com.opensam.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DistanceServiceTest {

    private val service = DistanceService()

    private val mockMap = DistanceService.DistanceMap(
        cities = listOf(
            DistanceService.DistanceCity(id = 1, connections = listOf(2, 3)),
            DistanceService.DistanceCity(id = 2, connections = listOf(1, 4)),
            DistanceService.DistanceCity(id = 3, connections = listOf(1, 5)),
            DistanceService.DistanceCity(id = 4, connections = listOf(2, 6)),
            DistanceService.DistanceCity(id = 5, connections = listOf(3, 7)),
            DistanceService.DistanceCity(id = 6, connections = listOf(4)),
            DistanceService.DistanceCity(id = 7, connections = listOf(5)),
            DistanceService.DistanceCity(id = 8, connections = emptyList()),
        ),
    )

    @Test
    fun `getCityDistance returns 0 for same city`() {
        assertEquals(0, service.getCityDistance(mockMap, 1, 1))
    }

    @Test
    fun `getCityDistance returns 1 for adjacent cities`() {
        assertEquals(1, service.getCityDistance(mockMap, 1, 2))
        assertEquals(1, service.getCityDistance(mockMap, 1, 3))
    }

    @Test
    fun `getCityDistance returns correct distance for distant cities`() {
        assertEquals(2, service.getCityDistance(mockMap, 1, 4))
        assertEquals(3, service.getCityDistance(mockMap, 1, 6))
    }

    @Test
    fun `getCityDistance returns Int_MAX_VALUE for unreachable cities`() {
        assertEquals(Int.MAX_VALUE, service.getCityDistance(mockMap, 1, 8))
    }

    @Test
    fun `searchDistance includes start city with distance 0`() {
        val result = service.searchDistance(mockMap, 1, 0)
        assertEquals(mapOf(1 to 0), result)
    }

    @Test
    fun `searchDistance finds cities within range 1`() {
        val result = service.searchDistance(mockMap, 1, 1)
        assertEquals(
            mapOf(
                1 to 0,
                2 to 1,
                3 to 1,
            ),
            result,
        )
    }

    @Test
    fun `searchDistance finds cities within range 2`() {
        val result = service.searchDistance(mockMap, 1, 2)
        assertEquals(
            mapOf(
                1 to 0,
                2 to 1,
                3 to 1,
                4 to 2,
                5 to 2,
            ),
            result,
        )
    }

    @Test
    fun `searchDistance does not include unreachable cities`() {
        val result = service.searchDistance(mockMap, 1, 10)
        assertFalse(result.containsKey(8))
    }
}

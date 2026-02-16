package com.opensam.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.opensam.entity.General
import com.opensam.repository.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.lang.reflect.Method

/**
 * Tests for ScenarioService's general tuple parsing (5-stat format).
 */
class ScenarioServiceTest {

    private lateinit var service: ScenarioService
    private lateinit var parseGeneral: Method

    @BeforeEach
    fun setUp() {
        service = ScenarioService(
            objectMapper = mock(ObjectMapper::class.java),
            worldStateRepository = mock(WorldStateRepository::class.java),
            nationRepository = mock(NationRepository::class.java),
            cityRepository = mock(CityRepository::class.java),
            generalRepository = mock(GeneralRepository::class.java),
            diplomacyRepository = mock(DiplomacyRepository::class.java),
        )

        parseGeneral = ScenarioService::class.java.getDeclaredMethod(
            "parseGeneral",
            List::class.java, Long::class.java, Map::class.java, Int::class.java
        )
        parseGeneral.isAccessible = true
    }

    private fun callParseGeneral(row: List<Any?>, nationNameToId: Map<String, Long> = emptyMap(), startYear: Int = 200): General {
        return parseGeneral.invoke(service, row, 1L, nationNameToId, startYear) as General
    }

    @Test
    fun `parseGeneral reads 5-stat tuple correctly`() {
        // [affinity, name, picture, nation, city, lead, str, int, pol, charm, officer, birth, death]
        val row: List<Any?> = listOf(25, "조조", "1010", null, null, 98, 72, 91, 94, 96, 0, 155, 220)
        val general = callParseGeneral(row)
        assertEquals("조조", general.name)
        assertEquals(98.toShort(), general.leadership)
        assertEquals(72.toShort(), general.strength)
        assertEquals(91.toShort(), general.intel)
        assertEquals(94.toShort(), general.politics)
        assertEquals(96.toShort(), general.charm)
        assertEquals(0.toShort(), general.officerLevel)
        assertEquals(155.toShort(), general.bornYear)
        assertEquals(220.toShort(), general.deadYear)
    }

    @Test
    fun `parseGeneral reads optional personality and special`() {
        val row: List<Any?> = listOf(76, "관우", "1020", null, null, 96, 97, 75, 64, 94, 0, 162, 219, "의리", "신산")
        val general = callParseGeneral(row)
        assertEquals("관우", general.name)
        assertEquals(96.toShort(), general.leadership)
        assertEquals(64.toShort(), general.politics)
        assertEquals(94.toShort(), general.charm)
        assertEquals("의리", general.personalCode)
        assertEquals("신산", general.specialCode)
    }

    @Test
    fun `parseGeneral defaults personality and special to None`() {
        val row: List<Any?> = listOf(0, "테스트", "9999", null, null, 50, 50, 50, 50, 50, 0, 180, 240)
        val general = callParseGeneral(row)
        assertEquals("None", general.personalCode)
        assertEquals("None", general.specialCode)
    }

    @Test
    fun `parseGeneral resolves nation by name`() {
        val nationMap = mapOf("후한" to 42L)
        val row: List<Any?> = listOf(1, "헌제", "1002", "후한", null, 17, 13, 61, 53, 46, 0, 170, 250)
        val general = callParseGeneral(row, nationNameToId = nationMap)
        assertEquals(42L, general.nationId)
        assertEquals(0.toShort(), general.npcState) // belongs to nation
    }

    @Test
    fun `parseGeneral sets npcState for free NPC`() {
        val row: List<Any?> = listOf(50, "방랑자", "9000", null, null, 50, 50, 50, 50, 50, 0, 180, 240)
        val general = callParseGeneral(row)
        assertEquals(0L, general.nationId)
        assertEquals(1.toShort(), general.npcState) // free NPC
    }

    @Test
    fun `parseGeneral sets permanent wanderer for affinity 999`() {
        val row: List<Any?> = listOf(999, "은둔자", "9001", null, null, 70, 10, 95, 80, 85, 0, 170, 234)
        val general = callParseGeneral(row)
        assertEquals(5.toShort(), general.npcState) // permanent wanderer
    }

    @Test
    fun `parseGeneral calculates age from startYear`() {
        val row: List<Any?> = listOf(0, "장수", "1000", null, null, 50, 50, 50, 50, 50, 0, 180, 250)
        val general = callParseGeneral(row, startYear = 200)
        assertEquals(20.toShort(), general.age) // 200 - 180 = 20
    }

    @Test
    fun `parseGeneral clamps age to minimum 20`() {
        val row: List<Any?> = listOf(0, "어린이", "1000", null, null, 50, 50, 50, 50, 50, 0, 195, 260)
        val general = callParseGeneral(row, startYear = 200)
        assertEquals(20.toShort(), general.age) // 200 - 195 = 5, clamped to 20
    }
}

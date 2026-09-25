package opensamguk.logic.season

import opensamguk.common.world.WorldId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeasonalEventsTest {
    private val definitions = File("../data/curated/han/seasonal-events.json").readText()
    private val values = File("../data/curated/han/world-event-values.json").readText()
    private val calendar = SeasonalCatalog.parseCalendar(values)

    @Test
    fun `36 turn calendar has stable season boundaries`() {
        assertEquals(Season.WINTER, calendar.seasonAt(0))
        assertEquals(Season.SPRING, calendar.seasonAt(6))
        assertEquals(Season.SUMMER, calendar.seasonAt(15))
        assertEquals(Season.AUTUMN, calendar.seasonAt(24))
        assertEquals(Season.WINTER, calendar.seasonAt(33))
        assertEquals(Season.WINTER, calendar.seasonAt(36))
        assertFalse(calendar.passageOpen(Availability.SEASONAL, Season.WINTER, emptySet()))
        assertTrue(calendar.passageOpen(Availability.SEASONAL, Season.WINTER, setOf(Season.WINTER)))
        assertFalse(calendar.passageOpen(Availability.CLOSED, Season.WINTER, setOf(Season.WINTER)))
    }

    @Test
    fun `catalog requires confirmed numeric decisions and all event definitions`() {
        assertEquals(SeasonalEventKind.entries.size, SeasonalCatalog.parseRules(definitions, values).size)
        assertFailsWith<IllegalArgumentException> {
            SeasonalCatalog.parseRules(definitions, values.replaceFirst("\"CONFIRMED\"", "\"DRAFT\""))
        }
        assertFailsWith<IllegalArgumentException> {
            SeasonalCatalog.parseRules(definitions.replaceFirst("\"GAME_TERM\"", "\"PRIMARY\""), values)
        }
    }

    @Test
    fun `seasonal decisions are replayable and filtered by season terrain and population`() {
        val rules = SeasonalCatalog.parseRules(definitions, values)
        val counties = listOf(
            CountySeasonState(2, Terrain.RIVER, 1000, 50),
            CountySeasonState(1, Terrain.PLAIN, 1000, 50),
            CountySeasonState(3, Terrain.PLAIN, 0, 50),
            CountySeasonState(4, Terrain.PLAIN, 1000, 0),
        )
        val certain = rules.map { it.copy(chancePermille = 1000) }
        val first = SeasonalEvents.decide("seed", WorldId(1), 189, 7, 1, calendar, counties, certain)
        val second = SeasonalEvents.decide("seed", WorldId(1), 189, 7, 1, calendar, counties.reversed(), certain.reversed())
        assertEquals(first, second)
        assertTrue(first.none { it.countyId == 3 })
        assertTrue(first.none { it.countyId == 4 && it.kind in setOf(SeasonalEventKind.DROUGHT, SeasonalEventKind.LOCUST) })
        assertTrue(first.any { it.kind == SeasonalEventKind.FLOOD && it.countyId == 2 })
        assertTrue(first.none { it.kind == SeasonalEventKind.FREEZE })
        assertEquals(emptyList(), SeasonalEvents.decide("seed", WorldId(1), 189, 7, 1, calendar, counties,
            rules.map { it.copy(chancePermille = 0) }))
    }
}

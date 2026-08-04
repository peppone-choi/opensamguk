package opensamguk.gameapi.rank

import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.HallReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.StatisticReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RankReadServiceTest {

    private val generals = mock(GeneralReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val hall = mock(HallReadRepository::class.java)
    private val worldStates = mock(WorldStateReadRepository::class.java)
    private val statistics = mock(StatisticReadRepository::class.java)

    private fun service() = RankReadService(generals, nations, cities, hall, worldStates, statistics)

    private fun stubUnifiedWinner() {
        `when`(worldStates.findProcessWorld()).thenReturn(
            WorldStateReadEntity(
                id = 9,
                currentYear = 200,
                currentMonth = 12,
                currentPhase = 2,
                isunited = 2,
                updatedAt = Instant.parse("2026-01-03T00:00:00Z"),
            ),
        )
        `when`(nations.findAll()).thenReturn(
            listOf(NationReadEntity(id = 1, name = "촉", color = "#2e7d32", level = 7, gold = 9000, rice = 8000)),
        )
        `when`(cities.countByNationId(1)).thenReturn(2)
        `when`(cities.count()).thenReturn(2)
        `when`(generals.countByNationId(1)).thenReturn(2)
        `when`(statistics.findFirstByOrderByIdDesc()).thenReturn(null)
    }

    @Test
    fun `emperor reads the unified process world`() {
        stubUnifiedWinner()

        assertEquals(listOf("촉"), service().emperor().map { it.name })
    }

    @Test
    fun `emperor detail maps the validated winner rows by stable id`() {
        stubUnifiedWinner()
        `when`(generals.findAll()).thenReturn(
            listOf(
                GeneralReadEntity(id = 9, name = "장비", nationId = 1, leadership = 90, strength = 96, intel = 45),
                GeneralReadEntity(id = 2, name = "관우", nationId = 1, leadership = 95, strength = 97, intel = 70),
            ),
        )
        `when`(cities.findAll()).thenReturn(
            listOf(
                CityReadEntity(id = 9, name = "성도", nationId = 1, level = 6, population = 250000),
                CityReadEntity(id = 2, name = "한중", nationId = 1, level = 4, population = 180000),
            ),
        )

        val detail = assertNotNull(service().emperorDetail(1))

        assertEquals(9000, detail.totalGold)
        assertEquals(8000, detail.totalRice)
        assertEquals(430000L, detail.totalPop)
        assertEquals(listOf("관우", "장비"), detail.generals.map { it.name })
        assertEquals(listOf("한중", "성도"), detail.cities.map { it.name })
    }

    @Test
    fun `emperor detail returns null for an id absent from the live list`() {
        stubUnifiedWinner()

        assertNull(service().emperorDetail(2))
    }

    @Test
    fun `emperor detail returns null for a non-unified process world`() {
        `when`(worldStates.findProcessWorld()).thenReturn(
            WorldStateReadEntity(id = 9, currentYear = 200, currentMonth = 12, isunited = 0),
        )

        assertEquals(emptyList(), service().emperor())
        assertNull(service().emperorDetail(1))
    }

    @Test
    fun `kingdom roster derives ambassador and auditor roles through SecretPermission`() {
        `when`(nations.findAll()).thenReturn(
            listOf(NationReadEntity(id = 1, name = "촉", color = "#2e7d32", power = 100)),
        )
        `when`(cities.findAll()).thenReturn(emptyList())
        `when`(generals.findAll()).thenReturn(
            listOf(
                GeneralReadEntity(id = 1, name = "군주", nationId = 1, officerLevel = 12, dedication = 100),
                GeneralReadEntity(
                    id = 2,
                    name = "외교관",
                    nationId = 1,
                    officerLevel = 1,
                    dedication = 90,
                    meta = linkedMapOf("permission" to "ambassador"),
                ),
                GeneralReadEntity(
                    id = 3,
                    name = "조언자",
                    nationId = 1,
                    officerLevel = 1,
                    dedication = 80,
                    meta = linkedMapOf("permission" to "auditor"),
                ),
                GeneralReadEntity(
                    id = 4,
                    name = "금지된외교관",
                    nationId = 1,
                    officerLevel = 1,
                    dedication = 70,
                    meta = linkedMapOf("permission" to "ambassador"),
                    penalty = linkedMapOf("noAmbassador" to true),
                ),
                GeneralReadEntity(
                    id = 5,
                    name = "일반수뇌",
                    nationId = 1,
                    officerLevel = 5,
                    dedication = 60,
                ),
            ),
        )

        val roster = service().kingdomRoster().nations.single()

        assertEquals(listOf("군주", "외교관"), roster.ambassadors)
        assertEquals(1, roster.auditorCount)
    }
}

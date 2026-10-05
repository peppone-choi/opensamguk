package opensamguk.gameapi.court.imperial

import opensamguk.gameapi.read.*
import opensamguk.logic.imperial.*
import org.mockito.Mockito.*
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImperialCourtReaderTest {
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val artifacts = mock(ActiveWorldArtifactResolver::class.java)
    private val reader = ImperialCourtReader(worlds, generals, nations, cities, artifacts)

    @Test
    fun `D123 active details are public and vacant ended lines omit every hidden detail`() {
        val active = ImperialHouse("active_line", "현재 황통", ImperialLineStatus.ACTIVE,
            1009, null, emptyList(), 1001, 5, null, 70)
        val hidden = active.copy(code = "vacant_line", status = ImperialLineStatus.VACANT,
            holderGeneralId = null, regentGeneralId = 201, courtNationId = 8, courtCityId = 12)
        seed(listOf(hidden.copy(code = "ended_line", status = ImperialLineStatus.ENDED), hidden, active))
        `when`(generals.findById(1009)).thenReturn(Optional.of(GeneralReadEntity(id = 1009, worldId = 1, name = "황제")))
        `when`(generals.findById(1001)).thenReturn(Optional.of(GeneralReadEntity(id = 1001, worldId = 1, name = "섭정")))
        `when`(nations.findById(5)).thenReturn(Optional.of(NationReadEntity(id = 5, worldId = 1, name = "지키는 세력")))
        val result = reader.read(1)
        assertEquals(ImperialCourtStatus.READY, result.status)
        assertEquals(listOf("active_line", "ended_line", "vacant_line"), result.lines.map { it.code })
        assertEquals("섭정", result.lines.first().regentName)
        assertEquals("지키는 세력", result.lines.first().courtNationName)
        for (line in result.lines.drop(1)) {
            assertNull(line.holderGeneralId)
            assertNull(line.emperorName)
            assertNull(line.courtCityId)
            assertNull(line.courtCityName)
            assertNull(line.regentGeneralId)
            assertNull(line.regentName)
            assertNull(line.courtNationId)
            assertNull(line.courtNationName)
            assertEquals(ImperialCourtFieldState.NOT_APPLICABLE, line.fieldStates.holder)
            assertEquals(ImperialCourtFieldState.NOT_APPLICABLE, line.fieldStates.courtCity)
            assertEquals(ImperialCourtFieldState.NOT_APPLICABLE, line.fieldStates.regent)
            assertEquals(ImperialCourtFieldState.NOT_APPLICABLE, line.fieldStates.courtNation)
        }
        verify(generals, never()).findById(201)
        verify(nations, never()).findById(8)
        verifyNoInteractions(cities, artifacts)
    }

    private fun seed(houses: List<ImperialHouse>) {
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1, currentYear = 190,
            currentMonth = 1, meta = mapOf(ImperialWorldCodec.META_KEY to ImperialWorldCodec.write(
                ImperialWorldState(houses, emptyList(), emptyList())))))
    }
}

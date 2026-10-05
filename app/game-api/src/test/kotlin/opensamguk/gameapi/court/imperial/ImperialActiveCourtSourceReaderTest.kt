package opensamguk.gameapi.court.imperial

import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.ImperialPresenceBadgeResponse
import opensamguk.gameapi.read.ImperialPresenceReader
import opensamguk.gameapi.read.ImperialPresenceResponse
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.input.Phase
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional
import kotlin.test.assertEquals

class ImperialActiveCourtSourceReaderTest {
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val presence = mock(ImperialPresenceReader::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val reader = ImperialActiveCourtSourceReader(worlds, presence, cities)
    private val badge = ImperialPresenceBadgeResponse("han", "한", 101, "WATER_ZONE", "water-1", null, 11, "황제")

    @Test
    fun `active source combines actual game phase and current court name with validated presence`() {
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(
            id = 1, currentYear = 190, currentMonth = 2, currentPhase = 3))
        `when`(presence.read()).thenReturn(ImperialPresenceResponse("READY", listOf(badge)))
        `when`(cities.findById(11)).thenReturn(Optional.of(CityReadEntity(id = 11, worldId = 1, name = "현재 조정")))
        val source = reader.read()
        assertEquals(ImperialActiveCourtSourceStatus.READY, source.status)
        assertEquals(ImperialActiveCourtContext(1, Phase(190, 2, 3)), source.context)
        assertEquals(listOf(ImperialActiveCourtLine(badge, "현재 조정")), source.lines)
    }
}

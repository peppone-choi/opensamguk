package opensamguk.gameapi.court.imperial

import opensamguk.gameapi.read.*
import opensamguk.logic.imperial.*
import org.mockito.Mockito.*
import java.util.Optional
import java.nio.file.Path
import opensamguk.infra.seed.WorldArtifactsResolver
import opensamguk.infra.seed.WorldMapVariant
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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


    @Test
    fun `missing key and valid empty seed are distinct without public detail queries`() {
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1))
        assertEquals(ImperialCourtStatus.NOT_SEEDED, reader.read(1).status)
        seed(emptyList())
        val ready = reader.read(1)
        assertEquals(ImperialCourtStatus.READY, ready.status)
        assertEquals(emptyList(), ready.lines)
        verifyNoInteractions(generals, nations, cities, artifacts)
    }

    @Test
    fun `missing malformed or wrong world source never exposes a partial court`() {
        assertEquals(ImperialCourtStatus.STATE_UNAVAILABLE, reader.read(1).status)
        for (meta in listOf(mapOf("imperialWorld" to null), mapOf("imperialWorld" to mapOf("schemaVersion" to 2)))) {
            `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1, meta = meta))
            assertEquals(ImperialCourtStatus.STATE_UNAVAILABLE, reader.read(1).status)
            assertEquals(emptyList(), reader.read(1).lines)
        }
        seed(emptyList())
        assertEquals(ImperialCourtStatus.STATE_UNAVAILABLE, reader.read(2).status)
        verifyNoInteractions(generals, nations, cities, artifacts)
    }

    @Test
    fun `active holder regent and nation references require exact same world rows`() {
        val house = active()
        for (bad in listOf(null, GeneralReadEntity(id = 1009, worldId = 2), GeneralReadEntity(id = 1010, worldId = 1))) {
            seed(listOf(house))
            `when`(generals.findById(1009)).thenReturn(Optional.ofNullable(bad))
            assertEquals(ImperialCourtStatus.STATE_UNAVAILABLE, reader.read(1).status)
        }
        `when`(generals.findById(1009)).thenReturn(Optional.of(GeneralReadEntity(id = 1009, worldId = 1, name = "황제")))
        seed(listOf(house.copy(regentGeneralId = 1001)))
        `when`(generals.findById(1001)).thenReturn(Optional.of(GeneralReadEntity(id = 1001, worldId = 2, name = "섭정")))
        assertEquals(ImperialCourtStatus.STATE_UNAVAILABLE, reader.read(1).status)
        seed(listOf(house.copy(courtNationId = 5)))
        `when`(nations.findById(5)).thenReturn(Optional.of(NationReadEntity(id = 5, worldId = 2, name = "다른 세계")))
        assertEquals(ImperialCourtStatus.STATE_UNAVAILABLE, reader.read(1).status)
    }

    @Test
    fun `blank names use unavailable fields while explicit unassigned details stay ready null`() {
        seed(listOf(active()))
        `when`(generals.findById(1009)).thenReturn(Optional.of(GeneralReadEntity(id = 1009, worldId = 1, name = " ")))
        val result = reader.read(1)
        assertEquals(ImperialCourtStatus.READY, result.status)
        val line = result.lines.single()
        assertNull(line.emperorName)
        assertEquals(1009, line.holderGeneralId)
        assertEquals(ImperialCourtFieldState.UNAVAILABLE, line.fieldStates.holder)
        assertEquals(ImperialCourtFieldState.READY, line.fieldStates.regent)
        assertEquals(ImperialCourtFieldState.READY, line.fieldStates.courtNation)
        assertEquals(ImperialCourtFieldState.READY, line.fieldStates.courtCity)
        verifyNoInteractions(nations, cities, artifacts)
    }

    @Test
    fun `court city is checked against both same world row and actual pinned artifact`() {
        val world = seed(listOf(active().copy(courtCityId = 11)))
        val bundle = WorldArtifactsResolver(Path.of("../..")).artifacts(WorldMapVariant.V3_1133)
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, emptyList(), bundle))
        `when`(generals.findById(1009)).thenReturn(Optional.of(GeneralReadEntity(id = 1009, worldId = 1, name = "황제")))
        val city = CityReadEntity(id = 11, worldId = 1, name = "현재 조정")
        `when`(cities.findById(11)).thenReturn(Optional.of(city))
        assertEquals("현재 조정", reader.read(1).lines.single().courtCityName)
        city.worldId = 2
        assertEquals(ImperialCourtStatus.STATE_UNAVAILABLE, reader.read(1).status)
        city.worldId = 1
        city.id = 12
        assertEquals(ImperialCourtStatus.STATE_UNAVAILABLE, reader.read(1).status)
        seed(listOf(active().copy(courtCityId = Int.MAX_VALUE)))
        assertEquals(ImperialCourtStatus.STATE_UNAVAILABLE, reader.read(1).status)
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(WorldStateReadEntity(id = 2), emptyList(), bundle))
        seed(listOf(active().copy(courtCityId = 11)))
        assertEquals(ImperialCourtStatus.STATE_UNAVAILABLE, reader.read(1).status)
    }

    @Test
    fun `public wire retains explicit nullable keys and contains no hidden fields or entity aliases`() {
        val active = active()
        seed(listOf(active.copy(status = ImperialLineStatus.ENDED, holderGeneralId = null,
            regentGeneralId = 201, courtNationId = 8, courtCityId = 12)))
        val result = reader.read(1)
        val mapper = ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
        val json = mapper.readTree(mapper.writeValueAsString(result))
        val line = json["lines"][0]
        for (key in listOf("holderGeneralId", "emperorName", "courtCityId", "courtCityName", "regentGeneralId",
                "regentName", "courtNationId", "courtNationName")) {
            assertEquals(true, line.has(key), key)
            assertEquals(true, line[key].isNull, key)
        }
        assertEquals(setOf("holder", "courtCity", "regent", "courtNation"), line["fieldStates"].fieldNames().asSequence().toSet())
        for (key in listOf("legitimacy", "allegiances", "designatedHeirGeneralId", "dynasticCandidateIds",
                "meta", "imperialEdicts", "emperorNodeId", "snapshot", "sourceRevision")) {
            assertFalse(mapper.writeValueAsString(result).contains(key), key)
        }
        assertFailsWith<UnsupportedOperationException> { (result.lines as MutableList<*>).clear() }
        verifyNoInteractions(generals, nations, cities, artifacts)
    }

    @Test
    fun `world conflict becomes unavailable and unrelated service failures propagate`() {
        doThrow(ResponseStatusException(HttpStatus.CONFLICT)).`when`(worlds).findProcessWorld()
        assertEquals(ImperialCourtStatus.STATE_UNAVAILABLE, reader.read(1).status)
        doThrow(ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).`when`(worlds).findProcessWorld()
        assertFailsWith<ResponseStatusException> { reader.read(1) }
    }

    private fun active() = ImperialHouse("active_line", "현재 황통", ImperialLineStatus.ACTIVE,
        1009, null, emptyList(), null, null, null, 70)

    private fun seed(houses: List<ImperialHouse>): WorldStateReadEntity {
        val world = WorldStateReadEntity(id = 1, currentYear = 190, currentMonth = 1,
            meta = mapOf(ImperialWorldCodec.META_KEY to ImperialWorldCodec.write(
                ImperialWorldState(houses, emptyList(), emptyList()))))
        `when`(worlds.findProcessWorld()).thenReturn(world)
        return world
    }
}

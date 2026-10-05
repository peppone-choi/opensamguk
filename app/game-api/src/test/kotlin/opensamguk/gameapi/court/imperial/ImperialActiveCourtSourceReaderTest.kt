package opensamguk.gameapi.court.imperial

import opensamguk.gameapi.read.ActiveWorldArtifactResolver
import opensamguk.gameapi.read.ActiveWorldArtifactSnapshot
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.SpatialStateReadRepository
import opensamguk.gameapi.read.SpatialStateReadSnapshot
import opensamguk.infra.seed.WorldArtifactsResolver
import opensamguk.logic.imperial.ImperialHouse
import opensamguk.logic.imperial.ImperialLineStatus
import opensamguk.logic.imperial.ImperialWorldCodec
import opensamguk.logic.imperial.ImperialWorldState
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.ProvinceControlSnapshot
import opensamguk.logic.world.StrategicNodeRef
import opensamguk.logic.world.WorldMapVariant
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
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.times
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

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

    private fun ready(badges: List<ImperialPresenceBadgeResponse> = listOf(badge)) {
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(
            id = 1, currentYear = 190, currentMonth = 2, currentPhase = 3))
        `when`(presence.read()).thenReturn(ImperialPresenceResponse("READY", badges))
        `when`(cities.findById(11)).thenReturn(Optional.of(CityReadEntity(id = 11, worldId = 1, name = "조정")))
    }

    private fun assertUnavailable() {
        val source = reader.read()
        assertEquals(ImperialActiveCourtSourceStatus.UNAVAILABLE, source.status)
        assertNull(source.context)
        assertNull(source.lines)
    }

    @Test
    fun `missing world cannot invoke presence or city reads`() {
        assertUnavailable()
        verifyNoInteractions(presence, cities)
    }

    @Test
    fun `invalid world or clock never fabricates a game phase`() {
        for (world in listOf(
            WorldStateReadEntity(id = 0, currentYear = 190, currentMonth = 2),
            WorldStateReadEntity(id = 1, currentYear = 0, currentMonth = 2),
            WorldStateReadEntity(id = 1, currentYear = 190, currentMonth = 0),
            WorldStateReadEntity(id = 1, currentYear = 190, currentMonth = 13),
            WorldStateReadEntity(id = 1, currentYear = 190, currentMonth = 2, currentPhase = 0),
            WorldStateReadEntity(id = 1, currentYear = 190, currentMonth = 2, currentPhase = 4),
        )) {
            `when`(worlds.findProcessWorld()).thenReturn(world)
            assertUnavailable()
        }
        verifyNoInteractions(presence, cities)
    }

    @Test
    fun `world format conflict is unavailable but service failures propagate`() {
        doThrow(ResponseStatusException(HttpStatus.CONFLICT)).`when`(worlds).findProcessWorld()
        assertUnavailable()
        doThrow(ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).`when`(worlds).findProcessWorld()
        val cause = assertFailsWith<ResponseStatusException> { reader.read() }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, cause.statusCode)
        verifyNoInteractions(presence, cities)
    }

    @Test
    fun `not seeded retains actual context without a false empty ready collection`() {
        ready()
        `when`(presence.read()).thenReturn(ImperialPresenceResponse("NOT_SEEDED", emptyList()))
        val source = reader.read()
        assertEquals(ImperialActiveCourtSourceStatus.NOT_SEEDED, source.status)
        assertEquals(ImperialActiveCourtContext(1, Phase(190, 2, 3)), source.context)
        assertNull(source.lines)
        verifyNoInteractions(cities)
    }

    @Test
    fun `not seeded cannot carry contradictory badges`() {
        ready()
        `when`(presence.read()).thenReturn(ImperialPresenceResponse("NOT_SEEDED", listOf(badge)))
        assertUnavailable()
        verifyNoInteractions(cities)
    }

    @Test
    fun `unavailable and unknown presence never become partial or ready data`() {
        ready()
        for (status in listOf("STATE_UNAVAILABLE", "UNKNOWN")) {
            `when`(presence.read()).thenReturn(ImperialPresenceResponse(status, listOf(badge)))
            assertUnavailable()
        }
        verifyNoInteractions(cities)
    }

    @Test
    fun `valid empty active source stays limited to active records`() {
        ready(emptyList())
        val source = reader.read()
        assertEquals(ImperialActiveCourtSourceStatus.READY, source.status)
        assertEquals(emptyList(), source.lines)
        verifyNoInteractions(cities)
    }

    @Test
    fun `unspecified court remains null without using emperor reference city`() {
        ready(listOf(badge.copy(emperorCityId = 12, courtCityId = null)))
        val line = reader.read().lines!!.single()
        assertEquals(12, line.presence.emperorCityId)
        assertNull(line.presence.courtCityId)
        assertNull(line.courtCityName)
        verifyNoInteractions(cities)
    }

    @Test
    fun `blank current court name stays null without changing its actual ID`() {
        ready()
        `when`(cities.findById(11)).thenReturn(Optional.of(CityReadEntity(id = 11, worldId = 1, name = " \t")))
        val source = reader.read()
        assertEquals(ImperialActiveCourtSourceStatus.READY, source.status)
        val line = source.lines!!.single()
        assertEquals(11, line.presence.courtCityId)
        assertNull(line.courtCityName)
    }

    @Test
    fun `missing mismatched and foreign world court rows fail the entire source`() {
        ready()
        for (city in listOf(null, CityReadEntity(id = 12, worldId = 1), CityReadEntity(id = 11, worldId = 2))) {
            `when`(cities.findById(11)).thenReturn(Optional.ofNullable(city))
            assertUnavailable()
        }
    }

    @Test
    fun `shared court name is read once and source does not retain a mutable entity or badge list`() {
        val input = mutableListOf(badge, badge.copy(lineCode = "second", emperorGeneralId = 102))
        ready(input)
        val city = CityReadEntity(id = 11, worldId = 1, name = "현재 이름")
        `when`(cities.findById(11)).thenReturn(Optional.of(city))
        val source = reader.read()
        input.clear()
        city.name = "변경된 이름"
        val lines = source.lines!!
        assertEquals(listOf("현재 이름", "현재 이름"), lines.map { it.courtCityName })
        assertFailsWith<UnsupportedOperationException> { (lines as MutableList<*>).clear() }
        verify(cities, times(1)).findById(11)
        verify(presence, times(1)).read()
    }

    @Test
    fun `real presence composition includes only active lines and current names`() {
        val bundle = WorldArtifactsResolver(Path.of("../..")).artifacts(WorldMapVariant.V3_1133)
        val topology = bundle.projection.topology
        val home = assertNotNull(bundle.projection.bindingsByCityId.getValue(12).landProvinceId)
        val generals = mock(GeneralReadRepository::class.java)
        val artifacts = mock(ActiveWorldArtifactResolver::class.java)
        val spatial = mock(SpatialStateReadRepository::class.java)
        val active = ImperialHouse("han", "한", ImperialLineStatus.ACTIVE,
            101, null, emptyList(), 999, 888, 11, 50)
        val state = ImperialWorldState(listOf(active,
            active.copy(code = "vacant", status = ImperialLineStatus.VACANT, holderGeneralId = null, courtCityId = 999),
            active.copy(code = "ended", status = ImperialLineStatus.ENDED, holderGeneralId = null, courtCityId = 998)),
            emptyList(), emptyList())
        val world = WorldStateReadEntity(id = 1, currentYear = 190, currentMonth = 2, currentPhase = 3,
            meta = mapOf(ImperialWorldCodec.META_KEY to ImperialWorldCodec.write(state)))
        `when`(worlds.findProcessWorld()).thenReturn(world)
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, emptyList(), bundle))
        `when`(generals.findById(101)).thenReturn(Optional.of(
            GeneralReadEntity(id = 101, worldId = 1, name = "현재 황제", cityId = 12)))
        `when`(cities.findById(11)).thenReturn(Optional.of(CityReadEntity(id = 11, worldId = 1, name = "현재 조정")))
        `when`(spatial.readSnapshot(1, topology)).thenReturn(SpatialStateReadSnapshot(
            ProvinceControlSnapshot.fromTopology(topology),
            GeneralPositionSnapshot.fromTopology(topology, listOf(GeneralPositionState(
                topology.topologyRevision, topology.contentHash, 101, StrategicNodeRef.LandProvince(home), 1)))))
        val actualReader = ImperialActiveCourtSourceReader(worlds,
            ImperialPresenceReader(worlds, generals, cities, artifacts, spatial), cities)
        val source = actualReader.read()
        assertEquals(ImperialActiveCourtSourceStatus.READY, source.status)
        val line = source.lines!!.single()
        assertEquals("han", line.presence.lineCode)
        assertEquals("현재 황제", line.presence.emperorName)
        assertEquals(home, line.presence.emperorNodeId)
        assertEquals("현재 조정", line.courtCityName)
        verify(generals, times(1)).findById(101)
        verify(cities, times(0)).findById(999)
        verify(cities, times(0)).findById(998)
    }
}

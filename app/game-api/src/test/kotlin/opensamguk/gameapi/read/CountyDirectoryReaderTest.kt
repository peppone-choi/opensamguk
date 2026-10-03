package opensamguk.gameapi.read

import kotlin.test.*
import opensamguk.gameapi.dto.*
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.world.*
import org.mockito.Mockito.*
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

class CountyDirectoryReaderTest {
    private val owners = mock(GeneralResolver::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val artifacts = mock(ActiveWorldArtifactResolver::class.java)
    private val geography = mock(CityGeography::class.java)
    private val vision = mock(VisionReader::class.java)
    private val reader = CountyDirectoryReader(owners, generals, artifacts, geography, vision)
    private val stamp = StampDto(190, 2, 1)
    private fun row(id: Int = 3, nation: Int = 10, supplied: Int = 1, population: Int = 1000,
                    commerce: Int = 100, commerceMax: Int = 100, agriculture: Int = 100, agricultureMax: Int = 100,
                    meta: Map<String, Any?> = mapOf(CountyWarehouse.META_KEY to CountyWarehouse(id, 0, Resources()).toMetaValue())) =
        CityReadEntity(id = id, worldId = 7, name = "현$id", nationId = nation, supplyState = supplied,
            population = population, commerce = commerce, commerceMax = commerceMax,
            agriculture = agriculture, agricultureMax = agricultureMax, meta = meta)

    private fun setup(rows: List<CityReadEntity> = listOf(row(), row(id = 5), row(id = 6, nation = 20), row(id = 4)),
                      adminIds: Set<Int> = setOf(3, 5, 6)) {
        `when`(owners.resolveGeneralId(41)).thenReturn(1)
        `when`(generals.findById(1)).thenReturn(Optional.of(GeneralReadEntity(id = 1, worldId = 7, userId = "41", nationId = 10)))
        val bundle = mock(ResolvedWorldArtifacts::class.java)
        val projection = mock(StrategicRouteProjection::class.java)
        `when`(bundle.projection).thenReturn(projection)
        `when`(projection.administrativeCountyIds).thenReturn(adminIds)
        `when`(projection.bindingsByCityId).thenReturn(rows.associate { city -> city.id to StrategicRouteBinding(
            city.id, "route:${city.id}", "place:${city.id}", if (city.id == 5) "p2" else "p1", city.id in adminIds) })
        `when`(bundle.commanderyIndex).thenReturn(CommanderyIndex("0".repeat(64),
            listOf(Commandery(0, "a", "甲군", "甲"), Commandery(1, "b", "乙군", "乙")),
            mapOf("p1" to 0, "p2" to 1), setOf(0 to 1)))
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(WorldStateReadEntity(id = 7,
            currentYear = 190, currentMonth = 2, currentPhase = 1,
            config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN")), rows, bundle))
        `when`(geography.places(bundle)).thenReturn(mapOf(3 to CityGeography.Place(null, null, displayName = "甲군 표시현")))
        setVision("FULL", "FULL")
    }

    private fun setVision(a: String, b: String, seenStamp: StampDto = stamp) {
        `when`(vision.visibility(1, 41)).thenReturn(VisibilityResponse("READY", seenStamp,
            listOf(VisibilityCommanderyDto(0, "a", "甲군", a), VisibilityCommanderyDto(1, "b", "乙군", b))))
    }

    @Test fun `income is canonical monthly gross forecast with integer rounding and live stamp`() {
        setup(listOf(row(population = 1003, commerce = 1, commerceMax = 3, agriculture = 2, agricultureMax = 3)))
        val out = reader.counties(1, 41, "NATION", null)
        assertEquals("READY", out.status); assertEquals("GAME_MONTH", out.period)
        assertEquals("CURRENT_STATE_FORECAST", out.basis); assertEquals(stamp, out.stamp)
        assertEquals(CountyIncomeDto(1333, 26666), out.counties.single().income)
        assertEquals("甲군 표시현", out.counties.single().name)
    }

    @Test fun `nation and commandery scopes exclude another nation and non-administrative places`() {
        setup()
        assertEquals(listOf(3, 5), reader.counties(1, 41, "NATION", null).counties.map { it.cityId })
        val out = reader.counties(1, 41, "COMMANDERY", "a")
        assertEquals(listOf(3), out.counties.map { it.cityId }); assertEquals("a", out.commandery?.id)
        assertEquals(CountyIncomeDto(4000, 40000), out.counties.single().income)
    }

    @Test fun `absence of warehouse is a known zero while corruption is unknown`() {
        setup(listOf(row(meta = emptyMap())))
        val missing = reader.counties(1, 41, "NATION", null)
        assertEquals("READY", missing.status); assertEquals(CountyIncomeDto(0, 0), missing.counties.single().income)
        setup(listOf(row(meta = mapOf(CountyWarehouse.META_KEY to mapOf("stock" to 99)))))
        val damaged = reader.counties(1, 41, "NATION", null)
        assertEquals("PARTIAL", damaged.status); assertNull(damaged.counties.single().income)
    }

    @Test fun `supply loss and zero development maxima follow engine production rules`() {
        setup(listOf(row(supplied = 0)))
        assertEquals(CountyIncomeDto(0, 0), reader.counties(1, 41, "NATION", null).counties.single().income)
        setup(listOf(row(commerceMax = 0)))
        assertEquals(CountyIncomeDto(0, 40000), reader.counties(1, 41, "NATION", null).counties.single().income)
        setup(listOf(row(commerce = 400, agriculture = 400)))
        assertEquals(CountyIncomeDto(4000, 40000), reader.counties(1, 41, "NATION", null).counties.single().income)
    }

    @Test fun `negative inputs and exact arithmetic overflow keep forecast unknown`() {
        setup(listOf(row(population = -1)))
        assertEquals("PARTIAL", reader.counties(1, 41, "NATION", null).status)
        setup(listOf(row(population = Int.MAX_VALUE, commerce = Int.MAX_VALUE, commerceMax = Int.MAX_VALUE)))
        assertNull(reader.counties(1, 41, "NATION", null).counties.single().income)
    }

    @Test fun `intel and fog do not compute or expose current income even with corrupt raw state`() {
        setup(listOf(row(meta = mapOf(CountyWarehouse.META_KEY to "broken")), row(id = 5)))
        setVision("INTEL", "FOG")
        val out = reader.counties(1, 41, "NATION", null)
        assertEquals("READY", out.status); assertEquals(listOf("INTEL", "FOG"), out.counties.map { it.visibility })
        assertTrue(out.counties.all { it.income == null })
    }

    @Test fun `private reads reject past or other identity before loading world and vision`() {
        setup()
        assertFailsWith<CampForbidden> { reader.counties(2, 41, "NATION", null) }
        verifyNoInteractions(generals, artifacts, geography, vision)
        `when`(owners.resolveGeneralId(41)).thenReturn(null)
        assertFailsWith<CampForbidden> { reader.counties(1, 41, "NATION", null) }
        verifyNoInteractions(generals, artifacts, geography, vision)
    }

    @Test fun `unknown vision and world identity fail closed and no nation has no owned list`() {
        setup()
        setVision("FULL", "FULL", StampDto(190, 1, 3))
        assertEquals("UNAVAILABLE", reader.counties(1, 41, "NATION", null).status)
        `when`(artifacts.resolve()).thenReturn(null)
        assertEquals("UNAVAILABLE", reader.counties(1, 41, "NATION", null).status)
        setup(listOf(CityReadEntity(id = 3, worldId = 8, nationId = 10)))
        assertFailsWith<ResponseStatusException> { reader.counties(1, 41, "NATION", null) }
        setup()
        `when`(generals.findById(1)).thenReturn(Optional.of(GeneralReadEntity(id = 1, worldId = 7, userId = "41", nationId = 0)))
        assertEquals("NO_NATION", reader.counties(1, 41, "NATION", null).status)
        setup(emptyList())
        assertTrue(reader.counties(1, 41, "NATION", null).counties.isEmpty())
    }

    @Test fun `unsupported scope and unknown commandery are rejected`() {
        setup()
        assertFailsWith<ResponseStatusException> { reader.counties(1, 41, "ALL", null) }
        assertFailsWith<ResponseStatusException> { reader.counties(1, 41, "COMMANDERY", null) }
        assertFailsWith<ResponseStatusException> { reader.counties(1, 41, "COMMANDERY", "unknown") }
        assertFailsWith<ResponseStatusException> { reader.counties(1, 41, "NATION", "a") }
    }
}

package opensamguk.gameapi.read

import kotlin.test.*
import opensamguk.gameapi.dto.*
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.CityMilitaryState
import opensamguk.logic.world.*
import org.mockito.Mockito.*
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

class CountyDetailReaderTest {
    private val owners = mock(GeneralResolver::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val artifacts = mock(ActiveWorldArtifactResolver::class.java)
    private val geography = mock(CityGeography::class.java)
    private val vision = mock(VisionReader::class.java)
    private val specialties = mock(CampReader::class.java)
    private val reader = CountyDetailReader(owners, generals, nations, artifacts, geography, vision, specialties)
    private val stamp = StampDto(190, 2, 1)
    private fun city(nationId: Int = 10, worldId: Int = 7, supply: Int = 1,
                     meta: Map<String, Any?> = mapOf(
                         CountyWarehouse.META_KEY to CountyWarehouse(3, 0, Resources()).toMetaValue(),
                         CityMilitaryState.META_KEY to CityMilitaryState(72, 61, 240).toMetaValue())) =
        CityReadEntity(id = 3, worldId = worldId, name = "현", nationId = nationId, level = 11,
            population = 1003, populationMax = 2000, defense = 900, defenseMax = 1200,
            commerce = 1, commerceMax = 3, agriculture = 2, agricultureMax = 3,
            security = 75, securityMax = 100, trust = 73.5, wall = 300, wallMax = 900,
            supplyState = supply, meta = meta)

    private fun setup(target: CityReadEntity = city(), tier: String = "FULL", at: StampDto = stamp,
                      administrative: Boolean = true, format: String = "GENERAL_RETAINER_CAMPAIGN") {
        `when`(owners.resolveGeneralId(41)).thenReturn(1)
        `when`(generals.findById(1)).thenReturn(Optional.of(GeneralReadEntity(id = 1, worldId = 7, userId = "41", nationId = 10)))
        val bundle = mock(ResolvedWorldArtifacts::class.java)
        val projection = mock(StrategicRouteProjection::class.java)
        `when`(bundle.projection).thenReturn(projection)
        `when`(projection.administrativeCountyIds).thenReturn(if (administrative) setOf(3) else emptySet())
        `when`(projection.bindingsByCityId).thenReturn(mapOf(3 to StrategicRouteBinding(3, "route:3", "place:3", "p1", administrative)))
        `when`(bundle.commanderyIndex).thenReturn(CommanderyIndex("0".repeat(64),
            listOf(Commandery(0, "a", "甲군", "甲")), mapOf("p1" to 0), emptySet()))
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(WorldStateReadEntity(id = 7,
            currentYear = 190, currentMonth = 2, currentPhase = 1, config = mapOf("worldFormat" to format)), listOf(target), bundle))
        `when`(geography.places(bundle)).thenReturn(mapOf(3 to CityGeography.Place("甲군", "county:3", countyHanja = "甲縣", displayName = "甲군 표시현")))
        `when`(vision.visibility(1, 41)).thenReturn(VisibilityResponse("READY", at,
            listOf(VisibilityCommanderyDto(0, "a", "甲군", tier, ageTurns = if (tier == "INTEL") 2 else null))))
        `when`(nations.findAll()).thenReturn(listOf(NationReadEntity(id = 10, worldId = 7, name = "우리", color = "#102030"),
            NationReadEntity(id = 20, worldId = 7, name = "남", color = "#405060")))
        `when`(specialties.county(3, 1, 41)).thenReturn(CountyResponse("READY", 3, "현",
            listOf(SpecialtyDto("IRON", "철", 33, 45))))
    }

    @Test fun `full owned core uses actual grade military and monthly forecast`() {
        setup()
        val out = requireNotNull(reader.county(3, 1, 41))
        assertEquals("READY", out.status); assertEquals("甲군 표시현", out.name); assertEquals("甲縣", out.nameCh)
        assertEquals(CountyGradeDto(11, "장현"), out.grade)
        assertEquals(CountyDirectoryCommandery("a", "甲군"), out.commandery)
        assertEquals(DirectoryAffiliation(10, "우리", "#102030"), out.owner)
        assertEquals(CountyIntegerIndicatorDto(1003, 2000), out.indicators.population)
        assertEquals(CountyIntegerIndicatorDto(2, 3), out.indicators.agriculture)
        assertEquals(CountyIntegerIndicatorDto(1, 3), out.indicators.commerce)
        assertEquals(CountyIntegerIndicatorDto(75, 100), out.indicators.security)
        assertEquals(CountyDecimalIndicatorDto(73.5, 100.0), out.indicators.trust)
        assertEquals(CountyIntegerIndicatorDto(900, 1200), out.indicators.defence)
        assertEquals(CountyIntegerIndicatorDto(300, 900), out.indicators.wall)
        assertEquals(CountyGarrisonDto(240, 72, 61), out.garrison)
        assertEquals(CountyIncomeDto(1333, 26666), out.income)
        assertEquals(33L, out.specialties?.single()?.monthly); assertNull(out.intelAgeTurns)
        assertEquals("GAME_MONTH", out.period); assertEquals("CURRENT_STATE_FORECAST", out.basis)
        assertEquals(stamp, out.stamp)
        assertNull(out.peopleHere); assertNull(out.front); assertNull(out.seasonalEvent)
        assertEquals("NO_SOURCE", out.unavailableReasons["/peopleHere"])
    }

    @Test fun `intel and fog mask all current private county values and specialty allocation`() {
        for (tier in listOf("INTEL", "FOG")) {
            setup(city(meta = mapOf(CityMilitaryState.META_KEY to "broken", CountyWarehouse.META_KEY to "broken")), tier)
            val out = requireNotNull(reader.county(3, 1, 41))
            assertEquals("READY", out.status); assertEquals(tier, out.visibility)
            assertEquals(CountyIndicatorsDto(), out.indicators); assertNull(out.garrison); assertNull(out.income)
            assertNull(out.specialties?.single()?.monthly); assertEquals(45L, out.specialties?.single()?.ledgerMonthly)
            assertEquals(if (tier == "INTEL") 2 else null, out.intelAgeTurns)
            for (key in listOf("population", "agriculture", "commerce", "security", "trust", "defence", "wall"))
                assertEquals("NOT_AUTHORIZED", out.unavailableReasons["/indicators/$key"])
        }
    }

    @Test fun `full foreign county does not grant another nations income or actual specialty allocation`() {
        setup(city(nationId = 20))
        val out = requireNotNull(reader.county(3, 1, 41))
        assertEquals(1003, out.indicators.population?.value); assertEquals(240, out.garrison?.troops)
        assertNull(out.income); assertNull(out.specialties?.single()?.monthly)
        assertEquals("NOT_AUTHORIZED", out.unavailableReasons["/income"])
    }

    @Test fun `military absence and corruption stay unknown and fortification never becomes troops`() {
        setup(city(meta = emptyMap()))
        var out = requireNotNull(reader.county(3, 1, 41))
        assertEquals("PARTIAL", out.status); assertNull(out.garrison); assertEquals(900, out.indicators.defence?.value)
        assertEquals("NO_SOURCE", out.unavailableReasons["/garrison"])
        assertEquals(CountyIncomeDto(0, 0), out.income)
        setup(city(meta = mapOf(CityMilitaryState.META_KEY to mapOf("troops" to 900))))
        out = requireNotNull(reader.county(3, 1, 41)); assertNull(out.garrison)
    }

    @Test fun `known supply loss is zero while malformed warehouse stays unknown`() {
        setup(city(supply = 0))
        assertEquals(CountyIncomeDto(0, 0), requireNotNull(reader.county(3, 1, 41)).income)
        setup(city(meta = mapOf(CountyWarehouse.META_KEY to "broken", CityMilitaryState.META_KEY to CityMilitaryState(72, 61, 240).toMetaValue())))
        val out = requireNotNull(reader.county(3, 1, 41)); assertEquals("PARTIAL", out.status); assertNull(out.income)
    }

    @Test fun `borrowed selection is forbidden before any world target or visibility read`() {
        setup()
        assertFailsWith<CampForbidden> { reader.county(3, 2, 41) }
        verifyNoInteractions(generals, nations, artifacts, geography, vision, specialties)
    }

    @Test fun `stored ownership is checked even when the resolver points to the selected body`() {
        setup()
        `when`(generals.findById(1)).thenReturn(Optional.of(GeneralReadEntity(id = 1, worldId = 7, userId = "42")))
        assertFailsWith<CampForbidden> { reader.county(3, 1, 41) }
        verifyNoInteractions(nations, artifacts, geography, vision, specialties)
    }

    @Test fun `stale vision and unsupported worlds cannot expose the core`() {
        setup(at = StampDto(190, 1, 3))
        assertEquals(CountyDetailDto("UNAVAILABLE", 3), reader.county(3, 1, 41))
        verifyNoInteractions(nations, geography, specialties)
        setup(format = "UNKNOWN")
        assertEquals("UNSUPPORTED_WORLD_FORMAT", reader.county(3, 1, 41)?.status)
    }

    @Test fun `cross world county and nation rows are rejected`() {
        setup(city(worldId = 8))
        assertFailsWith<ResponseStatusException> { reader.county(3, 1, 41) }
        setup()
        `when`(nations.findAll()).thenReturn(listOf(NationReadEntity(id = 10, worldId = 8)))
        assertFailsWith<ResponseStatusException> { reader.county(3, 1, 41) }
    }

    @Test fun `absent and non administrative targets are not county detail`() {
        setup(administrative = false); assertNull(reader.county(3, 1, 41))
        setup(); assertNull(reader.county(4, 1, 41))
    }

    @Test fun `missing and duplicated visibility rows are unavailable`() {
        setup()
        `when`(vision.visibility(1, 41)).thenReturn(VisibilityResponse("READY", stamp, emptyList()))
        assertEquals("UNAVAILABLE", reader.county(3, 1, 41)?.status)
        `when`(vision.visibility(1, 41)).thenReturn(VisibilityResponse("READY", stamp,
            listOf(VisibilityCommanderyDto(0, "a", "甲군", "FULL"), VisibilityCommanderyDto(0, "a", "甲군", "FULL"))))
        assertEquals("UNAVAILABLE", reader.county(3, 1, 41)?.status)
    }

    @Test fun `intel without a valid report age cannot become current county data`() {
        setup(tier = "INTEL")
        for (age in listOf(null, -1)) {
            `when`(vision.visibility(1, 41)).thenReturn(VisibilityResponse("READY", stamp,
                listOf(VisibilityCommanderyDto(0, "a", "甲군", "INTEL", ageTurns = age))))
            assertEquals("UNAVAILABLE", reader.county(3, 1, 41)?.status)
        }
        verifyNoInteractions(nations, geography, specialties)
    }

    @Test fun `one invalid indicator stays null while independent valid sources remain readable`() {
        setup(city().also { it.agriculture = -1 })
        val out = requireNotNull(reader.county(3, 1, 41))
        assertEquals("PARTIAL", out.status); assertNull(out.indicators.agriculture)
        assertEquals(1003, out.indicators.population?.value); assertEquals(73.5, out.indicators.trust?.value)
        assertEquals("INVALID_SOURCE", out.unavailableReasons["/indicators/agriculture"])
        assertNull(out.income)
    }

    @Test fun `valid zero indicators and zero maxima are observed values rather than unknown`() {
        setup(city().also {
            it.population = 0; it.populationMax = 0; it.agriculture = 0; it.agricultureMax = 0
            it.commerce = 0; it.commerceMax = 0; it.security = 0; it.securityMax = 0
            it.trust = 0.0; it.defense = 0; it.defenseMax = 0; it.wall = 0; it.wallMax = 0
        })
        val out = requireNotNull(reader.county(3, 1, 41))
        assertEquals("READY", out.status)
        assertEquals(CountyIntegerIndicatorDto(0, 0), out.indicators.population)
        assertEquals(CountyDecimalIndicatorDto(0.0, 100.0), out.indicators.trust)
        assertEquals(CountyIncomeDto(0, 0), out.income)
    }

    @Test fun `non finite trust and unknown grade do not become fabricated labels or numeric values`() {
        setup(city().also { it.trust = Double.NaN; it.level = 999 })
        val out = requireNotNull(reader.county(3, 1, 41))
        assertEquals("PARTIAL", out.status); assertNull(out.indicators.trust); assertNull(out.grade)
        assertEquals("INVALID_SOURCE", out.unavailableReasons["/indicators/trust"])
        assertEquals("INVALID_SOURCE", out.unavailableReasons["/grade"])
        assertEquals(1003, out.indicators.population?.value)
    }

    @Test fun `a read preserves inherited over cap values without clamping current state`() {
        setup(city().also { it.populationMax = 100; it.trust = 125.5 })
        val out = requireNotNull(reader.county(3, 1, 41))
        assertEquals("READY", out.status)
        assertEquals(CountyIntegerIndicatorDto(1003, 100), out.indicators.population)
        assertEquals(CountyDecimalIndicatorDto(125.5, 100.0), out.indicators.trust)
        assertNull(out.indicators.trust?.trend)
    }
}

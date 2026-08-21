package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.DiplomacyReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task E2 — no-DB unit test of [CommandPrecheckService]. The four JPA READ repos are stubbed
 * (Mockito), so this exercises the SHARED `:logic` constraint path WITHOUT a database. It proves the
 * precheck maps the shared `evaluateConstraints` outcomes to [PrecheckResult] and that the deny-reason
 * strings are the PHP-faithful constraint strings (no re-implementation in game-api).
 *
 * Fixture: nation 1 (level 7, capital 5) / city 5 (owned, supplied, agri 4000 < 8000) /
 * general 10 in city 5, gold 4000. world_state year 200 / startYear 190 -> develCost 40, so the
 * 농지개간 cost (= develCost on the empty P1 pipeline) is 40 and gold 4000 clears it.
 */
class CommandPrecheckServiceTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    private fun general(gold: Int = 4000, nationId: Int = 1, cityId: Int = 5) = GeneralReadEntity(
        id = 10, nationId = nationId, cityId = cityId,
        leadership = 70, strength = 30, intel = 95, injury = 0,
        experience = 1200, dedication = 900, officerLevel = 5,
        gold = gold, rice = 3000,
        meta = linkedMapOf("explevel" to 4, "intel_exp" to 12, "max_domestic_critical" to 3.5),
    )

    private fun city(nationId: Int = 1, supplyState: Int = 1, agri: Int = 4000, agriMax: Int = 8000) =
        CityReadEntity(
            id = 5, nationId = nationId, level = 5,
            commerce = 3000, commerceMax = 8000, agriculture = agri, agricultureMax = agriMax,
            supplyState = supplyState, frontState = 0, trust = 82.0, population = 50_000,
            meta = linkedMapOf(),
        )

    private fun nation() = NationReadEntity(id = 1, level = 7, capitalCityId = 5)

    private fun worldState() = WorldStateReadEntity(
        id = 1, scenarioCode = "scenario_2", currentYear = 200, currentMonth = 3, tickSeconds = 3600,
        config = linkedMapOf("startYear" to 190, "mapName" to "che"), meta = linkedMapOf(),
    )

    private fun service(
        generalEntity: GeneralReadEntity = general(),
        cityEntity: CityReadEntity = city(),
        nationEntity: NationReadEntity = nation(),
        worldStateEntity: WorldStateReadEntity = worldState(),
    ): CommandPrecheckService {
        val generals = mock(GeneralReadRepository::class.java)
        val cities = mock(CityReadRepository::class.java)
        val nations = mock(NationReadRepository::class.java)
        val diplomacies = mock(DiplomacyReadRepository::class.java)
        val worldStates = mock(WorldStateReadRepository::class.java)
        `when`(generals.findById(10)).thenReturn(Optional.of(generalEntity))
        `when`(cities.findById(5)).thenReturn(Optional.of(cityEntity))
        `when`(nations.findById(1)).thenReturn(Optional.of(nationEntity))
        `when`(diplomacies.findBySrcNationId(1)).thenReturn(emptyList())
        `when`(worldStates.findAll()).thenReturn(listOf(worldStateEntity))
        val factory = PrecheckStateViewFactory(generals, cities, nations, diplomacies, worldStates)
        return CommandPrecheckService(factory, registry)
    }

    @Test
    fun `owned supplied funded city with che_농지개간 is AVAILABLE`() {
        val result = service().precheck(generalId = 10, actionCode = "che_농지개간")
        assertEquals(PrecheckResult.Available, result)
    }

    @Test
    fun `selected recruit crewType reaches the precheck instead of defaulting to footman`() {
        val result = service().precheck(
            generalId = 10,
            actionCode = "che_징병",
            args = linkedMapOf("crewType" to 1104, "amount" to 100),
        )

        val blocked = assertIs<PrecheckResult.Blocked>(result)
        assertEquals("현재 선택할 수 없는 병종입니다.", blocked.reason)
        assertEquals("AvailableRecruitCrewType", blocked.constraintName)
    }

    @Test
    fun `recruit precheck normalizes a numeric amount string before it evaluates capacity`() {
        val result = service().precheck(
            generalId = 10,
            actionCode = "che_징병",
            args = linkedMapOf("crewType" to 1100, "amount" to "999999"),
        )

        val blocked = assertIs<PrecheckResult.Blocked>(result)
        assertEquals("주민이 부족합니다.", blocked.reason)
        assertEquals("ReqCityCapacity", blocked.constraintName)
    }

    @Test
    fun `recruit precheck rejects the same malformed args as full execution`() {
        for (args in listOf(
            linkedMapOf<String, Any?>("crewType" to "1100", "amount" to 100),
            linkedMapOf<String, Any?>("crewType" to 1100, "amount" to -1),
        )) {
            val result = service().precheck(generalId = 10, actionCode = "che_징병", args = args)
            val blocked = assertIs<PrecheckResult.Blocked>(result)
            assertEquals("인자가 올바르지 않습니다.", blocked.reason)
            assertEquals(null, blocked.constraintName)
        }
    }

    @Test
    fun `recruit availability exposes typed restrictions and fails closed for an unsupported set`() {
        val availability = service().recruitAvailability(10)!!
        assertTrue(availability.supported)
        assertTrue(availability.crewTypes.single { it.crewType == 1100 }.available)
        val elite = availability.crewTypes.single { it.crewType == 1104 }
        assertFalse(elite.available)
        assertEquals("현재 선택할 수 없는 병종입니다.", elite.reason)

        val unsupportedWorld = worldState().apply {
            config = linkedMapOf(
                "startYear" to 190,
                "mapName" to "che",
                "map" to linkedMapOf("unitSet" to "che"),
                "unitSet" to "not-ported",
            )
        }
        val unsupported = service(worldStateEntity = unsupportedWorld).recruitAvailability(10)!!
        assertFalse(unsupported.supported)
        assertTrue(unsupported.crewTypes.isEmpty())

        val blankWorld = worldState().apply {
            config = linkedMapOf("startYear" to 190, "mapName" to "che", "unitSet" to "  ")
        }
        val blank = service(worldStateEntity = blankWorld).recruitAvailability(10)!!
        assertFalse(blank.supported)
        assertTrue(blank.crewTypes.isEmpty())
    }

    @Test
    fun `lowercase or column startyear keeps precheck AVAILABLE`() {
        val lowercaseConfig = worldState().apply {
            config = linkedMapOf("startyear" to 190, "mapName" to "che")
        }
        val columnValue = worldState().apply {
            config = linkedMapOf("mapName" to "che")
            startYear = 190
        }

        assertEquals(
            PrecheckResult.Available,
            service(worldStateEntity = lowercaseConfig).precheck(generalId = 10, actionCode = "che_농지개간"),
        )
        assertEquals(
            PrecheckResult.Available,
            service(worldStateEntity = columnValue).precheck(generalId = 10, actionCode = "che_농지개간"),
        )
    }

    @Test
    fun `non-owned city is BLOCKED with the OccupiedCity reason`() {
        // general's nationId differs from the city's nationId -> OccupiedCity denies.
        val result = service(cityEntity = city(nationId = 2)).precheck(generalId = 10, actionCode = "che_농지개간")
        val blocked = assertIs<PrecheckResult.Blocked>(result)
        assertEquals("아국이 아닙니다.", blocked.reason)
        assertEquals("OccupiedCity", blocked.constraintName)
    }

    @Test
    fun `insufficient gold is BLOCKED with the ReqGeneralGold reason`() {
        val result = service(generalEntity = general(gold = 10)).precheck(generalId = 10, actionCode = "che_농지개간")
        val blocked = assertIs<PrecheckResult.Blocked>(result)
        assertEquals("자금이 모자랍니다.", blocked.reason)
        assertEquals("ReqGeneralGold", blocked.constraintName)
    }

    @Test
    fun `full agriculture is BLOCKED with the RemainCityCapacity reason`() {
        // agri == agriMax -> RemainCityCapacity denies; josa of "농지 개간" + "은" == "은".
        val result = service(cityEntity = city(agri = 8000, agriMax = 8000))
            .precheck(generalId = 10, actionCode = "che_농지개간")
        val blocked = assertIs<PrecheckResult.Blocked>(result)
        assertEquals("농지 개간은 충분합니다.", blocked.reason)
        assertEquals("RemainCityCapacity", blocked.constraintName)
    }

    @Test
    fun `precheck env includes every city owned by the actor nation`() {
        val generals = mock(GeneralReadRepository::class.java)
        val cities = mock(CityReadRepository::class.java)
        val nations = mock(NationReadRepository::class.java)
        val diplomacies = mock(DiplomacyReadRepository::class.java)
        val worldStates = mock(WorldStateReadRepository::class.java)
        val secondCity = city().apply {
            id = 9
            level = 6
        }
        `when`(generals.findById(10)).thenReturn(Optional.of(general()))
        `when`(cities.findById(5)).thenReturn(Optional.of(city()))
        `when`(cities.findByNationIdOrderByIdAsc(1)).thenReturn(listOf(city(), secondCity))
        `when`(nations.findById(1)).thenReturn(Optional.of(nation()))
        `when`(diplomacies.findBySrcNationId(1)).thenReturn(emptyList())
        `when`(worldStates.findAll()).thenReturn(listOf(worldState()))

        val state = PrecheckStateViewFactory(generals, cities, nations, diplomacies, worldStates).build(10)!!

        assertEquals(linkedMapOf(5 to 5, 9 to 6), state.env["ownCities"])
    }
}

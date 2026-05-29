package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
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
import kotlin.test.assertIs

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
            supplyState = supplyState, frontState = 0, trust = 82,
            meta = linkedMapOf(),
        )

    private fun nation() = NationReadEntity(id = 1, level = 7, capitalCityId = 5)

    private fun worldState() = WorldStateReadEntity(
        id = 1, scenarioCode = "scenario_2", currentYear = 200, currentMonth = 3, tickSeconds = 3600,
        config = linkedMapOf("startYear" to 190), meta = linkedMapOf(),
    )

    private fun service(
        generalEntity: GeneralReadEntity = general(),
        cityEntity: CityReadEntity = city(),
        nationEntity: NationReadEntity = nation(),
    ): CommandPrecheckService {
        val generals = mock(GeneralReadRepository::class.java)
        val cities = mock(CityReadRepository::class.java)
        val nations = mock(NationReadRepository::class.java)
        val worldStates = mock(WorldStateReadRepository::class.java)
        `when`(generals.findById(10)).thenReturn(Optional.of(generalEntity))
        `when`(cities.findById(5)).thenReturn(Optional.of(cityEntity))
        `when`(nations.findById(1)).thenReturn(Optional.of(nationEntity))
        `when`(worldStates.findAll()).thenReturn(listOf(worldState()))
        val factory = PrecheckStateViewFactory(generals, cities, nations, worldStates)
        return CommandPrecheckService(factory, registry)
    }

    @Test
    fun `owned supplied funded city with che_농지개간 is AVAILABLE`() {
        val result = service().precheck(generalId = 10, actionCode = "che_농지개간")
        assertEquals(PrecheckResult.Available, result)
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
}

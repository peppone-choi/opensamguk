package opensamguk.engine.world

import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.*

/**
 * HWIHA 는 縣 창고가 유일한 재정이라 기존 국가·개인 재정을 적용하지 않는다. 끄는 것은 재정뿐이고
 * 같은 이벤트 안의 비재정 부분(도시 성장, 전투 사상자 정산)은 남는다. 두 프로파일을 같이 검사한다 —
 * SAMMO 쪽이 함께 죽으면 동결 회귀가 깨진 것이다.
 */
class WorldActionContextHwihaFinanceTest {
    private fun world(profile: String): InMemoryTurnWorld {
        val hash = "b".repeat(64)
        return InMemoryTurnWorld(
            WorldSnapshot(
                worldId = WorldId(1),
                state = TurnWorldState(
                    1, 200, 1, 3600, Instant.EPOCH,
                    config = mapOf("ruleProfile" to profile, "mapName" to "han-world-v3"),
                ),
                nations = listOf(Nation(1, "N1", "#000", gold = 1_000, rice = 2_000)),
                generals = listOf(
                    TurnGeneral(
                        id = 5, name = "G5", nationId = 1, cityId = 10, userId = "42", npcState = 2,
                        troopId = 0, stats = GeneralStats(70, 70, 70), experience = 0, dedication = 100,
                        officerLevel = 5, gold = 100, rice = 200, crew = 0, turnTime = Instant.EPOCH,
                    )
                ),
                cities = listOf(
                    City(
                        id = 10, name = "縣", nationId = 1, level = 1, population = 1_000,
                        populationMax = 2_000, dead = 50, agriculture = 10, agricultureMax = 100,
                        commerce = 10, commerceMax = 100, security = 10, securityMax = 100,
                        defence = 10, defenceMax = 100, wall = 10, wallMax = 100, supplyState = 1,
                    )
                ),
                administrativeCountyIds = setOf(10),
                generalPositionSnapshot = GeneralPositionSnapshot("r1", hash, setOf("p10"), emptySet())
                    .withState(GeneralPositionState("r1", hash, 5, StrategicNodeRef.LandProvince("p10"), 1)),
                cityLandProvinceById = mapOf(10 to "p10"),
            )
        )
    }

    private fun context(world: InMemoryTurnWorld, recorder: ChangeRecorder) = WorldActionContext(
        env = linkedMapOf<String, Any?>("year" to 200, "month" to 1),
        world = world, recorder = recorder, pipeline = GeneralActionPipeline(emptyList()),
    )

    private val income = ProcessIncomeResult(
        resource = "gold",
        nationUpdates = listOf(IncomeNationUpdate(1, 9_999, 0, 0.0)),
        prevIncome = mapOf(1 to 123.0),
        generalPayouts = listOf(IncomeGeneralPayout(5, 77, listOf("payout"))),
        globalHistory = "봄 수입",
    )
    private val warIncome = ProcessWarIncomeResult(
        nationGoldAdds = listOf(WarIncomeNationAdd(1, 500, 1_500)),
        cityUpdates = listOf(WarIncomeCityUpdate(10, 1_010, 0)),
    )
    private val semiAnnual = ProcessSemiAnnualResult(
        resource = "gold",
        cityUpdates = listOf(SemiAnnualCityUpdate(10, 20, 21, 22, 23, 24, 1_100, 90.0, 0)),
        generalUpkeep = listOf(SemiAnnualGeneralUpkeep(5, 42)),
        nationUpkeep = listOf(SemiAnnualNationUpkeep(1, 43)),
    )

    @Test
    fun `HWIHA 는 기존 세입을 국가에도 개인에도 적용하지 않는다`() {
        val world = world("HWIHA")
        val recorder = ChangeRecorder()
        context(world, recorder).applyIncome(income)

        assertEquals(1_000, world.getNationById(1)!!.gold)
        assertEquals(100, world.getGeneralById(5)!!.gold)
        assertTrue(recorder.kvDirty().none { it.key.key.startsWith("prev_income") }, "prev_income 도 남기지 않는다")
    }

    @Test
    fun `SAMMO 는 기존 세입을 그대로 적용한다`() {
        val world = world("SAMMO")
        val recorder = ChangeRecorder()
        context(world, recorder).applyIncome(income)

        assertEquals(9_999, world.getNationById(1)!!.gold)
        assertEquals(177, world.getGeneralById(5)!!.gold)
        assertTrue(recorder.kvDirty().any { it.key.key == "prev_income_gold" })
    }

    @Test
    fun `HWIHA 전쟁 세입은 국가 금을 안 올리되 도시 사상자 정산은 남긴다`() {
        val world = world("HWIHA")
        context(world, ChangeRecorder()).applyWarIncome(warIncome)

        assertEquals(1_000, world.getNationById(1)!!.gold, "국가 금은 그대로다")
        assertEquals(1_010, world.getCityById(10)!!.population, "부상병 회복은 적용된다")
    }

    @Test
    fun `SAMMO 전쟁 세입은 국가 금까지 올린다`() {
        val world = world("SAMMO")
        context(world, ChangeRecorder()).applyWarIncome(warIncome)

        assertEquals(1_500, world.getNationById(1)!!.gold)
        assertEquals(1_010, world.getCityById(10)!!.population)
    }

    @Test
    fun `HWIHA 반년 처리는 도시 성장만 남기고 유지비를 끈다`() {
        val world = world("HWIHA")
        context(world, ChangeRecorder()).applySemiAnnual(semiAnnual)

        val city = world.getCityById(10)!!
        assertEquals(20, city.agriculture, "내정 성장은 적용된다")
        assertEquals(1_100, city.population)
        assertEquals(90.0, city.meta["trust"])
        assertEquals(100, world.getGeneralById(5)!!.gold, "개인 유지비는 끈다")
        assertEquals(1_000, world.getNationById(1)!!.gold, "국가 유지비도 끈다")
    }

    @Test
    fun `SAMMO 반년 처리는 유지비까지 적용한다`() {
        val world = world("SAMMO")
        context(world, ChangeRecorder()).applySemiAnnual(semiAnnual)

        assertEquals(20, world.getCityById(10)!!.agriculture)
        assertEquals(42, world.getGeneralById(5)!!.gold)
        assertEquals(43, world.getNationById(1)!!.gold)
    }
}

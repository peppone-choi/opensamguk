package opensamguk.logic.world

import opensamguk.logic.domain.City
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port-faithful tests for `ProcessWarIncome` (P3 / AREA A1 / Task IN3) — research Unit 2.
 *
 * PHP grand truth `Event/Action/ProcessWarIncome.php:11-36`:
 *   - group cities by nation; per nation in `getAllNationStaticInfo()` (ASCENDING id);
 *   - SKIP `level <= 0` nations (no gold add);
 *   - `income = getWarGoldIncome(type, cities)` = Σ `calcCityWarGoldIncome(city, nationTypeObj)`;
 *   - `nation.gold += income`;
 *   - THEN ONE bulk city update across ALL cities — the PHP filter is `true` (no WHERE), so
 *     `pop += dead * 0.2, dead = 0` applies to EVERY city incl. unowned/neutral (부상병 회복).
 *
 * EventTarget = PRE_MONTH (fires at L5, JUST BEFORE preUpdateMonthly) — it reads the post-supply
 * snapshot (UpdateCitySupply ran first at array index 0 of the F2 `pre_month` row; ProcessWarIncome is
 * index 1, both priority 9000 — the order is the array index, NOT a priority tiebreak).
 */
class ProcessWarIncomeTest {

    private val pipeline = GeneralActionPipeline(emptyList())

    private val goldBuffNationType = object : GeneralActionModule {
        override fun onCalcNationalIncome(general: opensamguk.logic.domain.General, type: String, value: Double): Double =
            if (type == "gold") value * 1.1 else value
    }

    private fun city(id: Int, nationId: Int, supply: Int = 1, dead: Int = 0, pop: Int = 30000) = City(
        id = id, nationId = nationId, level = 5,
        commerce = 0, commerceMax = 1, agriculture = 0, agricultureMax = 1,
        supplyState = supply, frontState = 0, trust = 0.0,
        security = 0, securityMax = 1, defense = 0, wall = 0, wallMax = 1,
        population = pop, populationMax = 1000000, dead = dead,
    )

    private fun nation(id: Int, level: Int = 5, gold: Int = 1000, nationType: GeneralActionModule? = null) =
        WarIncomeNation(id = id, level = level, gold = gold, nationType = nationType)

    @Test
    fun `level over 0 nation gains the summed per-city war gold`() {
        val cities = listOf(city(1, 1, dead = 1000), city(2, 1, dead = 500))
        val result = processWarIncome(listOf(nation(1, level = 5, gold = 1000)), cities, pipeline)
        // 1000/10=100 + 500/10=50 → income 150 ; gold 1000 → 1150
        assertEquals(150, result.nationGoldAdds.first { it.nationId == 1 }.income)
        assertEquals(1150, result.nationGoldAdds.first { it.nationId == 1 }.newGold)
    }

    @Test
    fun `level zero or below is skipped (no gold add)`() {
        val cities = listOf(city(1, 1, dead = 1000))
        val result = processWarIncome(listOf(nation(1, level = 0, gold = 1000)), cities, pipeline)
        assertTrue(result.nationGoldAdds.none { it.nationId == 1 })
    }

    @Test
    fun `war gold sums calcCityWarGoldIncome with the nation type fold`() {
        val cities = listOf(city(1, 1, dead = 105))
        val result = processWarIncome(listOf(nation(1, level = 5, gold = 0, nationType = goldBuffNationType)), cities, pipeline)
        // dead=105 → 10.5 → *1.1 = 11.55 → phpRound = 12
        assertEquals(12, result.nationGoldAdds.first { it.nationId == 1 }.income)
    }

    @Test
    fun `unsupplied city contributes zero war gold`() {
        val cities = listOf(city(1, 1, supply = 0, dead = 1000), city(2, 1, supply = 1, dead = 1000))
        val result = processWarIncome(listOf(nation(1, level = 5, gold = 0)), cities, pipeline)
        // supply==0 → 0 ; supply==1 dead 1000 → 100
        assertEquals(100, result.nationGoldAdds.first { it.nationId == 1 }.income)
    }

    @Test
    fun `pop plus dead times 0point2 and dead reset applies to ALL cities incl unowned`() {
        val cities = listOf(
            city(1, 1, dead = 1000, pop = 30000),  // owned
            city(2, 0, dead = 500, pop = 20000),   // unowned / neutral (nation 0) — filter is true
        )
        val result = processWarIncome(listOf(nation(1, level = 5)), cities, pipeline)
        val c1 = result.cityUpdates.first { it.cityId == 1 }
        val c2 = result.cityUpdates.first { it.cityId == 2 }
        assertEquals(30000 + 200, c1.newPop) // 30000 + 1000*0.2
        assertEquals(0, c1.newDead)
        assertEquals(20000 + 100, c2.newPop) // neutral still recovers 부상병
        assertEquals(0, c2.newDead)
    }

    @Test
    fun `nations processed in ascending id order for gold adds`() {
        val cities = listOf(city(1, 2, dead = 100), city(2, 1, dead = 100))
        val result = processWarIncome(listOf(nation(2), nation(1)), cities, pipeline)
        assertEquals(listOf(1, 2), result.nationGoldAdds.map { it.nationId })
    }

    @Test
    fun `leaf registers into the F2 factory and applies via context`() {
        val factory = opensamguk.logic.event.EventActionFactory()
        ProcessWarIncomeAction.register(factory)
        assertTrue(factory.has("ProcessWarIncome"))
        val action = factory.create(opensamguk.logic.event.RawAction("ProcessWarIncome", emptyList()))
        assertTrue(action is ProcessWarIncomeAction)

        var applied: ProcessWarIncomeResult? = null
        val ctx = object : ProcessWarIncomeContext {
            override val env = mapOf<String, Any?>("year" to 200, "month" to 1, "currentEventID" to 1)
            override val pipeline = this@ProcessWarIncomeTest.pipeline
            override fun warIncomeNations() = listOf(nation(1, level = 5, gold = 0))
            override fun cities() = listOf(city(1, 1, dead = 1000))
            override fun applyWarIncome(result: ProcessWarIncomeResult) { applied = result }
        }
        action.run(ctx)
        assertEquals(100, applied!!.nationGoldAdds.first().income)
    }
}

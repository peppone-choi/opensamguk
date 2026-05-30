package opensamguk.logic.world

import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.log.HistoryTokens
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port-faithful tests for `ProcessIncome(gold|rice)` (P3 / AREA A1 / Task IN2) — research Unit 2.
 *
 * PHP grand truth `Event/Action/ProcessIncome.php:32-209`:
 *   - per-nation loop in ASCENDING nation id (PHP SELECT row order);
 *   - gold: `income = getGoldIncome(...)`; rice: `income = getRiceIncome(...) + getWallIncome(...)`;
 *   - `originoutcome = getOutcome(100, generalRawList)`; `outcome = Util::round(bill/100 * originoutcome)`;
 *   - 3-branch payout split vs GameConst::$basegold (0) / $baserice (2000):
 *       (a) res+income < base       → realoutcome=0, ratio=0, res=valueFit(res+income, base)
 *       (b) (res+income)-base < out → realoutcome=res+income-base, res→base, ratio=realoutcome/originoutcome
 *       (c) else                    → realoutcome=outcome, res=res+income-outcome, ratio=outcome/originoutcome
 *     then `res = Util::valueFit(res, base)` (lower clamp);
 *   - `prev_income_{gold,rice} = income` (GROSS) written to nation_env KV BEFORE the per-general payout;
 *   - per general: `pay = Util::round(getBill(dedication) * ratio)`, increaseVar(resource, pay);
 *     officer_level > 4 → income line; ALL → salary line;
 *   - global history (봄 for gold / 가을 for rice) once after the nation loop.
 *
 * The income fold uses the NATION-TYPE source ONLY; READS rate_tmp (preUpdateMonthly froze it), NOT rate.
 */
class ProcessIncomeTest {

    private val pipeline = GeneralActionPipeline(emptyList())

    private fun city(
        id: Int, supply: Int = 1, pop: Int = 30000,
        comm: Int = 1000, commMax: Int = 2000, agri: Int = 1000, agriMax: Int = 2000,
        secu: Int = 1000, secuMax: Int = 2000, def: Int = 1000, wall: Int = 1000, wallMax: Int = 2000,
        trust: Double = 100.0,
    ) = City(
        id = id, nationId = 1, level = 5,
        commerce = comm, commerceMax = commMax, agriculture = agri, agricultureMax = agriMax,
        supplyState = supply, frontState = 0, trust = trust,
        security = secu, securityMax = secuMax, defense = def, wall = wall, wallMax = wallMax,
        population = pop, populationMax = 100000,
    )

    private fun gen(id: Int, dedication: Double, officerLevel: Int) =
        IncomeGeneral(id = id, dedication = dedication, officerLevel = officerLevel)

    private fun nation(
        id: Int, name: String = "촉", gold: Int = 10000, rice: Int = 10000,
        level: Int = 5, taxRate: Double = 20.0, bill: Double = 100.0, capitalId: Int = 1,
        nationType: GeneralActionModule? = null,
        cities: List<City> = listOf(city(1)),
        generals: List<IncomeGeneral> = listOf(gen(1, 1000.0, 5)),
        officerCntByCity: Map<Int, Int> = emptyMap(),
    ) = IncomeNation(
        id = id, name = name, gold = gold, rice = rice, level = level,
        taxRate = taxRate, bill = bill, capitalId = capitalId, nationType = nationType,
        cities = cities, generals = generals, officerCntByCity = officerCntByCity,
    )

    @Test
    fun `nations processed in ascending id order`() {
        val n2 = nation(id = 2, name = "위")
        val n1 = nation(id = 1, name = "촉")
        val result = processIncome("gold", listOf(n2, n1), pipeline, year = 200, month = 1)
        assertEquals(listOf(1, 2), result.nationUpdates.map { it.nationId })
    }

    @Test
    fun `gold income only uses getGoldIncome (no wall) and rice sums rice plus wall`() {
        val n = nation(id = 1, generals = emptyList())
        val goldExpected = opensamguk.logic.domestic.getGoldIncome(n.cities, n.capitalId, n.level, n.taxRate, n.nationType, pipeline)
        val riceExpected = opensamguk.logic.domestic.getRiceIncome(n.cities, n.capitalId, n.level, n.taxRate, n.nationType, pipeline) +
            opensamguk.logic.domestic.getWallIncome(n.cities, n.capitalId, n.level, n.taxRate, n.nationType, pipeline)

        val gResult = processIncome("gold", listOf(n), pipeline, year = 200, month = 1)
        assertEquals(opensamguk.logic.util.phpRound(goldExpected), gResult.prevIncome[1])

        val rResult = processIncome("rice", listOf(n), pipeline, year = 200, month = 7)
        assertEquals(opensamguk.logic.util.phpRound(riceExpected), rResult.prevIncome[1])
    }

    @Test
    fun `prev_income written GROSS before payout with literal income value`() {
        // income (gross) is what goes to prev_income — not realoutcome.
        val n = nation(id = 1, generals = listOf(gen(1, 1000.0, 5)))
        val result = processIncome("gold", listOf(n), pipeline, year = 200, month = 1)
        val grossGold = opensamguk.logic.util.phpRound(
            opensamguk.logic.domestic.getGoldIncome(n.cities, n.capitalId, n.level, n.taxRate, n.nationType, pipeline)
        )
        assertEquals(grossGold, result.prevIncome[1])
    }

    @Test
    fun `basegold branch-a fires only when gold would go negative`() {
        // gold=0, no income, an outcome to pay → res+income (0) < basegold? basegold=0 so 0<0 is false.
        // Force negative: a nation with 0 gold and 0 income and a bill — income is computed, here 0 income
        // city makes income 0; outcome from a dedicated general. res(0)+income(0)=0, basegold=0, 0<0 false.
        // branch (b): (0)-0 < outcome → realoutcome=0, ratio=0.
        val n = nation(id = 1, gold = 0, cities = listOf(city(1, supply = 0)), generals = listOf(gen(1, 4000.0, 5)))
        val result = processIncome("gold", listOf(n), pipeline, year = 200, month = 1)
        // unsupplied city → 0 income; gold stays clamped at basegold (0); ratio 0 → 0 payout.
        assertEquals(0, result.nationUpdates.first().newResource)
        assertEquals(0, result.generalPayouts.first { it.generalId == 1 }.amount)
    }

    @Test
    fun `baserice floor clamps the nation at 2000`() {
        // rice=2000 exactly, income 0 (unsupplied), an outcome demand: res+income=2000, baserice=2000.
        // branch (b): (2000)-2000=0 < outcome → realoutcome=0, ratio=0; res = valueFit(2000, 2000)=2000.
        val n = nation(id = 1, rice = 2000, cities = listOf(city(1, supply = 0)), generals = listOf(gen(1, 4000.0, 5)))
        val result = processIncome("rice", listOf(n), pipeline, year = 200, month = 7)
        assertEquals(2000, result.nationUpdates.first().newResource)
    }

    @Test
    fun `officer level over 4 gets income line and all generals get salary line`() {
        val n = nation(
            id = 1, gold = 100000,
            generals = listOf(gen(1, 4000.0, 5), gen(2, 4000.0, 3)),
        )
        val result = processIncome("gold", listOf(n), pipeline, year = 200, month = 1)
        val g1 = result.generalPayouts.first { it.generalId == 1 } // officer_level 5 (>4)
        val g2 = result.generalPayouts.first { it.generalId == 2 } // officer_level 3 (<=4)
        assertTrue(g1.logLines.any { it == HistoryTokens.goldSalaryLine(g1.amount) })
        assertTrue(g1.logLines.any { it == HistoryTokens.goldIncomeLine(result.prevIncome[1]!!) })
        assertTrue(g2.logLines.any { it == HistoryTokens.goldSalaryLine(g2.amount) })
        assertTrue(g2.logLines.none { it == HistoryTokens.goldIncomeLine(result.prevIncome[1]!!) })
    }

    @Test
    fun `global history string is spring for gold and autumn for rice`() {
        val gResult = processIncome("gold", listOf(nation(id = 1)), pipeline, year = 200, month = 1)
        assertEquals(HistoryTokens.springIncomeGlobal(), gResult.globalHistory)
        val rResult = processIncome("rice", listOf(nation(id = 1)), pipeline, year = 200, month = 7)
        assertEquals(HistoryTokens.autumnIncomeGlobal(), rResult.globalHistory)
    }

    @Test
    fun `per general payout is phpRound of getBill times ratio`() {
        val n = nation(id = 1, gold = 1000000, generals = listOf(gen(1, 4000.0, 5)))
        val result = processIncome("gold", listOf(n), pipeline, year = 200, month = 1)
        val ratio = result.nationUpdates.first().ratio
        val expected = opensamguk.logic.util.phpRound(opensamguk.logic.domestic.getBill(4000.0) * ratio)
        assertEquals(expected, result.generalPayouts.first { it.generalId == 1 }.amount)
    }

    @Test
    fun `leaf registers into the F2 factory by name and dispatches the resource arg`() {
        val factory = opensamguk.logic.event.EventActionFactory()
        ProcessIncomeAction.register(factory)
        assertTrue(factory.has("ProcessIncome"))

        val raw = opensamguk.logic.event.RawAction(
            "ProcessIncome",
            listOf(kotlinx.serialization.json.JsonPrimitive("gold")),
        )
        val action = factory.create(raw)
        assertTrue(action is ProcessIncomeAction)
        assertEquals("gold", (action as ProcessIncomeAction).resource)

        // The leaf runs against a richer ProcessIncomeContext and applies the computed result.
        var applied: ProcessIncomeResult? = null
        val ctx = object : ProcessIncomeContext {
            override val env = mapOf<String, Any?>("year" to 200, "month" to 1, "currentEventID" to 7)
            override val pipeline = this@ProcessIncomeTest.pipeline
            override fun nations() = listOf(nation(id = 1))
            override fun applyIncome(result: ProcessIncomeResult) { applied = result }
        }
        action.run(ctx)
        assertEquals("gold", applied!!.resource)
        assertEquals(HistoryTokens.springIncomeGlobal(), applied!!.globalHistory)
    }
}

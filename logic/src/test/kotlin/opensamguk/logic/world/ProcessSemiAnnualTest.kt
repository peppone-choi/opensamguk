package opensamguk.logic.world

import opensamguk.logic.domain.City
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port-faithful tests for `ProcessSemiAnnual(gold|rice)` + `popIncrease` (P3 / AREA A1 / Task IN4;
 * AREA A5 popIncrease folded here) — research Unit 2.
 *
 * PHP grand truth `Event/Action/ProcessSemiAnnual.php` + `func_time_event.php:35-77` (global popIncrease):
 *   run() (:67-97):
 *     1. bulk over ALL cities (filter true): dead=0; agri/comm/secu/def/wall *= 0.99;
 *     2. popIncrease() (the GLOBAL one, identical body);
 *     3. general upkeep `WHERE res>1000`: IF(res>10000, res*0.97, res*0.99);
 *     4. nation  upkeep `WHERE res>1000`: IF(res>100000, res*0.95, IF(res>10000, res*0.97, res*0.99)).
 *   popIncrease() (:35-77):
 *     1. nation=0 cities: trust=50; agri/comm/secu/def/wall *= 0.99;
 *     2. per nation (SELECT nation,rate_tmp,type): popRatio=(30-tax)/200 → onCalcNationalIncome('pop',...);
 *        pop = least(pop_max, basePopIncreaseAmount + pop*(1 + popRatio*(1 ± secu/secu_max/10)))  (+ if >=0);
 *        agri/comm/secu/def/wall = least(*_max, * * (1 + (20-tax)/200)); trust=clamp(trust+(20-tax),0,100);
 *        FILTER nation=X AND supply=1.
 *
 * NEUTRAL DOUBLE-DECAY: run() decays nation=0 ×0.99 AND popIncrease() decays nation=0 ×0.99 again →
 * net ×0.9801; run() PRECEDES popIncrease(). Int columns (agri/comm/secu/def/wall/pop) round at EACH
 * SQL store (phpRound); trust is double (F4 type fix) — NO round.
 */
class ProcessSemiAnnualTest {

    private val pipeline = GeneralActionPipeline(emptyList())

    private val popDampNationType = object : GeneralActionModule {
        // halves the popRatio when positive; passes a negative through unmodified (the value>0 gate note)
        override fun onCalcNationalIncome(general: opensamguk.logic.domain.General, type: String, value: Double): Double =
            if (type == "pop" && value > 0) value / 2 else value
    }

    private fun city(
        id: Int, nationId: Int, supply: Int = 1, trust: Double = 80.0,
        agri: Int = 1000, comm: Int = 1000, secu: Int = 1000, def: Int = 1000, wall: Int = 1000,
        pop: Int = 30000, popMax: Int = 1000000, dead: Int = 100,
        agriMax: Int = 5000, commMax: Int = 5000, secuMax: Int = 5000, defMax: Int = 5000, wallMax: Int = 5000,
    ) = City(
        id = id, nationId = nationId, level = 5,
        commerce = comm, commerceMax = commMax, agriculture = agri, agricultureMax = agriMax,
        supplyState = supply, frontState = 0, trust = trust,
        security = secu, securityMax = secuMax, defense = def, wall = wall, wallMax = wallMax,
        population = pop, populationMax = popMax, dead = dead,
    )

    private fun nation(id: Int, taxRate: Double = 20.0, nationType: GeneralActionModule? = null) =
        SemiAnnualNation(id = id, taxRate = taxRate, nationType = nationType)

    @Test
    fun `run decays all cities 1 percent and resets dead`() {
        val result = processSemiAnnual("gold", listOf(nation(1)), listOf(city(1, 1, supply = 0, dead = 100)), pipeline)
        // supply=0 owned city → only run() phase decays the 5 stats ×0.99; dead→0; per-nation loop filters supply=1.
        val c = result.cityUpdates.first { it.cityId == 1 }
        assertEquals(0, c.newDead)
        assertEquals(phpRound(1000 * 0.99), c.newAgri)
        assertEquals(phpRound(1000 * 0.99), c.newComm)
    }

    @Test
    fun `neutral nation0 city is double-decayed run then popIncrease and trust set to 50`() {
        val result = processSemiAnnual("gold", listOf(nation(1)), listOf(city(1, 0, trust = 80.0)), pipeline)
        val c = result.cityUpdates.first { it.cityId == 1 }
        // run() ×0.99 (rounded), THEN popIncrease() ×0.99 (rounded) → net per-phase rounding.
        val afterRun = phpRound(1000 * 0.99)
        val afterPop = phpRound(afterRun * 0.99)
        assertEquals(afterPop, c.newAgri)
        assertEquals(afterPop, c.newComm)
        assertEquals(50.0, c.newTrust) // neutral trust forced to 50
    }

    @Test
    fun `owned supplied city grows pop with positive popRatio`() {
        // tax 20 → popRatio = (30-20)/200 = 0.05 (positive) → pop grows.
        val result = processSemiAnnual("gold", listOf(nation(1, taxRate = 20.0)), listOf(city(1, 1, pop = 30000)), pipeline)
        val c = result.cityUpdates.first { it.cityId == 1 }
        assertTrue(c.newPop > 30000)
    }

    @Test
    fun `negative popRatio passes through the nation-type fold unmodified`() {
        // tax 50 → popRatio = (30-50)/200 = -0.10 ; popDamp leaves negatives unchanged.
        val r1 = processSemiAnnual("gold", listOf(nation(1, taxRate = 50.0, nationType = popDampNationType)), listOf(city(1, 1)), pipeline)
        val r2 = processSemiAnnual("gold", listOf(nation(1, taxRate = 50.0, nationType = null)), listOf(city(1, 1)), pipeline)
        assertEquals(r2.cityUpdates.first().newPop, r1.cityUpdates.first().newPop)
    }

    @Test
    fun `trust clamps to the 0 to 100 range`() {
        // tax 0 → trustDiff = 20 ; trust 90 + 20 = 110 → clamp 100.
        val high = processSemiAnnual("gold", listOf(nation(1, taxRate = 0.0)), listOf(city(1, 1, trust = 90.0)), pipeline)
        assertEquals(100.0, high.cityUpdates.first().newTrust)
        // tax 100 → trustDiff = -80 ; trust 50 - 80 = -30 → clamp 0.
        val low = processSemiAnnual("gold", listOf(nation(1, taxRate = 100.0)), listOf(city(1, 1, trust = 50.0)), pipeline)
        assertEquals(0.0, low.cityUpdates.first().newTrust)
    }

    @Test
    fun `general upkeep tiered thresholds gold`() {
        val gens = listOf(
            SemiAnnualGeneral(1, gold = 500, rice = 0),    // <=1000 → unchanged
            SemiAnnualGeneral(2, gold = 5000, rice = 0),   // 1000<res<=10000 → ×0.99
            SemiAnnualGeneral(3, gold = 50000, rice = 0),  // >10000 → ×0.97
        )
        val result = processSemiAnnual("gold", listOf(nation(1)), listOf(city(1, 1)), pipeline, generals = gens)
        assertEquals(500, result.generalUpkeep.first { it.generalId == 1 }.newResource)
        assertEquals(phpRound(5000 * 0.99), result.generalUpkeep.first { it.generalId == 2 }.newResource)
        assertEquals(phpRound(50000 * 0.97), result.generalUpkeep.first { it.generalId == 3 }.newResource)
    }

    @Test
    fun `nation upkeep tiered thresholds rice`() {
        val nats = listOf(
            SemiAnnualNation(1, taxRate = 20.0, gold = 0, rice = 500),     // <=1000 unchanged
            SemiAnnualNation(2, taxRate = 20.0, gold = 0, rice = 5000),    // ×0.99
            SemiAnnualNation(3, taxRate = 20.0, gold = 0, rice = 50000),   // ×0.97
            SemiAnnualNation(4, taxRate = 20.0, gold = 0, rice = 500000),  // ×0.95
        )
        val result = processSemiAnnual("rice", nats, listOf(city(1, 1)), pipeline)
        assertEquals(500, result.nationUpkeep.first { it.nationId == 1 }.newResource)
        assertEquals(phpRound(5000 * 0.99), result.nationUpkeep.first { it.nationId == 2 }.newResource)
        assertEquals(phpRound(50000 * 0.97), result.nationUpkeep.first { it.nationId == 3 }.newResource)
        assertEquals(phpRound(500000 * 0.95), result.nationUpkeep.first { it.nationId == 4 }.newResource)
    }

    @Test
    fun `deterministic ascending id ordering for city updates`() {
        val result = processSemiAnnual("gold", listOf(nation(1)), listOf(city(2, 1), city(1, 1)), pipeline)
        assertEquals(listOf(1, 2), result.cityUpdates.map { it.cityId })
    }

    @Test
    fun `leaf registers into the F2 factory and dispatches the resource arg`() {
        val factory = opensamguk.logic.event.EventActionFactory()
        ProcessSemiAnnualAction.register(factory)
        assertTrue(factory.has("ProcessSemiAnnual"))
        val action = factory.create(
            opensamguk.logic.event.RawAction("ProcessSemiAnnual", listOf(kotlinx.serialization.json.JsonPrimitive("gold")))
        )
        assertTrue(action is ProcessSemiAnnualAction)
        assertEquals("gold", (action as ProcessSemiAnnualAction).resource)

        var applied: ProcessSemiAnnualResult? = null
        val ctx = object : ProcessSemiAnnualContext {
            override val env = mapOf<String, Any?>("year" to 200, "month" to 1, "currentEventID" to 1)
            override val pipeline = this@ProcessSemiAnnualTest.pipeline
            override fun semiAnnualNations() = listOf(nation(1))
            override fun cities() = listOf(city(1, 1))
            override fun semiAnnualGenerals() = emptyList<SemiAnnualGeneral>()
            override fun applySemiAnnual(result: ProcessSemiAnnualResult) { applied = result }
        }
        action.run(ctx)
        assertEquals(1, applied!!.cityUpdates.size)
    }
}

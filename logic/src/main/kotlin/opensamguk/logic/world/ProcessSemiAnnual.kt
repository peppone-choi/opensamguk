package opensamguk.logic.world

import opensamguk.common.constants.GameConst
import opensamguk.logic.domain.City
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.math.min
import kotlin.math.max

/**
 * P3 / AREA A1 / Task IN4 — `ProcessSemiAnnual(gold|rice)` Action + `popIncrease` (AREA A5 folded here).
 *
 * Faithful port of PHP `Event/Action/ProcessSemiAnnual.php` + `func_time_event.php:35-77` (the global
 * `popIncrease` — byte-identical to the class method; `run()` calls the GLOBAL one).
 *
 * `run()` (ProcessSemiAnnual.php:67-97), in EXACT order:
 *   1. bulk over ALL cities (filter `true`): `dead = 0`; `agri/comm/secu/def/wall *= 0.99`;
 *   2. `popIncrease()`;
 *   3. general upkeep `WHERE res > 1000`: `IF(res>10000, res*0.97, res*0.99)`;
 *   4. nation  upkeep `WHERE res > 1000`: `IF(res>100000, res*0.95, IF(res>10000, res*0.97, res*0.99))`.
 *
 * `popIncrease()` (func_time_event.php:35-77):
 *   1. nation=0 cities: `trust = 50`; `agri/comm/secu/def/wall *= 0.99`;
 *   2. per nation (`SELECT nation,rate_tmp,type`):
 *        `popRatio = (30 - taxRate)/200`; `popRatio = onCalcNationalIncome('pop', popRatio)`;
 *        `pop = least(pop_max, basePopIncreaseAmount + pop*(1 + popRatio*(1 ± secu/secu_max/10)))` (`+` if >=0);
 *        for each of agri/comm/secu/def/wall: `least(*_max, * * (1 + (20-taxRate)/200))`;
 *        `trust = greatest(0, least(100, trust + (20-taxRate)))`;
 *        FILTER `nation = X AND supply = 1`.
 *
 * NEUTRAL DOUBLE-DECAY (source-verified): `run()` decays nation=0 cities ×0.99, then `popIncrease()`
 * decays them ×0.99 AGAIN → net ×0.9801. `run()` PRECEDES `popIncrease()`. Each phase is a separate SQL
 * UPDATE → the INT columns (agri/comm/secu/def/wall/pop) round at EVERY store ([phpRound]); `trust` is
 * double precision (the F4 type fix) and is NOT rounded. The pop fold reads the POST-run-decay secu.
 *
 * The pop fold routes through the NATION-TYPE source ONLY ([GeneralActionPipeline.nationIncomeFold]);
 * a null nation type folds as identity (the `value>0` gate, if any, lives in the nation-type module).
 */

/** One nation's semi-annual input. `taxRate` = `rate_tmp`. `gold`/`rice` feed the nation upkeep tier. */
data class SemiAnnualNation(
    val id: Int,
    val taxRate: Double,
    val nationType: GeneralActionModule? = null,
    val gold: Int = 0,
    val rice: Int = 0,
)

/** A general's resource for the upkeep tier (`gold`/`rice`). */
data class SemiAnnualGeneral(
    val id: Int,
    val gold: Int,
    val rice: Int,
)

/** Per-city post-tick state (the net of run-decay + popIncrease). */
data class SemiAnnualCityUpdate(
    val cityId: Int,
    val newAgri: Int, val newComm: Int, val newSecu: Int, val newDef: Int, val newWall: Int,
    val newPop: Int, val newTrust: Double, val newDead: Int,
)

/** Per-general upkeep result (`newResource` of the ticked resource). */
data class SemiAnnualGeneralUpkeep(val generalId: Int, val newResource: Int)

/** Per-nation upkeep result (`newResource` of the ticked resource). */
data class SemiAnnualNationUpkeep(val nationId: Int, val newResource: Int)

/** The structured IN4 result the [EventAction] wrapper applies to the world + flush. */
data class ProcessSemiAnnualResult(
    val resource: String,
    val cityUpdates: List<SemiAnnualCityUpdate>,
    val generalUpkeep: List<SemiAnnualGeneralUpkeep>,
    val nationUpkeep: List<SemiAnnualNationUpkeep>,
)

/** Mutable per-city working state (int columns round at each phase; trust stays double). */
private class CityWork(c: City) {
    val id = c.id
    val nationId = c.nationId
    val supply = c.supplyState
    var agri = c.agriculture; val agriMax = c.agricultureMax
    var comm = c.commerce; val commMax = c.commerceMax
    var secu = c.security; val secuMax = c.securityMax
    var def = c.defense; val defMax = c.defenseMax
    var wall = c.wall; val wallMax = c.wallMax
    var pop = c.population; val popMax = c.populationMax
    var trust = c.trust
    var dead = c.dead
}

/** The pure IN4 core: city decay + popIncrease (incl. neutral double-decay) + tiered general/nation upkeep. */
fun processSemiAnnual(
    resource: String,
    nations: List<SemiAnnualNation>,
    cities: List<City>,
    pipeline: GeneralActionPipeline,
    generals: List<SemiAnnualGeneral> = emptyList(),
): ProcessSemiAnnualResult {
    require(resource == "gold" || resource == "rice") { "잘못된 자원 타입" }
    val isGold = resource == "gold"

    // Deterministic ascending id ordering for all city math.
    val work = cities.sortedBy { it.id }.map { CityWork(it) }

    // --- run() phase 1: ALL cities — dead=0; the 5 stats ×0.99 (rounded per store) ---
    for (w in work) {
        w.dead = 0
        w.agri = phpRound(w.agri * 0.99)
        w.comm = phpRound(w.comm * 0.99)
        w.secu = phpRound(w.secu * 0.99)
        w.def = phpRound(w.def * 0.99)
        w.wall = phpRound(w.wall * 0.99)
    }

    // --- run() phase 2: popIncrease() ---
    // popIncrease step 1: nation=0 cities → trust=50; the 5 stats ×0.99 AGAIN (the double-decay).
    for (w in work) {
        if (w.nationId == 0) {
            w.trust = 50.0
            w.agri = phpRound(w.agri * 0.99)
            w.comm = phpRound(w.comm * 0.99)
            w.secu = phpRound(w.secu * 0.99)
            w.def = phpRound(w.def * 0.99)
            w.wall = phpRound(w.wall * 0.99)
        }
    }
    // popIncrease step 2: per nation (ascending id), over its supply=1 cities.
    val byNation = work.groupBy { it.nationId }
    for (nation in nations.sortedBy { it.id }) {
        val cs = byNation[nation.id]?.filter { it.supply == 1 } ?: continue
        val rawPopRatio = (30 - nation.taxRate) / 200.0
        val popRatio = pipeline.nationIncomeFold(nation.nationType, "pop", rawPopRatio)
        val genericRatio = (20 - nation.taxRate) / 200.0
        val trustDiff = 20 - nation.taxRate
        for (w in cs) {
            val secuFactor = w.secu.toDouble() / w.secuMax / 10
            val popMul = if (popRatio >= 0) 1 + popRatio * (1 + secuFactor) else 1 + popRatio * (1 - secuFactor)
            val newPop = min(w.popMax.toDouble(), GameConst.basePopIncreaseAmount + w.pop * popMul)
            w.pop = phpRound(newPop)
            w.agri = phpRound(min(w.agriMax.toDouble(), w.agri * (1 + genericRatio)))
            w.comm = phpRound(min(w.commMax.toDouble(), w.comm * (1 + genericRatio)))
            w.secu = phpRound(min(w.secuMax.toDouble(), w.secu * (1 + genericRatio)))
            w.def = phpRound(min(w.defMax.toDouble(), w.def * (1 + genericRatio)))
            w.wall = phpRound(min(w.wallMax.toDouble(), w.wall * (1 + genericRatio)))
            w.trust = max(0.0, min(100.0, w.trust + trustDiff))
        }
    }

    val cityUpdates = work.map {
        SemiAnnualCityUpdate(it.id, it.agri, it.comm, it.secu, it.def, it.wall, it.pop, it.trust, it.dead)
    }

    // --- run() phase 3: general upkeep WHERE res>1000: IF(res>10000, ×0.97, ×0.99) ---
    val generalUpkeep = generals.sortedBy { it.id }.map { g ->
        val res = if (isGold) g.gold else g.rice
        val newRes = if (res > 1000) {
            if (res > 10000) phpRound(res * 0.97) else phpRound(res * 0.99)
        } else res
        SemiAnnualGeneralUpkeep(g.id, newRes)
    }

    // --- run() phase 4: nation upkeep WHERE res>1000: IF(>100000, ×0.95, IF(>10000, ×0.97, ×0.99)) ---
    val nationUpkeep = nations.sortedBy { it.id }.map { n ->
        val res = if (isGold) n.gold else n.rice
        val newRes = if (res > 1000) {
            when {
                res > 100000 -> phpRound(res * 0.95)
                res > 10000 -> phpRound(res * 0.97)
                else -> phpRound(res * 0.99)
            }
        } else res
        SemiAnnualNationUpkeep(n.id, newRes)
    }

    return ProcessSemiAnnualResult(resource, cityUpdates, generalUpkeep, nationUpkeep)
}

/**
 * The F2-dispatched `ProcessSemiAnnual` leaf (registered by name). F2's `month==1` row names
 * `ProcessSemiAnnual("gold")` at index 1 (BEFORE `ProcessIncome(gold)`) and the `month==7` row names
 * `ProcessSemiAnnual("rice")` at index 1 (BEFORE `ProcessIncome(rice)`). The calendar gating + the
 * resource arg + the intra-row position are F2-owned. Reads the world from a richer context.
 */
class ProcessSemiAnnualAction(val resource: String) : EventAction {
    init {
        require(resource == "gold" || resource == "rice") { "잘못된 자원 타입" }
    }

    override fun run(ctx: EventActionContext) {
        val psc = ctx as? ProcessSemiAnnualContext
            ?: error("ProcessSemiAnnualAction requires a ProcessSemiAnnualContext")
        psc.applySemiAnnual(processSemiAnnual(resource, psc.nations(), psc.cities(), psc.pipeline, psc.generals()))
    }

    companion object {
        const val NAME = "ProcessSemiAnnual"

        /** Register the leaf into the F2-owned factory by name (plan §append protocol). */
        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { args ->
                val resource = (args[0] as kotlinx.serialization.json.JsonPrimitive).content
                ProcessSemiAnnualAction(resource)
            }
    }
}

/** The richer dispatch context [ProcessSemiAnnualAction] consumes (supplied by the daemon, not F2). */
interface ProcessSemiAnnualContext : EventActionContext {
    val pipeline: GeneralActionPipeline
    fun nations(): List<SemiAnnualNation>
    fun cities(): List<City>
    fun generals(): List<SemiAnnualGeneral>
    fun applySemiAnnual(result: ProcessSemiAnnualResult)
}

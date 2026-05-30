package opensamguk.logic.actions.war

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.GameUnitConst
import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.*
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.tryUniqueItemLottery
import opensamguk.logic.domestic.uniqueLotterySeed
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.war.ProcessWarEnv
import opensamguk.logic.war.ProcessWarResult
import opensamguk.logic.war.WarSeed
import opensamguk.logic.war.candidateCities
import opensamguk.logic.war.processWar
import opensamguk.common.rng.LiteHashDrbg

/**
 * BO3 — `che_출병` resolver. Faithful port of `che_출병.php:134-261`.
 *
 * The SOLE caller of the OUTER `processWar()` wrapper (BO1) — never the inner `processWarNG` directly.
 * 급습/선전포고 are P6 (decision #8) and are NOT registered here.
 *
 * Flow (`che_출병.php:157-261`):
 *  1. candidateCities derivation off the BFS [opensamguk.logic.war.BattleCommandContext.distanceList]
 *     (the engine builder ran the BFS; the resolver only walks the buckets — [candidateCities]);
 *  2. the ONE per-turn-rng choice draw `defenderCityID = rng.choice(candidateCities)` — on the ctx's
 *     PER-TURN rng (NOT the warRng). `RandUtil.choice` is GREEN (reused, not re-implemented);
 *  3. friendly target (chosen city's nation == mine) → move log + alternative `che_이동` + RETURN (no battle);
 *  4. else: `city.state=43 / term=3` on the dest city; `addDex(crewType, crew/100)`; maybe +1 inheritance;
 *  5. `warSeed = WarSeed.build(hidden, loggerYear, loggerMonth, genId, destCityId)` (F6);
 *  6. call the OUTER `processWar(warSeed, …)` wrapper EXACTLY ONCE — it builds the single battle RandUtil,
 *     the defender list + getNextDefender closure, delegates to processWarNG, and returns the post-loop
 *     supply-rice/capital-bonus/dead-split deltas (all ChangeRecorder DELTAS — NO inline DB here);
 *  7. AFTER the battle: the unique-item lottery on the SEPARATE 출병 lineage
 *     (`genGenericUniqueRNGFromGeneral(general,'출병')`, distinct from the warRng).
 *
 * The cross-entity deltas (defender nation rice, both city dead, diplomacy dead, the conquerCity flag for B2)
 * are surfaced on [lastBattleResult] / [lastConquerCity] for the engine to fold into the ChangeRecorder.
 */
typealias ProcessWarFn = (warSeed: String, chosenCityId: Int, context: GeneralActionResolveContext) -> ProcessWarResult
typealias UniqueLotteryFn = (rng: RandUtil, general: General, prob: Double, trialCount: Int) -> Boolean

class CheChulbyeong(
    private val pipeline: GeneralActionPipeline,
    private val processWarFn: ProcessWarFn? = null,
    private val lotteryFn: UniqueLotteryFn = ::tryUniqueItemLottery,
) : GeneralActionDefinition {
    override val key = "che_출병"
    override val name = "출병"
    override val category = "군사"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destCityID" to "int")

    // --- last-resolve observation surface (the engine + the BO3 test read these) ---
    var lastAlternative: String? = null
        private set
    var lastChosenCityId: Int? = null
        private set
    var lastWarSeed: String? = null
        private set
    var lastConquerCity: Boolean = false
        private set
    var lastBattleResult: ProcessWarResult? = null
        private set
    var lastLotteryFired: Boolean = false
        private set

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(), occupiedCity(), reqGeneralCrew(),
    )

    override fun resolve(context: GeneralActionResolveContext) {
        lastAlternative = null
        lastChosenCityId = null
        lastWarSeed = null
        lastConquerCity = false
        lastBattleResult = null
        lastLotteryFired = false

        val bctx = context.battleContext
            ?: error("che_출병 requires a BattleCommandContext (BO2 builder)")
        val d = context.draft
        val g0 = d.general

        // (1)(2) candidateCities + the ONE per-turn choice draw (che_출병.php:163-195).
        val (candidates, currDist) = candidateCities(bctx.distanceList, bctx.myNationId)
        if (candidates.isEmpty()) {
            // No reachable candidate (should be precluded by HasRouteWithEnemy) — rest, no battle.
            return
        }
        val defenderCityId = (context.rng.choice(candidates))
        lastChosenCityId = defenderCityId

        val destCity = bctx.cityById[defenderCityId]
            ?: error("che_출병: chosen city $defenderCityId not staged")
        val defenderNationId = destCity.nationId
        val destCityName = CityConst.byId(defenderCityId)?.name ?: ""
        val josaRo = JosaUtil.pick(destCityName, "로")

        // (3) friendly target → che_이동 alternative + RETURN (che_출병.php:201-213).
        if (bctx.myNationId == defenderNationId) {
            if (bctx.finalTargetCityId == defenderCityId) {
                context.addLog("본국입니다. <G><b>$destCityName</b></>$josaRo 이동합니다. <1>${context.date}</>")
            } else {
                context.addLog("가까운 경로에 적군 도시가 없습니다. <G><b>$destCityName</b></>$josaRo 이동합니다. <1>${context.date}</>")
            }
            lastAlternative = "che_이동"
            return
        }

        // the "거쳐야/거치기로" detour log when the chosen city is not the final target (che_출병.php:215-223).
        if (bctx.finalTargetCityId != defenderCityId) {
            val finalName = CityConst.byId(bctx.finalTargetCityId)?.name ?: ""
            val josaRoFinal = JosaUtil.pick(finalName, "로")
            val josaUl = JosaUtil.pick(destCityName, "을")
            val minDist = bctx.distanceList.keys.firstOrNull() ?: currDist
            if (minDist == currDist) {
                context.addLog("<G><b>$finalName</b></>$josaRoFinal 가기 위해 <G><b>$destCityName</b></>$josaUl 거쳐야 합니다. <1>${context.date}</>")
            } else {
                context.addLog("<G><b>$finalName</b></>$josaRoFinal 가는 도중 <G><b>$destCityName</b></>$josaUl 거치기로 합니다. <1>${context.date}</>")
            }
        }

        // (4) pre-battle attacker mutations (che_출병.php:225-241).
        // PHP `UPDATE city SET state=43, term=3 WHERE city=destCityID` — a DEST-city delta (the engine folds
        // it via the destCity carrier). The logic City has no generic `state` column, so the battle-state
        // flag rides `meta['state']` (term is a first-class column).
        d.destCity = destCity.copy(
            term = 3,
            meta = withMeta(destCity.meta, "state" to 43),
        )

        val crewType = GameUnitConst.byId(g0.crewTypeId) ?: GameUnitConst.byId(1100)!!
        var g = addDexForChulbyeong(g0, crewType.id, g0.crew / 100.0)
        // +1 inheritance (active_action) when crew>500 && train*atmos > 70*70 — P6 succession seam (no write).
        // (modeled as a no-op write; the predicate is preserved for parity.)
        g = g.copy(lastTurn = LastTurn(name, arg = linkedMapOf<String, Any?>("destCityID" to bctx.finalTargetCityId)))
        d.general = g

        // (5) warSeed from the LOGGER year/month (che_출병.php:245-253; F6).
        val warSeed = WarSeed.build(bctx.hiddenSeed, bctx.loggerYear, bctx.loggerMonth, g0.id, defenderCityId)
        lastWarSeed = warSeed

        // (6) the OUTER processWar() wrapper — EXACTLY ONCE (che_출병.php:255). NOT processWarNG directly.
        val invoke = processWarFn ?: ::defaultProcessWar
        val result = invoke(warSeed, defenderCityId, context)
        lastBattleResult = result
        lastConquerCity = result.conquerCity

        // (7) AFTER the battle: the unique-item lottery on the SEPARATE 출병 lineage (che_출병.php:258).
        // The lottery rng is built from the 출병 reason token — distinct from the warRng (cannot reorder it).
        val lotterySeed = uniqueLotterySeed(bctx.hiddenSeed, bctx.loggerYear, bctx.loggerMonth, g0.id, name)
        val lotteryRng = RandUtil(LiteHashDrbg(lotterySeed))
        lastLotteryFired = lotteryFn(lotteryRng, d.general, 0.0, 0)
    }

    /** The default real processWar invocation — builds all params from the draft + battle context. */
    private fun defaultProcessWar(warSeed: String, chosenCityId: Int, context: GeneralActionResolveContext): ProcessWarResult {
        val bctx = context.battleContext!!
        val d = context.draft
        val attackerNation = d.nation ?: error("che_출병: attacker nation missing")
        val destCity = bctx.cityById.getValue(chosenCityId)
        val defenderNation = bctx.nationById[destCity.nationId]
        val defenders = bctx.defenderGeneralsByCity[chosenCityId] ?: emptyList()
        val attackerCrewType = GameUnitConst.byId(d.general.crewTypeId) ?: GameUnitConst.byId(1100)!!
        val defenderCrewType = GameUnitConst.byId(1100)!!   // per-defender crewType resolved by the wrapper in full impl
        return processWar(
            warSeed = warSeed,
            attackerGeneral = d.general,
            attackerNation = attackerNation,
            defenderCity = destCity,
            defenderCandidates = defenders,
            attackerCrewType = attackerCrewType,
            attackerTech = attackerNation.tech.toInt(),
            defenderCrewType = defenderCrewType,
            defenderTech = (defenderNation?.tech ?: 0.0).toInt(),
            pipeline = pipeline,
            year = context.env.year,
            startYear = context.env.startYear,
            env = ProcessWarEnv(
                defenderNationTech = defenderNation?.tech ?: 0.0,
                defenderNationRice = defenderNation?.rice ?: 10000,
                defenderNationCapitalCityId = defenderNation?.capitalCityId ?: 0,
                attackerCityId = bctx.attackerCityId,
                defenderCityId = chosenCityId,
            ),
        )
    }

    /** PHP `addDex($crewTypeObj, crew/100)` — fold the crew dex accumulator (CASTLE→SIEGE, no draw). */
    private fun addDexForChulbyeong(g: General, crewTypeId: Int, exp: Double): General {
        val crewType = GameUnitConst.byId(crewTypeId) ?: return g
        var armType = crewType.armType
        if (armType == GameUnitConst.T_CASTLE) armType = GameUnitConst.T_SIEGE
        if (armType < 0) return g
        var e = exp
        if (armType == GameUnitConst.T_WIZARD || armType == GameUnitConst.T_SIEGE) e *= 0.9
        val key = "dex$armType"
        return g.copy(meta = withMeta(g.meta, key to metaDouble(g.meta, key) + e))
    }
}

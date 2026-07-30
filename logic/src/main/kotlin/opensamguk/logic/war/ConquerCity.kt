package opensamguk.logic.war

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.log.BattleLogTokens
import opensamguk.logic.util.phpToInt
import opensamguk.logic.util.valueFit
import opensamguk.logic.world.CalcCityDistance
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.world.CityConstVariant
import opensamguk.logic.world.FrontCity
import opensamguk.logic.world.setNationFront

/**
 * AREA B2 — the ConquerCity resolver. Port target = PHP `process_war.php:532-808` (ConquerCity),
 * `:504-530` (getConquerNation / DeleteConflict), `:810-845` (findNextCapital BFS), `func.php:1713-1805`
 * (deleteNation). Research Unit 7, decisions #5/#6/#7.
 *
 * A PURE resolver: it mutates NOTHING in place and performs NO inline DB write. Every effect is surfaced
 * on [ConquerCityResult] as a delta (pre/post [General]/[City]/[Nation] pair, or a deletion marker), which
 * the engine folds into the [ChangeRecorder] (`diffGeneral`/`diffCity`/`diffNation`/`markNationDeleted`).
 * Generals SURVIVE conquest as 재야 → `markGeneralDeleted` is NEVER used; only `markNationDeleted` for the
 * collapse cascade.
 *
 * The conquest sub-stream is the DISTINCT [ConquerCitySeed] lineage (`'ConquerCity'`, 7-arg) — never the
 * battle [WarSeed] stream. The DOUBLE-SEED RESET (the rng re-built fresh with identical args after the
 * OccupyCity event, `:549` then `:589`) is the highest draw-order risk and is reproduced VERBATIM here.
 */
object ConquerCity {

    /**
     * Resolve a city conquest. Builds SEED #1, emits the conquest logs, runs the OccupyCity SLOT, REBUILDS
     * the rng (SEED #2, stream resets to idx 0), then runs the defender `onArbitraryAction` loop (ascending
     * PK) as the FIRST consumer of the reset stream.
     *
     * The collapse / survive branches (BC2) and the city-reset / front recalc (BC3) extend this; BC1 lands
     * the setup + the double-seed + the defender loop.
     */
    fun resolve(
        input: ConquerCityInput,
        arbitraryAction: ConquerArbitraryAction = ConquerArbitraryAction { _, _, _ -> },
        occupyCityHandler: OccupyCityHandler = OccupyCityHandler { },
        rngOverride: RandUtil? = null,
    ): ConquerCityResult {
        val admin = input.admin
        val attacker = input.attacker
        val city = input.defenderCity

        // SEED #1 (process_war.php:549) — drives the conquest side-effects up to the OccupyCity event.
        val seed1Rng = ConquerCitySeed.rng(
            admin.hiddenSeed, admin.year, admin.month, attacker.nationId, attacker.id, city.id,
        )

        // The 공략 성공 / 점령 / 지배 logs (process_war.php:566-577) — NO rng.
        val logs = mutableListOf<ConquerLog>()
        val scoutInvites = mutableListOf<ScoutInviteIntent>()
        val turnSlotWrites = mutableListOf<GeneralTurnSlotIntent>()
        val attackerNationName = input.attackerNationName
        val cityName = input.cityName
        val cityUl = JosaUtil.pick(cityName, "을")
        val cityYi = JosaUtil.pick(cityName, "이")
        val nationYi = JosaUtil.pick(attackerNationName, "이")
        val generalYi = JosaUtil.pick(input.attackerGeneralName, "이")
        val defenderDecoration =
            if (input.isNeutralCapture) "공백지인" else "<D><b>${input.defenderNationName}</b></>의"
        logs += ConquerLog.generalAction(attacker.id, attacker.nationId, "<G><b>$cityName</b></> 공략에 <S>성공</>했습니다.")
        logs += ConquerLog.generalHistory(attacker.id, attacker.nationId, "<G><b>$cityName</b></>$cityUl <S>점령</>")
        logs += ConquerLog.globalAction("<Y>${input.attackerGeneralName}</>$generalYi <G><b>$cityName</b></> 공략에 <S>성공</>했습니다.")
        logs += ConquerLog.globalHistory(
            "<S><b>【지배】</b></><D><b>$attackerNationName</b></>$nationYi <G><b>$cityName</b></>$cityUl 지배했습니다.",
        )
        logs += ConquerLog.nationHistory(
            attacker.nationId,
            "<Y>${input.attackerGeneralName}</>$generalYi $defenderDecoration <G><b>$cityName</b></> $cityUl <S>점령</>",
        )
        logs += ConquerLog.nationHistory(
            city.nationId,
            "<D><b>$attackerNationName</b></>의 <Y>${input.attackerGeneralName}</>에 의해 <G><b>$cityName</b></>$cityYi <O>함락</>",
        )

        // OccupyCity EventTarget SLOT (process_war.php:586-588) — runs on SEED #1; its draws are discarded.
        occupyCityHandler.handle(seed1Rng)

        // SEED #2 REBUILD (process_war.php:589) — IDENTICAL args → the stream RESETS to idx 0. The rngOverride
        // (a scripted/recording stub) substitutes the post-reset stream for draw-order pinning.
        val resetStream: RandUtil = rngOverride ?: ConquerCitySeed.rng(
            admin.hiddenSeed, admin.year, admin.month, attacker.nationId, attacker.id, city.id,
        )
        val rng = DrawCountingRng(resetStream)

        // defender-city general loop (process_war.php:598-603) — the FIRST consumer of the reset rng.
        // Iteration is explicit ascending PK (sortedBy { it.id }), NOT input/query order (PR-4).
        for (defender in input.defenderCityGenerals.sortedBy { it.id }) {
            arbitraryAction.onArbitraryAction(defender, rng, attacker)
        }

        // BC2 — COLLAPSE (cityCount==1) vs SURVIVE/capital-move. The same `rng` continues the SEED #2 stream.
        val generalDeltas = mutableListOf<GeneralDelta>()
        val nationDeltas = mutableListOf<NationDelta>()
        val cityDeltas = mutableListOf<CityDelta>()
        var deletedNationId: Int? = null
        val collapseGeneralOrder = mutableListOf<Int>()

        val isCollapse = !input.isNeutralCapture && input.defenderNationCityCount == 1
        if (isCollapse) {
            resolveCollapse(
                input,
                rng,
                logs,
                generalDeltas,
                nationDeltas,
                collapseGeneralOrder,
                scoutInvites,
                turnSlotWrites,
            )
            deletedNationId = input.defenderNation!!.id
        } else if (!input.isNeutralCapture) {
            resolveSurvive(input, logs, generalDeltas, nationDeltas, cityDeltas)
        }

        // BC3 — winner + city reset + front recalc (process_war.php:757-807). UNCONDITIONAL after the
        // collapse/survive if-else (runs for BOTH branches). NO rng.
        val frontResults = mutableListOf<NationFrontDelta>()
        val conquerNationId = resolveWinnerAndReset(
            input, logs, generalDeltas, cityDeltas, frontResults,
        )

        return ConquerCityResult(
            conquerLogs = logs,
            collapseLoopDraws = rng.draws,
            firstCollapseDraw = rng.firstBool,
            generalDeltas = generalDeltas,
            nationDeltas = nationDeltas,
            cityDeltas = cityDeltas,
            deletedNationId = deletedNationId,
            conquerNationId = conquerNationId,
            frontResults = frontResults,
            deletedGeneralIds = emptyList(), // generals SURVIVE as 재야 — markGeneralDeleted is NEVER used.
            collapseGeneralOrder = collapseGeneralOrder,
            scoutInvites = scoutInvites,
            turnSlotWrites = turnSlotWrites,
            destroyNationEvent = isCollapse,
        )
    }

    /**
     * COLLAPSE (process_war.php:607-700). `deleteNation(lord,false)` cascade (NO rng) → the PER old-general
     * draw sub-stream (`:627-664`) → winner reward (`:667-680`). oldNationGenerals = other generals ascending
     * PK (func.php:1732 SELECT has NO ORDER BY → pin the sort, PR-4) + the lord appended LAST.
     */
    private fun resolveCollapse(
        input: ConquerCityInput,
        rng: RandUtil,
        logs: MutableList<ConquerLog>,
        generalDeltas: MutableList<GeneralDelta>,
        nationDeltas: MutableList<NationDelta>,
        collapseGeneralOrder: MutableList<Int>,
        scoutInvites: MutableList<ScoutInviteIntent>,
        turnSlotWrites: MutableList<GeneralTurnSlotIntent>,
    ) {
        val admin = input.admin
        val loseNation = input.defenderNation!!
        // deleteNation order: other generals (no != lord) ascending PK + the lord LAST (func.php:1735).
        val lord = input.defenderNationGenerals.firstOrNull { it.officerLevel == 12 }
            ?: error("ConquerCity collapse: no lord (officer_level 12) in the defender nation")
        val others = input.defenderNationGenerals.filter { it.id != lord.id }.sortedBy { it.id }
        val oldNationGenerals = others + lord
        val defenderNationUl = JosaUtil.pick(input.defenderNationName, "을")
        logs += ConquerLog.nationHistory(
            input.attacker.nationId,
            "<D><b>${input.defenderNationName}</b></>$defenderNationUl 정복",
        )

        var loseGeneralGold = 0
        var loseGeneralRice = 0
        for (oldGeneral in oldNationGenerals) {
            collapseGeneralOrder.add(oldGeneral.id)

            // (1)(2) the two gold/rice loss draws — Util::toInt (truncate toward zero) of value*nextRange.
            val loseGold = phpToInt(oldGeneral.gold * rng.nextRange(0.2, 0.5))
            val loseRice = phpToInt(oldGeneral.rice * rng.nextRange(0.2, 0.5))
            loseGeneralGold += loseGold
            loseGeneralRice += loseRice

            // (3)(4) exp/ded decay via the suppress-flagged addExperience/addDedication (NO onCalcStat fold;
            // value = -exp*0.1 / -ded*0.5 — the PHP path wins, NOT the TS inline *0.9).
            val newExp = oldGeneral.experience + (-oldGeneral.experience * 0.1)
            val newDed = oldGeneral.dedication + (-oldGeneral.dedication * 0.5)

            // The general SURVIVES as 재야 (the markNationDeleted cascade neutralizes nation/officer fields):
            // belong/troop/officer_level/officer_city/nation reset to the 재야 baseline (func.php:1753-1759).
            val post = oldGeneral.copy(
                gold = oldGeneral.gold - loseGold,
                rice = oldGeneral.rice - loseRice,
                experience = newExp,
                dedication = newDed,
                officerLevel = 0,
                officerCity = 0,
                nationId = 0,
            )
            generalDeltas.add(GeneralDelta(oldGeneral, post))
            logs += ConquerLog.generalAction(
                oldGeneral.id,
                0,
                "도주하며 금<C>$loseGold</> 쌀<C>$loseRice</>을 분실했습니다.",
            )

            // (5) scout (process_war.php:644) — CONDITIONAL, short-circuit AND on join_mode != 'onlyRandom'.
            if (admin.joinMode != "onlyRandom" && rng.nextBool(0.5)) {
                scoutInvites += ScoutInviteIntent(input.attacker.id, oldGeneral.id)
            }

            // (6) NPC join (process_war.php:653-661) — CONDITIONAL.
            val npcType = oldGeneral.npcType
            if (admin.joinMode != "onlyRandom" && npcType in 2..8 && npcType != 5 &&
                rng.nextBool(GameConst.joinRuinedNPCProp)
            ) {
                val joinTurn = rng.nextRangeInt(0, 12)
                for (turnIdx in 0 until joinTurn) {
                    turnSlotWrites += GeneralTurnSlotIntent(oldGeneral.id, turnIdx, "che_견문", "{}", "견문")
                }
                turnSlotWrites += GeneralTurnSlotIntent(
                    oldGeneral.id,
                    joinTurn,
                    "che_임관",
                    """{"destNationID":${input.attacker.nationId}}""",
                    "임관",
                )
            }
        }

        // Winner reward (process_war.php:667-680): basegold/baserice-excess of the captured nation + the
        // generals' total loss, HALVED via intdiv, added to the attacker nation.
        var loseNationGold = valueFit((loseNation.gold - GameConst.basegold).toDouble(), 0.0).toInt()
        var loseNationRice = valueFit((loseNation.rice - GameConst.baserice).toDouble(), 0.0).toInt()
        loseNationGold += loseGeneralGold
        loseNationRice += loseGeneralRice
        loseNationGold /= 2 // intdiv (non-negative operands → floor == trunc)
        loseNationRice /= 2

        val attackerNation = input.attackerNation
        if (attackerNation != null) {
            val post = attackerNation.copy(
                gold = attackerNation.gold + loseNationGold,
                rice = attackerNation.rice + loseNationRice,
            )
            nationDeltas.add(NationDelta(attackerNation, post))
        }
        val resourceLog =
            "<D><b>${input.defenderNationName}</b></> 정복으로 금<C>${formatInteger(loseNationGold)}</> " +
                "쌀<C>${formatInteger(loseNationRice)}</>을 획득했습니다."
        for (chiefId in input.attackerNationChiefIds) {
            logs += ConquerLog.generalAction(chiefId, input.attacker.nationId, resourceLog)
        }

        // DestroyNation EventTarget SLOT (process_war.php:700) — a no-op in P4 (the OpenNationBetting handler
        // is P6). The slot + its position AFTER the winner reward are preserved so P6 betting attaches
        // without shifting the P4 collapse side-effect stream.
        // (no draw, no delta in P4)
    }

    /**
     * SURVIVE / capital-move (process_war.php:703-755). NO rng. Demote the lost city's officer_city governors
     * to 재야 (officer_level=1, officer_city=0); on a capital loss move the capital to findNextCapital (BC3 —
     * not yet wired) + halve nation gold/rice. Generals SURVIVE → no markGeneralDeleted.
     */
    private fun resolveSurvive(
        input: ConquerCityInput,
        logs: MutableList<ConquerLog>,
        generalDeltas: MutableList<GeneralDelta>,
        nationDeltas: MutableList<NationDelta>,
        cityDeltas: MutableList<CityDelta>,
    ) {
        val cityId = input.defenderCity.id
        val defNation = input.defenderNation!!
        // 태수/군사/종사 → 일반 (process_war.php:705-708): officer_level=1, officer_city=0 WHERE officer_city=cityID.
        for (g in input.defenderNationGenerals) {
            if (g.officerCity == cityId) {
                generalDeltas.add(GeneralDelta(g, g.copy(officerLevel = 1, officerCity = 0)))
            }
        }

        // capital-move (process_war.php:711-748) — only when the LOST city was the defender's capital.
        if (defNation.capitalCityId != cityId) return

        // findNextCapital over the defender's OTHER owned cities (BFS-ring max-pop, LAST-on-tie; BC3).
        val ownedPop = input.allCitiesForBfs
            .filter { it.nationId == defNation.id && it.id != cityId }
            .associate { it.id to it.population }
        val minCity = findNextCapital(cityId, ownedPop)

        logs += ConquerLog.globalHistory(
            BattleLogTokens.emergencyCapitalMoveHistory(input.defenderNationName, input.minCityNameOf(minCity)),
        )
        val minCityName = input.minCityNameOf(minCity)
        val minCityRo = JosaUtil.pick(minCityName, "로")
        for (g in input.defenderNationGenerals) {
            logs += ConquerLog.generalAction(
                g.id,
                g.nationId,
                "수도가 함락되어 <G><b>$minCityName</b></>$minCityRo <M>긴급천도</>합니다.",
            )
            if (g.officerLevel >= 5) {
                logs += ConquerLog.generalAction(
                    g.id,
                    g.nationId,
                    "수뇌는 <G><b>$minCityName</b></>$minCityRo 집합되었습니다.",
                )
            }
        }

        // 천도: nation capital=minCity, gold×0.5, rice×0.5 (process_war.php:734-738).
        val movedNation = defNation.copy(
            capitalCityId = minCity,
            gold = (defNation.gold * 0.5).toInt(),
            rice = (defNation.rice * 0.5).toInt(),
        )
        nationDeltas.add(NationDelta(defNation, movedNation))

        // minCity → supply city (process_war.php:740-742); chiefs move to minCity (officer_level>=5,
        // process_war.php:744-746); nation generals atmos ×0.8 (process_war.php:748-750).
        for (c in input.allCitiesForBfs) {
            if (c.id == minCity && c.supplyState == 0) {
                cityDeltas.add(CityDelta(c, c.copy(supplyState = 1)))
            }
        }
        for (g in input.defenderNationGenerals) {
            var post = g
            if (g.officerLevel >= 5) post = post.copy(cityId = minCity)
            post = post.copy(atmos = post.atmos * 0.8)
            if (post != g) mergeGeneralDelta(generalDeltas, g, post)
        }
    }

    /**
     * Winner (`process_war.php:757-775`) + city reset (`:777-795`) + front recalc (`:798-807`). UNCONDITIONAL
     * after collapse/survive. Returns the conquerNation id (`getConquerNation = array_key_first(conflict)`).
     */
    private fun resolveWinnerAndReset(
        input: ConquerCityInput,
        logs: MutableList<ConquerLog>,
        generalDeltas: MutableList<GeneralDelta>,
        cityDeltas: MutableList<CityDelta>,
        frontResults: MutableList<NationFrontDelta>,
    ): Int {
        val city = input.defenderCity
        val attacker = input.attacker

        // getConquerNation (process_war.php:526-530) = array_key_first(Json::decode(city.conflict)).
        val conflict = ConflictMap.decode(city.conflict)
        val conquerNation = conflict.getConquerNation() ?: 0

        // winner tie-break (process_war.php:757-775).
        if (conquerNation == attacker.nationId) {
            // attacker general moves to the city (process_war.php:760-762).
            mergeGeneralDelta(generalDeltas, attacker, attacker.copy(cityId = city.id))
        } else {
            // 분쟁협상 / 양도 logs only (process_war.php:764-774) — no general move.
            val conquerNationName = input.conquerNationNameOf(conquerNation)
            val cityUl = JosaUtil.pick(input.cityName, "을")
            logs += ConquerLog.globalHistory(BattleLogTokens.conflictNegotiationHistory(conquerNationName, input.cityName))
            logs += ConquerLog.nationHistory(
                conquerNation,
                "<D><b>${input.attackerNationName}</b></>에서 <G><b>${input.cityName}</b></>$cityUl <S>양도</> 받음",
            )
            logs += ConquerLog.nationHistory(
                attacker.nationId,
                "<G><b>${input.cityName}</b></>$cityUl <D><b>$conquerNationName</b></>에 <Y>양도</>",
            )
        }

        // city reset (process_war.php:777-795). agri/comm/secu ×0.7 (int-cast); supply/term/conflict/
        // officer_set reset; nation=conquerNation; def/wall per level.
        var resetCity = city.copy(
            supplyState = 1,
            term = 0,
            conflict = "{}",
            agriculture = (city.agriculture * 0.7).toInt(),
            commerce = (city.commerce * 0.7).toInt(),
            security = (city.security * 0.7).toInt(),
            nationId = conquerNation,
            officerSet = 0,
        )
        resetCity = if (city.level > 3) {
            resetCity.copy(defense = GameConst.defaultCityWall, wall = GameConst.defaultCityWall)
        } else {
            resetCity.copy(defense = city.defenseMax / 2, wall = city.wallMax / 2)
        }
        cityDeltas.add(CityDelta(city, resetCity))

        // front recalc (process_war.php:798-807): nearNationsID = distinct nation of path-neighbors (nation!=0)
        // ∪ {conquerNation}, array_unique → SetNationFront per nation.
        val pathNeighborIds: Set<Int> =
            (input.cityConstVariant.byId(city.id)?.path?.keys ?: emptySet()) + city.id
        val frontCities = input.allCitiesForBfs.map { FrontCity(it.id, it.nationId, it.frontState) }
        val nearNations = LinkedHashSet<Int>()
        for (c in frontCities) {
            if (c.nationId != 0 && c.id in pathNeighborIds) nearNations.add(c.nationId)
        }
        nearNations.add(conquerNation)
        for (nationId in nearNations) {
            if (nationId == 0) continue
            val res = setNationFront(nationId, frontCities, input.diplomacyForFront, input.cityConstVariant)
            frontResults.add(NationFrontDelta(res.nationId, res.fronts))
        }
        return conquerNation
    }

    /** Merge a new General delta into the list — folds onto an existing pre/post for the same general id. */
    private fun mergeGeneralDelta(deltas: MutableList<GeneralDelta>, pre: General, post: General) {
        val idx = deltas.indexOfFirst { it.post.id == post.id }
        if (idx >= 0) deltas[idx] = GeneralDelta(deltas[idx].pre, post)
        else deltas.add(GeneralDelta(pre, post))
    }

    private fun formatInteger(value: Int): String = "%,d".format(value)

    /**
     * findNextCapital (`process_war.php:810-845`): BFS distance-rings over [CalcCityDistance] outward; in the
     * FIRST ring containing an owned city pick MAX pop, and on a pop TIE the LAST max-pop city in BFS
     * ring-iteration order wins (the `if($cityPop < $maxCityPop) continue;` overwrite-on-equality; decision
     * #7 — NOT Euclidean Math.hypot, NOT first-max).
     *
     * @param ownedCityPop the defender's OTHER owned cities (city!=capital) → pop.
     */
    fun findNextCapital(capitalId: Int, ownedCityPop: Map<Int, Int>): Int {
        val rings = CalcCityDistance.searchDistanceRings(capitalId, 99)
        for ((_, ringCities) in rings) {
            var maxPop = 0
            var minCity = 0
            for (cityId in ringCities) {
                val pop = ownedCityPop[cityId] ?: continue
                if (pop < maxPop) continue            // `<` strict → equality OVERWRITES → LAST max wins.
                minCity = cityId
                maxPop = pop
            }
            if (minCity != 0) return minCity
        }
        throw IllegalStateException("도시가 남지 않았는데 긴천을 시도하고 있습니다")
    }
}

/** The `$admin`-derived env the resolver reads (seed inputs + the 임관 join-mode gate). */
data class ConquerAdmin(
    val hiddenSeed: String,
    val year: Int,
    val month: Int,
    /** `$admin['join_mode']` — `'onlyRandom'` suppresses the scout + NPC-join draws (decision predicates). */
    val joinMode: String,
)

/**
 * The OccupyCity EventTarget handler SLOT (`process_war.php:586-588`). A no-op in P4 (no city-conquest
 * events registered), but the slot + its position BEFORE the SEED #2 rebuild are preserved so the collapse
 * draws don't shift when P3 event triggers are added (OQ #12). It runs on the SEED #1 rng; any draws it
 * makes are DISCARDED by the immediately-following double-seed reset.
 */
fun interface OccupyCityHandler {
    fun handle(seed1Rng: RandUtil)
}

/**
 * The per-defender `onArbitraryAction(self, rng, 'ConquerCity', null, ['attacker'=>general])` seam
 * (`process_war.php:599-602`). In P4 no ConquerCity-target trigger reacts, so the canonical implementation
 * draws ZERO — but the loop (and this seam) MUST exist as the structural first-consumer of the reset rng so
 * collapse draws stay pinned when triggers are added.
 */
fun interface ConquerArbitraryAction {
    fun onArbitraryAction(defender: General, rng: RandUtil, attacker: General)
}

/**
 * The resolver input — the conquest context (the PHP `ConquerCity` args + the few queries it makes).
 * All snapshots are immutable pre-state; the resolver returns the post-state on [ConquerCityResult].
 */
data class ConquerCityInput(
    val admin: ConquerAdmin,
    /** The attacking general (`$general`) — the conqueror. */
    val attacker: General,
    /** The conquered city (`$city`). */
    val defenderCity: City,
    /** The defender's nation (`getNationStaticInfo($defenderNationID)`), or null for a 공백지 (neutral) capture. */
    val defenderNation: Nation?,
    /** The attacker's nation (`$attackerNationID` row) — the winner-reward gold/rice sink (BC2). */
    val attackerNation: Nation? = null,
    /** The generals stationed in the conquered city (`$defenderCityGeneralList`) — the onArbitraryAction loop set. */
    val defenderCityGenerals: List<General>,
    /** `SELECT count(city) FROM city WHERE nation=defenderNationID` — ==1 ⇒ collapse (BC2). */
    val defenderNationCityCount: Int,
    /** All generals of the defender nation EXCLUDING the city ones — the deleteNation cascade set (BC2). */
    val defenderNationGenerals: List<General>,
    /** All cities (for the findNextCapital BFS + the front recalc neighbor scan, BC3). */
    val allCitiesForBfs: List<City>,
    /** Settled (post-Q9) diplomacy rows the front recompute reads (`SetNationFront`, BC3). */
    val diplomacyForFront: List<Diplomacy> = emptyList(),
    /** The active per-map CityConst variant (path adjacency for the front recalc + findNextCapital). */
    val cityConstVariant: CityConstVariant = CityConstRegistry.of("che"),
    /** Display name of the attacker's nation (`$attackerNationName`). */
    val attackerNationName: String = "",
    val attackerGeneralName: String = "",
    val attackerNationChiefIds: List<Int> = emptyList(),
    /** Display name of the defender's nation (`$defenderNationName`) — the 긴급천도 / 멸망 logs. */
    val defenderNationName: String = "",
    /** Display name of the conquered city (`$cityName`). */
    val cityName: String = "",
    /** Resolved nation-id → display-name (for the 양도 / conquerNation logs). */
    val nationNames: Map<Int, String> = emptyMap(),
) {
    val isNeutralCapture: Boolean get() = defenderNation == null || defenderCity.nationId == 0

    fun conquerNationNameOf(nationId: Int): String = nationNames[nationId] ?: ""

    fun minCityNameOf(cityId: Int): String =
        cityConstVariant.byId(cityId)?.name ?: CityConst.byId(cityId)?.name ?: ""
}

/**
 * The conquest outcome as flush deltas — the engine folds these into the [ChangeRecorder]. BC1 lands the
 * setup surface (logs + the double-seed draw observation); BC2/BC3 add the General/City/Nation deltas, the
 * deleted-nation marker, and the city-reset / front results.
 */
data class ConquerCityResult(
    /** The 공략 성공 / 지배 / (later) 정복·긴급천도·양도 logs, in emission order. */
    val conquerLogs: List<ConquerLog>,
    /** The total rng draws made off the SEED #2 reset stream (defender loop + collapse sub-stream). */
    val collapseLoopDraws: Int,
    /** The FIRST nextBool draw off the RESET (SEED #2) stream — proves the double-seed reset to idx 0. */
    val firstCollapseDraw: Boolean? = null,
    /** General pre/post deltas (collapse loss + 재야 demote; survive governor demote; BC3 attacker move). */
    val generalDeltas: List<GeneralDelta> = emptyList(),
    /** Nation pre/post deltas (winner reward; BC3 capital-move / city ownership change). */
    val nationDeltas: List<NationDelta> = emptyList(),
    /** City pre/post deltas (BC3 city reset + capital-move supply + front recalc). */
    val cityDeltas: List<CityDelta> = emptyList(),
    /** The tombstoned defender nation id (collapse only) — the engine calls `markNationDeleted`. Null = survive. */
    val deletedNationId: Int? = null,
    /** The winner nation = `getConquerNation(city) = array_key_first(conflict)` (process_war.php:757). */
    val conquerNationId: Int = 0,
    /** Per-nation front recompute results (SetNationFront over path-neighbor nations ∪ conquerNation, BC3). */
    val frontResults: List<NationFrontDelta> = emptyList(),
    /** ALWAYS empty — generals SURVIVE conquest as 재야 (markGeneralDeleted is NEVER used by ConquerCity). */
    val deletedGeneralIds: List<Int> = emptyList(),
    /** The collapse oldNationGenerals iteration order (others asc PK + lord LAST) — the draw-stream pin. */
    val collapseGeneralOrder: List<Int> = emptyList(),
    val scoutInvites: List<ScoutInviteIntent> = emptyList(),
    val turnSlotWrites: List<GeneralTurnSlotIntent> = emptyList(),
    val destroyNationEvent: Boolean = false,
)

enum class ConquerLogScope(val wireValue: String) {
    GENERAL("general"),
    NATION("nation"),
    GLOBAL("global"),
}

enum class ConquerLogCategory(val wireValue: String) {
    ACTION("action"),
    HISTORY("history"),
}

data class ConquerLog(
    val scope: ConquerLogScope,
    val category: ConquerLogCategory,
    val text: String,
    val generalId: Int? = null,
    val nationId: Int? = null,
) {
    operator fun contains(value: CharSequence): Boolean = text.contains(value)

    companion object {
        fun generalAction(generalId: Int, nationId: Int, text: String) =
            ConquerLog(ConquerLogScope.GENERAL, ConquerLogCategory.ACTION, text, generalId, nationId)

        fun generalHistory(generalId: Int, nationId: Int, text: String) =
            ConquerLog(ConquerLogScope.GENERAL, ConquerLogCategory.HISTORY, text, generalId, nationId)

        fun nationHistory(nationId: Int, text: String) =
            ConquerLog(ConquerLogScope.NATION, ConquerLogCategory.HISTORY, text, nationId = nationId)

        fun globalAction(text: String) =
            ConquerLog(ConquerLogScope.GLOBAL, ConquerLogCategory.ACTION, text)

        fun globalHistory(text: String) =
            ConquerLog(ConquerLogScope.GLOBAL, ConquerLogCategory.HISTORY, text)
    }
}

data class ScoutInviteIntent(val sourceGeneralId: Int, val destinationGeneralId: Int)

data class GeneralTurnSlotIntent(
    val generalId: Int,
    val turnIdx: Int,
    val actionCode: String,
    val argJson: String,
    val brief: String,
)

/** A per-nation front recompute result (`SetNationFront`) — the engine folds each city front as a diffCity. */
data class NationFrontDelta(val nationId: Int, val fronts: Map<Int, Int>)

/** A pre/post [General] delta — the engine folds it via `ChangeRecorder.diffGeneral(pre, post)`. */
data class GeneralDelta(val pre: General, val post: General)

/** A pre/post [Nation] delta — the engine folds it via `ChangeRecorder.diffNation(pre, post)`. */
data class NationDelta(val pre: Nation, val post: Nation)

/** A pre/post [City] delta — the engine folds it via `ChangeRecorder.diffCity(pre, post)`. */
data class CityDelta(val pre: City, val post: City)

/**
 * A thin draw-counting wrapper over a [RandUtil] — pins how many draws the structural loop slot makes.
 * The base DRBG (`"00"`) is never consumed: every draw method is overridden to delegate to [inner].
 */
internal class DrawCountingRng(private val inner: RandUtil) : RandUtil(LiteHashDrbg("00")) {
    var draws: Int = 0
        private set
    var firstBool: Boolean? = null
        private set

    override fun nextBool(prob: Double): Boolean {
        val v = inner.nextBool(prob)
        if (firstBool == null) firstBool = v
        draws++
        return v
    }

    override fun nextRange(min: Double, max: Double): Double {
        val v = inner.nextRange(min, max)
        draws++
        return v
    }

    override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int {
        val v = inner.nextRangeInt(minInclusive, maxInclusive)
        draws++
        return v
    }

    override fun <T> choice(items: List<T>): T {
        val v = inner.choice(items)
        draws++
        return v
    }
}

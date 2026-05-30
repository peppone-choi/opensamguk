package opensamguk.logic.war

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.log.BattleLogTokens
import opensamguk.logic.util.phpToInt
import opensamguk.logic.util.valueFit

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
        val logs = mutableListOf<String>()
        val attackerNationName = input.attackerNationName
        val cityName = input.cityName
        logs.add("<G><b>$cityName</b></> 공략에 <S>성공</>했습니다.")
        logs.add(BattleLogTokens.conquerHistory(attackerNationName, cityName))

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
            resolveCollapse(input, rng, logs, generalDeltas, nationDeltas, collapseGeneralOrder)
            deletedNationId = input.defenderNation!!.id
        } else if (!input.isNeutralCapture) {
            resolveSurvive(input, logs, generalDeltas, nationDeltas, cityDeltas)
        }

        return ConquerCityResult(
            conquerLogs = logs,
            collapseLoopDraws = rng.draws,
            firstCollapseDraw = rng.firstBool,
            generalDeltas = generalDeltas,
            nationDeltas = nationDeltas,
            cityDeltas = cityDeltas,
            deletedNationId = deletedNationId,
            deletedGeneralIds = emptyList(), // generals SURVIVE as 재야 — markGeneralDeleted is NEVER used.
            collapseGeneralOrder = collapseGeneralOrder,
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
        logs: MutableList<String>,
        generalDeltas: MutableList<GeneralDelta>,
        nationDeltas: MutableList<NationDelta>,
        collapseGeneralOrder: MutableList<Int>,
    ) {
        val admin = input.admin
        val loseNation = input.defenderNation!!
        // deleteNation order: other generals (no != lord) ascending PK + the lord LAST (func.php:1735).
        val lord = input.defenderNationGenerals.maxByOrNull { it.officerLevel }
            ?: error("ConquerCity collapse: no lord (officer_level 12) in the defender nation")
        val others = input.defenderNationGenerals.filter { it.id != lord.id }.sortedBy { it.id }
        val oldNationGenerals = others + lord

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
            logs.add("도주하며 금<C>$loseGold</> 쌀<C>$loseRice</>을 분실했습니다.")

            // (5) scout (process_war.php:644) — CONDITIONAL, short-circuit AND on join_mode != 'onlyRandom'.
            if (admin.joinMode != "onlyRandom" && rng.nextBool(0.5)) {
                // ScoutMessage::buildScoutMessage — a P6 messaging seam; no rng, no delta in P4.
            }

            // (6) NPC join (process_war.php:653-661) — CONDITIONAL.
            val npcType = oldGeneral.npcType
            if (admin.joinMode != "onlyRandom" && npcType in 2..8 && npcType != 5 &&
                rng.nextBool(GameConst.joinRuinedNPCProp)
            ) {
                @Suppress("UNUSED_VARIABLE")
                val joinTurn = rng.nextRangeInt(0, 12) // _setGeneralCommand 임관/견문 — a P6 command-queue seam.
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
        logs: MutableList<String>,
        generalDeltas: MutableList<GeneralDelta>,
        nationDeltas: MutableList<NationDelta>,
        cityDeltas: MutableList<CityDelta>,
    ) {
        val cityId = input.defenderCity.id
        // 태수/군사/종사 → 일반 (process_war.php:705-708): officer_level=1, officer_city=0 WHERE officer_city=cityID.
        for (g in input.defenderNationGenerals) {
            if (g.officerCity == cityId) {
                generalDeltas.add(GeneralDelta(g, g.copy(officerLevel = 1, officerCity = 0)))
            }
        }
        // The capital-move (process_war.php:711-748) is wired in BC3 (findNextCapital + chiefs move + atmos×0.8).
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
    /** Display name of the attacker's nation (`$attackerNationName`). */
    val attackerNationName: String = "",
    /** Display name of the conquered city (`$cityName`). */
    val cityName: String = "",
) {
    val isNeutralCapture: Boolean get() = defenderNation == null || defenderCity.nationId == 0
}

/**
 * The conquest outcome as flush deltas — the engine folds these into the [ChangeRecorder]. BC1 lands the
 * setup surface (logs + the double-seed draw observation); BC2/BC3 add the General/City/Nation deltas, the
 * deleted-nation marker, and the city-reset / front results.
 */
data class ConquerCityResult(
    /** The 공략 성공 / 지배 / (later) 정복·긴급천도·양도 logs, in emission order. */
    val conquerLogs: List<String>,
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
    /** ALWAYS empty — generals SURVIVE conquest as 재야 (markGeneralDeleted is NEVER used by ConquerCity). */
    val deletedGeneralIds: List<Int> = emptyList(),
    /** The collapse oldNationGenerals iteration order (others asc PK + lord LAST) — the draw-stream pin. */
    val collapseGeneralOrder: List<Int> = emptyList(),
)

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

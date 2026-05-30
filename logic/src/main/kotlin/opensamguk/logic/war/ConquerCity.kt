package opensamguk.logic.war

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.log.BattleLogTokens

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
    ): ConquerCityResult {
        val admin = input.admin
        val attacker = input.attacker
        val city = input.defenderCity

        // SEED #1 (process_war.php:549) — drives the conquest side-effects up to the OccupyCity event.
        @Suppress("UNUSED_VARIABLE")
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

        // SEED #2 REBUILD (process_war.php:589) — IDENTICAL args → the stream RESETS to idx 0.
        val rng = ConquerCitySeed.rng(
            admin.hiddenSeed, admin.year, admin.month, attacker.nationId, attacker.id, city.id,
        )

        // defender-city general loop (process_war.php:598-603) — the FIRST consumer of the reset rng.
        // Iteration is explicit ascending PK (sortedBy { it.id }), NOT input/query order (PR-4).
        val countingRng = DrawCountingRng(rng)
        for (defender in input.defenderCityGenerals.sortedBy { it.id }) {
            arbitraryAction.onArbitraryAction(defender, countingRng, attacker)
        }

        return ConquerCityResult(
            conquerLogs = logs,
            collapseLoopDraws = countingRng.draws,
            firstCollapseDraw = countingRng.firstBool,
        )
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
    /** The total rng draws the defender onArbitraryAction loop made (0 when no ConquerCity trigger reacts). */
    val collapseLoopDraws: Int,
    /** The FIRST nextBool draw off the RESET (SEED #2) stream — proves the double-seed reset to idx 0. */
    val firstCollapseDraw: Boolean? = null,
)

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

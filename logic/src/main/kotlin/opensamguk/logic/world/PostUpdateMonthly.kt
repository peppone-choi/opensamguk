package opensamguk.logic.world

import opensamguk.common.rng.RandUtil
import opensamguk.logic.log.HistoryTokens
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.valueFit
import kotlin.math.sqrt

/**
 * P3 / AREA B2 / `postUpdateMonthly` — the Q1-Q17 ordered Month-side settlement set.
 *
 * Faithful port of PHP `func_gamerule.php:260-442` (+ `:445-467` checkWander, `:436-441` SetNationFront).
 * **Runs at L10 of the [opensamguk.logic.tick.MonthlyPipeline], AFTER the Month event batch — the side-effect
 * ORDER + the monthlyRng draw order are themselves parity targets** (the G1 log-sequence gate). This file
 * grows across B2's three tasks: POST1 (Q1-Q4 power aggregate + jitter), POST2 (Q5-Q10 diplomacy), POST3
 * (Q11-Q17 tail + the Q4→Q11→Q15→Q16 monthlyRng draw order).
 *
 * The `$monthlyRng` is the ONLY RNG consumer here (the Month batch self-seeds its own DRBGs). The exact
 * consume order across the whole function is **Q4 → Q11 → Q15 → Q16, a single instance** (`:322,425,432,434`):
 * any reordering corrupts the byte-match, so the pure core records each draw into an ordered draw log.
 *
 * --- POST1 (Q1-Q4) ---
 *
 *   Q1  $globalLogger = new ActionLogger(0, 0, year, month)                          // :266
 *   Q2  group city.name by nation (DB row order)                                     // :268-275
 *   Q3  the ONE big power-aggregate SELECT, keyed by nation (`func_gamerule.php:288-310`):
 *       power = round(
 *         ( round(((A.gold + A.rice) + SUM(gold+rice over generals)) / 100)
 *           + A.tech
 *           + if(A.level==0, 0, round( SUM(pop)*SUM(pop+agri+comm+secu+wall+def)
 *                                      / SUM(*_max) / 100 ) over supply=1 cities)
 *           + SUM over generals of:
 *               (ra.value+1000)/(rb.value+1000) * (npc<2?1.2:1) * (leadership>=40?leadership:0) * 2
 *               + (sqrt(intel*strength)*2 + leadership/2) / 2
 *           + round( SUM(dex1+dex2+dex3+dex4+dex5 over generals) / 1000 )
 *           + round( SUM(experience+dedication over generals) / 100 )
 *         ) / 10 )
 *       totalCrew = SUM(crew over generals)
 *       (ra/rb = the killcrew_person/deathcrew_person `rank_data` rows, LEFT JOIN → value 0 when absent.)
 *   Q4  per-nation IN Q3 QUERY ORDER:  power = round(power * rng.nextRange(0.95, 1.05))  // :322 — the FIRST
 *       monthlyRng draw, EXACTLY ONE per nation; maxPower/maxCrew/maxCities tracked into nation_env KV;
 *       nation.power written.                                                          // :323-333
 *
 * **PURE / in-memory** — no IO. The daemon supplies the SELECT-shaped inputs; the hook applies the result.
 */

/** A `rank_data`-joined general row feeding the Q3 power aggregate (`func_gamerule.php:299-305`). */
data class PowerGeneral(
    val id: Int,
    val gold: Int,
    val rice: Int,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val npc: Int,
    /** SUM(dex1+dex2+dex3+dex4+dex5) for this general (pre-divided; Q3 sums then rounds /1000). */
    val dexSum: Int,
    val experience: Int,
    val dedication: Int,
    val crew: Int,
    /** `rank_data` value for type='killcrew_person' (LEFT JOIN → 0 when absent → (0+1000) numerator). */
    val killcrewPersonRankValue: Int = 0,
    /** `rank_data` value for type='deathcrew_person' (LEFT JOIN → 0 when absent → (0+1000) denominator). */
    val deathcrewPersonRankValue: Int = 0,
)

/** A supply=1 city row feeding the Q3 internal-development term (`func_gamerule.php:296-297`). */
data class PowerCity(
    /** SUM(pop) contributor. */
    val pop: Int,
    /** SUM(pop+agri+comm+secu+wall+def). */
    val sumStat: Int,
    /** SUM(pop_max+agri_max+comm_max+secu_max+wall_max+def_max). */
    val sumMax: Int,
    val supply: Boolean,
)

/** A nation's Q3 power-aggregate input (the `nation A` row + its joined generals/cities). */
data class PostNationPowerInput(
    val nationId: Int,
    val gennum: Int,
    val gold: Int,
    val rice: Int,
    val tech: Int,
    val level: Int,
    val generals: List<PowerGeneral> = emptyList(),
    val cities: List<PowerCity> = emptyList(),
    /** Q2 city.name list grouped by nation (DB row order) — feeds maxCities. */
    val cityNames: List<String> = emptyList(),
)

/** The `nation_env` `max_power` KV value (Q4 tracking; `func_gamerule.php:318-328`). */
data class PowerKv(
    val maxPower: Int = 0,
    val maxCrew: Int = 0,
    val maxCities: List<String> = emptyList(),
)

/** Per-nation Q3/Q4 result: raw (pre-jitter) power, jittered power, totalCrew, gennum, updated KV. */
data class PostNationPowerResult(
    val nationId: Int,
    val gennum: Int,
    val rawPower: Int,
    val power: Int,
    val totalCrew: Int,
    val maxPowerKv: PowerKv,
)

/** POST1 (Q1-Q4) result: the per-nation power rows (in query order) + the ordered RNG draw log. */
data class PostUpdateMonthlyPowerResult(
    val nations: List<PostNationPowerResult>,
    val rngDrawOrder: List<String>,
)

/**
 * The pure Q3 power aggregate for one nation (pre-jitter), transcribed verbatim from the SELECT at
 * `func_gamerule.php:288-310`. `round` = [phpRound] (PHP `round`, half-away-from-zero).
 */
internal fun nationRawPower(n: PostNationPowerInput): Int {
    // round( ((A.gold+A.rice) + SUM(gold+rice over generals)) / 100 )
    val genGoldRice = n.generals.sumOf { it.gold + it.rice }
    val resources = phpRound(((n.gold + n.rice) + genGoldRice) / 100.0).toDouble()

    // if(A.level=0, 0, round( SUM(pop)*SUM(pop+agri+comm+secu+wall+def)/SUM(*_max)/100 )) over supply=1.
    val internal = if (n.level == 0) 0.0 else {
        val supplyCities = n.cities.filter { it.supply }
        val pop = supplyCities.sumOf { it.pop }.toDouble()
        val stat = supplyCities.sumOf { it.sumStat }.toDouble()
        val max = supplyCities.sumOf { it.sumMax }.toDouble()
        if (max == 0.0) 0.0 else phpRound(pop * stat / max / 100.0).toDouble()
    }

    // SUM over generals: (ra+1000)/(rb+1000)*(npc<2?1.2:1)*(ld>=40?ld:0)*2 + (sqrt(intel*str)*2 + ld/2)/2
    val abilityTerm = n.generals.sumOf { g ->
        val ra = (g.killcrewPersonRankValue + 1000).toDouble()
        val rb = (g.deathcrewPersonRankValue + 1000).toDouble()
        val npcFactor = if (g.npc < 2) 1.2 else 1.0
        val ldFactor = if (g.leadership >= 40) g.leadership.toDouble() else 0.0
        ra / rb * npcFactor * ldFactor * 2 +
            (sqrt(g.intel.toDouble() * g.strength) * 2 + g.leadership / 2.0) / 2
    }

    // round( SUM(dex1..dex5)/1000 ) and round( SUM(experience+dedication)/100 ).
    val dexTerm = phpRound(n.generals.sumOf { it.dexSum } / 1000.0).toDouble()
    val edTerm = phpRound(n.generals.sumOf { it.experience + it.dedication } / 100.0).toDouble()

    val inner = resources + n.tech + internal + abilityTerm + dexTerm + edTerm
    return phpRound(inner / 10.0)
}

/**
 * POST1 — Q1-Q4 power aggregate + jitter. Iterates [nations] in QUERY ORDER (the DB row order the daemon
 * supplies), computes Q3 raw power, applies Q4 `power *= rng.nextRange(0.95,1.05)` (EXACTLY ONE draw per
 * nation, [phpRound]ed), and tracks maxPower/maxCrew/maxCities into the per-nation KV.
 *
 * @param maxPower the existing `nation_env` `max_power` KV per nation (read-modify-write).
 */
fun postUpdateMonthlyPower(
    nations: List<PostNationPowerInput>,
    maxPower: Map<Int, PowerKv>,
    rng: RandUtil,
): PostUpdateMonthlyPowerResult {
    val drawOrder = mutableListOf<String>()
    val results = nations.map { n ->
        val rawPower = nationRawPower(n)
        val totalCrew = n.generals.sumOf { it.crew }

        // Q4 — the FIRST monthlyRng draw, one nextRange per nation in query order.
        val draw = rng.nextRange(0.95, 1.05)
        drawOrder += "Q4:${n.nationId}"
        val power = phpRound(rawPower * draw)

        val existing = maxPower[n.nationId] ?: PowerKv()
        val newMaxCities =
            if (n.cityNames.size > existing.maxCities.size) n.cityNames else existing.maxCities
        val kv = PowerKv(
            maxPower = maxOf(existing.maxPower, power),
            maxCrew = maxOf(existing.maxCrew, totalCrew),
            maxCities = newMaxCities,
        )
        PostNationPowerResult(
            nationId = n.nationId,
            gennum = n.gennum,
            rawPower = rawPower,
            power = power,
            totalCrew = totalCrew,
            maxPowerKv = kv,
        )
    }
    return PostUpdateMonthlyPowerResult(nations = results, rngDrawOrder = drawOrder)
}

// ===========================================================================================
// POST2 (Q5-Q10) — diplomacy settlement + war-term + 개전/종전 state machine.
// PHP grand truth `func_gamerule.php:336-421`.
// ===========================================================================================

/** A `diplomacy` row (me/you ordered pair) feeding Q5-Q9. */
data class DiplomacyRow(
    val me: Int,
    val you: Int,
    val state: Int,
    val term: Int,
    val dead: Int,
)

/** Q5 per-(me,you) war-term update for state=0 rows (`func_gamerule.php:345-348`). */
data class DiplomacyWarTermUpdate(val me: Int, val you: Int, val newTerm: Int, val newDead: Int)

/** Q7 종전 state update — both directions set to state=2,term=0 (`func_gamerule.php:385-388`). */
data class DiplomacyStateUpdate(val me: Int, val you: Int, val newState: Int, val newTerm: Int)

/** Q9 bulk per-row final state/term/dead (`func_gamerule.php:393-406`). */
data class DiplomacyFinalUpdate(val me: Int, val you: Int, val newState: Int, val newTerm: Int, val newDead: Int)

/** POST2 (Q5-Q10) result. Each list is in the PHP evaluation order; the daemon folds them into the flush. */
data class PostUpdateMonthlyDiplomacyResult(
    val q5Updates: List<DiplomacyWarTermUpdate>,
    val warStartLogs: List<String>,
    val warStopLogs: List<String>,
    val q7StateUpdates: List<DiplomacyStateUpdate>,
    val globalLoggerFlushCount: Int,
    val q9Updates: List<DiplomacyFinalUpdate>,
    val availableWarSettingCnt: Map<Int, Int>,
)

/**
 * POST2 — Q5-Q10 diplomacy settlement. Pure core: takes the `diplomacy` rows + the Q3 per-nation gennum
 * (`genNum`) + nation names + the existing `available_war_setting_cnt` KV (`maxPower`) + the active nation
 * IDs (`nations`), and produces the ordered settlement updates + logs.
 *
 *   Q5  state=0 rows: term=floor(dead/100/genCount[me]); dead-=term*100*genCount; term=valueFit(term+Δ,0,13).
 *   Q6  개전 log for state=1 AND term<=1 AND me<you (input row order — DB query order).
 *   Q7  종전: state=0 AND term<=1 ORDER BY me desc,you desc; first occurrence of the {min}_{max} key seen
 *       → continue; second occurrence → push 종전 log + set BOTH directions state=2,term=0.
 *   Q8  globalLogger.flush() (recorded once).
 *   Q9  bulk on EVERY row: dead=if(state!=0,0,dead), term=greatest(0,term-1); THEN state 7→2 where post-term==0;
 *       THEN state 1→0,term=6 where post-term==0.
 *   Q10 available_war_setting_cnt: ensure each active nation has an entry (default 0); cnt<max → cnt=valueFit(cnt+inc,0,max).
 *
 * **Q6/Q7 read the LIVE state (NOT the Q5 term update — Q5 only mutates state=0 rows' term in the
 * write-set; the PHP Q6/Q7 SELECTs re-read the table, but term on state=0 rows reflects Q5's UPDATE).**
 * The Q7 종전 condition `term<=1` is evaluated against the post-Q5 term for state=0 rows.
 */
fun postUpdateMonthlyDiplomacy(
    rows: List<DiplomacyRow>,
    genNum: Map<Int, Int>,
    nationNames: Map<Int, String>,
    maxPower: Map<Int, Int>,
    nations: List<Int>,
): PostUpdateMonthlyDiplomacyResult {
    val maxCnt = opensamguk.common.constants.GameConst.maxAvailableWarSettingCnt
    val incCnt = opensamguk.common.constants.GameConst.incAvailableWarSettingCnt

    // Q5 — war-term floor on state=0 rows. Produces an effective post-Q5 term per (me,you) for Q7.
    val q5Updates = mutableListOf<DiplomacyWarTermUpdate>()
    val postQ5Term = HashMap<Pair<Int, Int>, Int>()
    for (d in rows) {
        if (d.state != 0) continue
        val genCount = genNum[d.me] ?: 1
        // PHP: floor($dead / 100 / $genCount) — FLOAT division throughout, then floor (faithful to :341).
        val deltaTerm = Math.floor(d.dead.toDouble() / 100.0 / genCount.toDouble()).toInt()
        val newDead = d.dead - deltaTerm * 100 * genCount
        val newTerm = valueFit((d.term + deltaTerm).toDouble(), 0.0, 13.0).toInt()
        q5Updates += DiplomacyWarTermUpdate(d.me, d.you, newTerm, newDead)
        postQ5Term[d.me to d.you] = newTerm
    }

    // Q6 — 개전 log: state=1 AND term<=1 AND me<you (input/query order).
    val warStartLogs = rows
        .filter { it.state == 1 && it.term <= 1 && it.me < it.you }
        .map { HistoryTokens.warStartGlobal(nationNames[it.me] ?: "", nationNames[it.you] ?: "") }

    // Q7 — 종전: state=0 AND (post-Q5) term<=1 ORDER BY me desc,you desc; both directions present → 종전.
    val warStopLogs = mutableListOf<String>()
    val q7StateUpdates = mutableListOf<DiplomacyStateUpdate>()
    val seen = HashSet<String>()
    val q7Candidates = rows
        .filter { it.state == 0 && (postQ5Term[it.me to it.you] ?: it.term) <= 1 }
        .sortedWith(compareByDescending<DiplomacyRow> { it.me }.thenByDescending { it.you })
    for (d in q7Candidates) {
        val key = if (d.me < d.you) "${d.me}_${d.you}" else "${d.you}_${d.me}"
        if (seen.add(key)) continue   // first occurrence — mark seen, skip (PHP `continue`)
        // second occurrence — both directions are present → 종전.
        warStopLogs += HistoryTokens.warStopGlobal(nationNames[d.me] ?: "", nationNames[d.you] ?: "")
        q7StateUpdates += DiplomacyStateUpdate(d.me, d.you, newState = 2, newTerm = 0)
        q7StateUpdates += DiplomacyStateUpdate(d.you, d.me, newState = 2, newTerm = 0)
    }

    // Q8 — globalLogger.flush() (the 개전/종전 logs are emitted here; recorded once).
    val globalLoggerFlushCount = 1

    // Q9 — bulk dead-reset + term-1, THEN state 7→2 (term=0), THEN state 1→0,term=6.
    val q9Updates = rows.map { d ->
        val deadReset = if (d.state != 0) 0 else d.dead
        val termDec = maxOf(0, d.term - 1)
        var newState = d.state
        var newTerm = termDec
        if (newState == 7 && termDec == 0) {          // 불가침 → 통상
            newState = 2
        } else if (newState == 1 && termDec == 0) {   // 선포 → 교전
            newState = 0
            newTerm = 6
        }
        DiplomacyFinalUpdate(d.me, d.you, newState, newTerm, deadReset)
    }

    // Q10 — available_war_setting_cnt: ensure each active nation, then increment those below the max.
    val cnt = LinkedHashMap<Int, Int>()
    cnt.putAll(maxPower)
    for (n in nations) cnt.putIfAbsent(n, 0)
    val result = LinkedHashMap<Int, Int>()
    for ((nationId, c) in cnt) {
        result[nationId] = if (c >= maxCnt) c
        else valueFit((c + incCnt).toDouble(), 0.0, maxCnt.toDouble()).toInt()
    }

    return PostUpdateMonthlyDiplomacyResult(
        q5Updates = q5Updates,
        warStartLogs = warStartLogs,
        warStopLogs = warStopLogs,
        q7StateUpdates = q7StateUpdates,
        globalLoggerFlushCount = globalLoggerFlushCount,
        q9Updates = q9Updates,
        availableWarSettingCnt = result,
    )
}

// ===========================================================================================
// POST3 (Q11-Q17) — the tail: checkWander / tournament / auction RNG order + SetNationFront last.
// PHP grand truth `func_gamerule.php:423-442` (+ `:445-467` checkWander).
// ===========================================================================================

/**
 * A monthlyRng consumer (the SAME `RandUtil` instance threaded Q4→Q11→Q15→Q16). Each Q-step that needs
 * RNG receives the live instance and draws its OWN deterministic count; the pure core only enforces the
 * ORDER + the gate conditions (the daemon supplies the faithful command/tournament/auction bodies).
 */
typealias RngConsumer = (RandUtil) -> Unit

/**
 * A B2-owned placeholder for the Q17 per-nation front result the [setNationFront] callback yields. The
 * faithful front 0/1/2/3 computation lives in B3's `SetNationFront.kt` (a later wave); POST3 only threads
 * the callback output through (Q17 runs LAST and consumes NO monthlyRng).
 */
data class PostFrontResult(val nationId: Int)

/** POST3 (Q11-Q17) result: whether Q11 ran, the monthlyRng draw-order log, and the Q17 front results. */
data class PostUpdateMonthlyTailResult(
    val checkWanderRan: Boolean,
    val rngDrawOrder: List<String>,
    val frontResults: List<PostFrontResult>,
)

/**
 * POST3 — Q11-Q17 tail. Enforces the EXACT monthlyRng consume order on a SINGLE instance and the gate
 * conditions, delegating each RNG-consuming step to an injected consumer (so the faithful command /
 * tournament / auction bodies — and their exact draw counts — live where they belong while the ORDER is
 * pinned here). PINNED per-call draw counts (consolidated OQ #1-residual blocker, PR-7):
 *
 *   Q11 checkWander($rng)        — runs ONLY if `year >= startYear+2`; draws inside the che_해산 command run
 *                                  per wanderer (routes through the P2 CommandRegistry + 9-source pipeline).
 *   Q12 updateGeneralNumber()    — no rng (recompute nation.gennum; daemon side-effect).
 *   Q13 refreshNationStaticInfo()— no rng (static-info cache refresh; daemon side-effect).
 *   Q14 checkEmperior()          — NO rng (천통 detection → isunited / UNITED target; verified takes no rng).
 *   Q15 triggerTournament($rng)  — at most ONE `nextBool(0.4)`; if it proceeds AND tnmt_pattern is empty, a
 *                                  5-element `shuffle`. (Default golden: tnmt_trig off → ZERO draws; the
 *                                  injected consumer reproduces the faithful count.)
 *   Q16 registerAuction($rng)    — EXACTLY two `nextBool(1/(cnt+5))` gates (sell-rice + buy-rice), each
 *                                  optionally followed by `nextRangeInt(1,5)` + `nextRangeInt(3,12)`.
 *   Q17 SetNationFront(nation)   — per level>0 nation in static-info order, runs LAST, NO rng (B3 body).
 *
 * **The monthlyRng is consumed in EXACT order Q4 (POST1) → Q11 → Q15 → Q16, a single instance** (`:322,425,
 * 432,434`). [isUnited] is the Q14 checkEmperior outcome the daemon supplies; it changes NO draw count.
 */
fun postUpdateMonthlyTail(
    year: Int,
    startYear: Int,
    rng: RandUtil,
    checkWander: RngConsumer,
    triggerTournament: RngConsumer,
    registerAuction: RngConsumer,
    setNationFront: () -> List<PostFrontResult>,
    @Suppress("UNUSED_PARAMETER") isUnited: Boolean = false,
): PostUpdateMonthlyTailResult {
    val drawOrder = mutableListOf<String>()

    // Q11 — checkWander gated on `year >= startYear+2` (the SECOND monthlyRng consumer after Q4).
    var checkWanderRan = false
    if (year >= startYear + 2) {
        checkWander(rng)
        drawOrder += "Q11"
        checkWanderRan = true
    }

    // Q12/Q13 — updateGeneralNumber + refreshNationStaticInfo (no rng; daemon-side recompute).
    // Q14 — checkEmperior (no rng; isUnited threaded for the UNITED target but never affects the stream).

    // Q15 — triggerTournament (THIRD consumer).
    triggerTournament(rng)
    drawOrder += "Q15"

    // Q16 — registerAuction (FOURTH consumer).
    registerAuction(rng)
    drawOrder += "Q16"

    // Q17 — SetNationFront per active nation, runs LAST (no rng; B3 produces the front 0/1/2/3 results).
    val frontResults = setNationFront()

    return PostUpdateMonthlyTailResult(
        checkWanderRan = checkWanderRan,
        rngDrawOrder = drawOrder,
        frontResults = frontResults,
    )
}

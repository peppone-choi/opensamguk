package opensamguk.logic.world

import opensamguk.common.rng.RandUtil
import opensamguk.logic.util.phpRound
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

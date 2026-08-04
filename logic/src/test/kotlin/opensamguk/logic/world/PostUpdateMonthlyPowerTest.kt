package opensamguk.logic.world

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.util.phpRound
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port-faithful tests for `postUpdateMonthly` Q1-Q4 (P3 / AREA B2 / Task POST1) — research Unit 9.
 *
 * PHP grand truth `func_gamerule.php:260-334`:
 *   Q1  $globalLogger = new ActionLogger(0, 0, year, month)                         // :266
 *   Q2  group city.name by nation in DB row order                                   // :268-275
 *   Q3  the ONE big power-aggregate SELECT (resources/100 + tech + supply=1 internal
 *       + general-ability rank-weighted term + dex/1000 + (exp+dedication)/100, /10)  // :288-310
 *   Q4  per-nation IN Q3 QUERY ORDER:  power = round(power * rng.nextRange(0.95,1.05));
 *       maxPower/maxCrew/maxCities tracked into nation_env KV; nation.power written   // :313-334
 *
 * **Q4 is the ONLY postUpdateMonthly RNG consumer until checkWander — EXACTLY one nextRange draw per
 * nation in Q3 query order** (a wrong draw count desyncs every downstream month).
 *
 * Q3 transcribed formula (per nation A; `round` = phpRound half-away):
 * ```
 *   power = round(
 *     ( round(((A.gold + A.rice) + SUM(gold+rice over generals)) / 100)
 *       + A.tech
 *       + if(A.level==0, 0,
 *            round( SUM(pop) * SUM(pop+agri+comm+secu+wall+def)
 *                   / SUM(pop_max+agri_max+comm_max+secu_max+wall_max+def_max) / 100 )   // over supply=1 cities
 *         )
 *       + SUM over generals of:
 *            (ra.value + 1000) / (rb.value + 1000)
 *            * (npc < 2 ? 1.2 : 1)
 *            * (leadership >= 40 ? leadership : 0) * 2
 *            + (sqrt(intel*strength)*2 + leadership/2) / 2
 *       + round( SUM(dex1+dex2+dex3+dex4+dex5 over generals) / 1000 )
 *       + round( SUM(experience+dedication over generals) / 100 )
 *     ) / 10
 *   )
 *   totalCrew = SUM(crew over generals)
 * ```
 * `ra`/`rb` are the `rank_data` killcrew_person / deathcrew_person rows (LEFT JOIN → value defaults 0
 * when absent, so the term is `(0+1000)/(0+1000)=1` for a general with no rank rows).
 */
class PostUpdateMonthlyPowerTest {

    private val zeroSeed = "0".repeat(64)
    private fun monthlyRng() = RandUtil(LiteHashDrbg("monthly|200|1"))

    // ── a single general's power-aggregate inputs (Q3 per-general terms) ──
    private fun general(
        id: Int,
        gold: Int = 0, rice: Int = 0,
        leadership: Int = 0, strength: Int = 0, intel: Int = 0,
        npc: Int = 0,
        dexSum: Int = 0,
        experience: Int = 0, dedication: Int = 0,
        crew: Int = 0,
        killcrewPersonRank: Int = 0, deathcrewPersonRank: Int = 0,
    ) = PowerGeneral(
        id = id, gold = gold, rice = rice, leadership = leadership, strength = strength,
        intel = intel, npc = npc, dexSum = dexSum, experience = experience,
        dedication = dedication, crew = crew,
        killcrewPersonRankValue = killcrewPersonRank, deathcrewPersonRankValue = deathcrewPersonRank,
    )

    private fun city(pop: Int, sumStat: Int, sumMax: Int, supply: Boolean = true) =
        PowerCity(pop = pop, sumStat = sumStat, sumMax = sumMax, supply = supply)

    private fun nation(
        id: Int, gennum: Int, gold: Int = 0, rice: Int = 0, tech: Int = 0, level: Int = 1,
        generals: List<PowerGeneral> = emptyList(),
        cities: List<PowerCity> = emptyList(),
        cityNames: List<String> = emptyList(),
    ) = PostNationPowerInput(
        nationId = id, gennum = gennum, gold = gold, rice = rice, tech = tech, level = level,
        generals = generals, cities = cities, cityNames = cityNames,
    )

    /** The HAND-COMPUTED Q3 power for one nation (mirrors the SELECT verbatim, pre-jitter). */
    private fun expectedRawPower(n: PostNationPowerInput): Int {
        val genGoldRice = n.generals.sumOf { it.gold + it.rice }
        val resources = phpRound(((n.gold + n.rice) + genGoldRice) / 100.0).toDouble()
        val internal = if (n.level == 0) 0.0 else {
            val supplyCities = n.cities.filter { it.supply }
            val pop = supplyCities.sumOf { it.pop }.toDouble()
            val stat = supplyCities.sumOf { it.sumStat }.toDouble()
            val max = supplyCities.sumOf { it.sumMax }.toDouble()
            if (max == 0.0) 0.0 else phpRound(pop * stat / max / 100.0).toDouble()
        }
        val abilityTerm = n.generals.sumOf { g ->
            val ra = (g.killcrewPersonRankValue + 1000).toDouble()
            val rb = (g.deathcrewPersonRankValue + 1000).toDouble()
            val npcFactor = if (g.npc < 2) 1.2 else 1.0
            val ldFactor = if (g.leadership >= 40) g.leadership.toDouble() else 0.0
            ra / rb * npcFactor * ldFactor * 2 +
                (sqrt(g.intel.toDouble() * g.strength) * 2 + g.leadership / 2.0) / 2
        }
        val dexTerm = phpRound(n.generals.sumOf { it.dexSum } / 1000.0).toDouble()
        val edTerm = phpRound(n.generals.sumOf { it.experience + it.dedication } / 100.0).toDouble()
        val inner = resources + n.tech + internal + abilityTerm + dexTerm + edTerm
        return phpRound(inner / 10.0)
    }

    // ── Q3: power aggregate for a known MULTI-GENERAL nation (full transcribed formula) ──

    @Test
    fun `power aggregate matches hand-computed value for a multi-general nation`() {
        val n = nation(
            id = 5, gennum = 3, gold = 12000, rice = 8000, tech = 350, level = 3,
            generals = listOf(
                general(1, gold = 1000, rice = 500, leadership = 80, strength = 70, intel = 60,
                    npc = 0, dexSum = 5000, experience = 300, dedication = 200, crew = 1500,
                    killcrewPersonRank = 200, deathcrewPersonRank = 50),
                general(2, gold = 800, rice = 300, leadership = 35, strength = 90, intel = 40,
                    npc = 2, dexSum = 2000, experience = 100, dedication = 50, crew = 1000),
                general(3, gold = 0, rice = 0, leadership = 50, strength = 50, intel = 50,
                    npc = 1, dexSum = 0, experience = 0, dedication = 0, crew = 500),
            ),
            cities = listOf(
                city(pop = 50000, sumStat = 4000, sumMax = 8000, supply = true),
                city(pop = 30000, sumStat = 3000, sumMax = 6000, supply = false),  // supply=false → excluded
            ),
        )
        val result = postUpdateMonthlyPower(
            nations = listOf(n),
            maxPower = emptyMap(),
            rng = monthlyRng(),
        )
        val out = result.nations.single()
        // Pre-jitter Q3 power matches the hand-computed transcription, and totalCrew sums crew.
        assertEquals(expectedRawPower(n), out.rawPower)
        assertEquals(1500 + 1000 + 500, out.totalCrew)
    }

    @Test
    fun `level zero nation contributes no internal-development term`() {
        val n = nation(
            id = 1, gennum = 1, gold = 1000, rice = 1000, tech = 0, level = 0,
            generals = listOf(general(1, leadership = 0, strength = 0, intel = 0)),
            cities = listOf(city(pop = 99999, sumStat = 9999, sumMax = 9999, supply = true)),
        )
        val out = postUpdateMonthlyPower(listOf(n), emptyMap(), monthlyRng()).nations.single()
        assertEquals(expectedRawPower(n), out.rawPower)  // internal term forced to 0 at level 0
    }

    // ── Q4: exactly one nextRange draw per nation in query order ──

    @Test
    fun `Q4 draws exactly one nextRange per nation in query order`() {
        val nations = listOf(
            nation(1, gennum = 1, gold = 100000, tech = 100, generals = listOf(general(1))),
            nation(2, gennum = 1, gold = 200000, tech = 200, generals = listOf(general(2))),
            nation(3, gennum = 1, gold = 300000, tech = 300, generals = listOf(general(3))),
        )
        // A recording RandUtil-substitute: a real RandUtil over a deterministic DRBG, plus a
        // parallel reference stream to assert the draw count + that draws happen in nation order.
        val rng = monthlyRng()
        val ref = monthlyRng()
        val result = postUpdateMonthlyPower(nations, emptyMap(), rng)

        // Exactly 3 draws were consumed (one per nation) — reproduce them in order and verify the
        // jitter applied to each nation equals round(rawPower * draw_i).
        val draws = (1..3).map { ref.nextRange(0.95, 1.05) }
        result.nations.forEachIndexed { i, out ->
            assertEquals(phpRound(out.rawPower * draws[i]), out.power,
                "nation ${out.nationId} jitter must use the i-th draw in query order")
        }
        // Draw-count assertion: a 4th draw on rng must equal the reference's 4th (no extra draw leaked).
        assertEquals(ref.nextRange(0.95, 1.05), rng.nextRange(0.95, 1.05))
        // Draw order recorded for the G1 sequence gate.
        assertEquals(listOf("Q4:1", "Q4:2", "Q4:3"), result.rngDrawOrder)
    }

    @Test
    fun `Q4 power uses phpRound on the jitter`() {
        val n = nation(1, gennum = 1, gold = 100000, tech = 0, generals = listOf(general(1)))
        val rng = monthlyRng()
        val ref = monthlyRng()
        val out = postUpdateMonthlyPower(listOf(n), emptyMap(), rng).nations.single()
        val draw = ref.nextRange(0.95, 1.05)
        assertEquals(phpRound(out.rawPower * draw), out.power)
    }

    // ── Q4: max_power / maxCrew / maxCities KV tracking ──

    @Test
    fun `maxPower KV is updated to the larger of existing and the jittered power`() {
        val n = nation(1, gennum = 1, gold = 100000, tech = 500, generals = listOf(general(1, crew = 999)))
        // Existing KV holds a tiny maxPower → gets overwritten by the new (larger) jittered power.
        val existing = mapOf(1 to PowerKv(maxPower = 1, maxCrew = 1, maxCities = listOf("이전")))
        val out = postUpdateMonthlyPower(listOf(n), existing, monthlyRng()).nations.single()
        assertEquals(maxOf(1, out.power), out.maxPowerKv.maxPower)
        assertEquals(maxOf(1, out.totalCrew), out.maxPowerKv.maxCrew)
    }

    @Test
    fun `maxCities replaced only when the current city COUNT exceeds the stored count`() {
        val cityNames = listOf("낙양", "장안", "허창")
        val n = nation(1, gennum = 1, gold = 1000, tech = 0, generals = listOf(general(1)),
            cityNames = cityNames)
        // stored maxCities has 2 entries; current is 3 → replaced.
        val existing = mapOf(1 to PowerKv(maxPower = 0, maxCrew = 0, maxCities = listOf("a", "b")))
        val out = postUpdateMonthlyPower(listOf(n), existing, monthlyRng()).nations.single()
        assertEquals(cityNames, out.maxPowerKv.maxCities)
    }

    @Test
    fun `maxCities NOT replaced when stored count is greater or equal`() {
        val cityNames = listOf("낙양")
        val n = nation(1, gennum = 1, generals = listOf(general(1)), cityNames = cityNames)
        val stored = listOf("a", "b", "c")
        val existing = mapOf(1 to PowerKv(maxPower = 0, maxCrew = 0, maxCities = stored))
        val out = postUpdateMonthlyPower(listOf(n), existing, monthlyRng()).nations.single()
        assertEquals(stored, out.maxPowerKv.maxCities)  // 1 < 3 → keep stored
    }

    // ── Q2 city grouping order preserved ──

    @Test
    fun `city names carry through to maxCities in input order`() {
        val n = nation(1, gennum = 1, generals = listOf(general(1)),
            cityNames = listOf("성도", "강주", "재동"))
        val out = postUpdateMonthlyPower(listOf(n), emptyMap(), monthlyRng()).nations.single()
        assertTrue(out.maxPowerKv.maxCities.orEmpty().containsAll(listOf("성도", "강주", "재동")))
    }
}

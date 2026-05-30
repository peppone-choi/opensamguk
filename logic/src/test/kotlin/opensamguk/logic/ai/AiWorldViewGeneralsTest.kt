package opensamguk.logic.ai

import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * F-FACADE / Task FC2 — [AiWorldView.categorizeNationGeneral] parity tests.
 *
 * Port target = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php:3516-3613` (read in full) — the 9 general
 * buckets, the first-match-wins role ladder, the `calcRecentWarTurn` 12000 sentinel + the pre-임관 skip,
 * the userWar/userCivil split at `lastWar+12`, the by-reference mutation into the FC1 city buckets. NO RNG
 * draws — these helpers only fix candidate ORDER.
 *
 * Load-bearing parity targets (B5, R-FACADE §1, A2 §8, H-HELPERS §2):
 *  - **PK-ascending HARD requirement:** `SELECT no FROM general WHERE nation=%i AND no!=%i` (`:3516`, EXCLUDES
 *    self) has NO `ORDER BY` → MariaDB returns rows in clustered-PK (general `no` ascending) order. The Kotlin
 *    port MUST materialize `.sortedBy { it.no }` (ascending) into `LinkedHashMap`s in that exact order — a
 *    shuffled InMemory insertion order still yields no-ascending buckets.
 *  - **the first-match-wins role ladder** (`:3574-3601`, A2 §8): `npcType==5` → troopLeaders; non-npc
 *    `che_집합`-leader → troopLeaders; `killturn<=5` → npcCivil; `npcType<2` → userGenerals + (userWar if
 *    recentWar<=lastWar+12 OR (dipState≠평화 & crew>=minWarCrew) else userCivil); `getLeadership(false)>=
 *    minNPCWarLeadership` → npcWar; else npcCivil. REORDERING changes bucketing → commands → draws.
 *  - **calcRecentWarTurn 12000 sentinel** (`General.php:280`, H-HELPERS §2): falsy `recent_war` → `12*1000`;
 *    `secDiff<=0` → 0; else `intdiv(toInt(secDiff), 60*turnTerm)` (trunc-toward-zero). The pre-임관 skip
 *    (`:3544`): `recentWar >= (belong-1)*12 → continue` (excluded from lastWar). userWar split (`:3585`):
 *    `recentWar <= lastWar + 12`.
 *  - **chiefGenerals overwrite-on-dup-level** (`:3559-3560`): keyed by `officer_level` (>4), later wins.
 *  - **important += 1 per officer 2-4** (`:3562`) on its `officer_city`; base 1 from FC1 cities.
 *  - **by-ref into cities** (`:3565-3572`): `nationCities[cityID]['generals'][generalID]=g`; in-own-city but
 *    unsupplied OR city-not-ours → lostGenerals. Cities MUST exist first (FC1 before FC2).
 *  - lazy-once (`:3518`, `userGenerals === null` sentinel) — NOT re-invalidated.
 */
class AiWorldViewGeneralsTest {

    private fun city(id: Int, nation: Int, supply: Int = 1): City = City(
        id = id, nationId = nation, level = 5,
        commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
        supplyState = supply, frontState = 0, trust = 0.0,
    )

    private fun general(
        no: Int,
        nation: Int = 1,
        cityId: Int = 0,
        officerLevel: Int = 1,
        officerCity: Int = 0,
        npcType: Int = 0,
        troop: Int = 0,
        crew: Int = 0,
        leadership: Int = 50,
        killturn: Int = 1000,
        belong: Int = 1,
    ): General = General(
        id = no, nationId = nation, cityId = cityId,
        leadership = leadership, strength = 50, intel = 50, injury = 0,
        experience = 0.0, dedication = 0.0,
        officerLevel = officerLevel, gold = 0, rice = 0,
        crew = crew, troop = troop, npcType = npcType, officerCity = officerCity,
        meta = linkedMapOf("killturn" to killturn, "belong" to belong),
    )

    /** Wrap a [General] into the FC2 view, defaulting recentWarTurn to the 12000 "never fought" sentinel. */
    private fun gv(
        general: General,
        recentWarSeconds: Long? = null,
        reservedCommandName: String? = null,
        fullLeadership: Double = general.leadership.toDouble(),
    ): AiGeneralView = AiGeneralView(
        general = general,
        recentWarSeconds = recentWarSeconds,
        reservedCommandName = reservedCommandName,
        fullLeadership = fullLeadership,
    )

    private fun view(
        ownNationId: Int = 1,
        ownGeneralId: Int = 100,
        cityRows: List<City> = listOf(city(10, 1), city(20, 1)),
        generals: List<AiGeneralView>,
        dipState: Int = AiInstanceState.D_PEACE,
        minWarCrew: Int = 1500,
        minNpcWarLeadership: Int = 40,
        turnTerm: Int = 120,
    ): AiWorldView = AiWorldView(
        ownNationId = ownNationId,
        cityRows = cityRows,
        warTargetNation = null,
        ownGeneralId = ownGeneralId,
        generalsSupplier = { generals },
        dipState = dipState,
        minWarCrew = minWarCrew,
        minNpcWarLeadership = minNpcWarLeadership,
        turnTerm = turnTerm,
    )

    // ----------------------------------------------------------------------------------------------
    // (1) calcRecentWarTurn — the 12000 sentinel, secDiff<=0 clamp, trunc-div (H-HELPERS §2).
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `calcRecentWarTurn returns the 12000 sentinel when recent_war is falsy`() {
        assertEquals(12000, gv(general(1), recentWarSeconds = null).calcRecentWarTurn(120))
    }

    @Test
    fun `calcRecentWarTurn clamps a non-positive secDiff to 0`() {
        assertEquals(0, gv(general(1), recentWarSeconds = 0L).calcRecentWarTurn(120))
        assertEquals(0, gv(general(1), recentWarSeconds = -500L).calcRecentWarTurn(120))
    }

    @Test
    fun `calcRecentWarTurn truncates intdiv(secDiff, 60 turnTerm) toward zero`() {
        // 60 * turnTerm=120 = 7200. 100000 / 7200 = 13.88.. -> trunc 13.
        assertEquals(13, gv(general(1), recentWarSeconds = 100_000L).calcRecentWarTurn(120))
        // exact boundary 7200 -> 1.
        assertEquals(1, gv(general(1), recentWarSeconds = 7200L).calcRecentWarTurn(120))
        assertEquals(0, gv(general(1), recentWarSeconds = 7199L).calcRecentWarTurn(120))
    }

    // ----------------------------------------------------------------------------------------------
    // (2) PK-ascending HARD requirement + self EXCLUDED.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `categorizeNationGeneral materializes general-no PK-ascending order even from shuffled insertion`() {
        // Supply OUT of `no` order to prove the explicit sortedBy{no} fires; npcType<2 -> userGenerals.
        val gens = listOf(gv(general(53)), gv(general(14)), gv(general(30)))
        val v = view(generals = gens)
        v.categorizeNationGeneral()

        assertEquals(
            listOf(14, 30, 53),
            v.userGenerals.keys.toList(),
            "userGenerals keyed general-no ascending (PK order) regardless of insertion order",
        )
        assertEquals(listOf(14, 30, 53), v.nationGenerals.map { it.general.id })
    }

    @Test
    fun `self is excluded by the snapshot supplier and never appears in any bucket`() {
        // The supplier already filters no != ownID (PHP `:3516` WHERE no != ownID); assert no own-id leak.
        val gens = listOf(gv(general(14)), gv(general(30)))
        val v = view(ownGeneralId = 100, generals = gens)
        v.categorizeNationGeneral()
        assertFalse(v.nationGenerals.any { it.general.id == 100 })
        assertFalse(v.userGenerals.containsKey(100))
    }

    // ----------------------------------------------------------------------------------------------
    // (3) The first-match-wins role ladder — each rung asserted in order.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `rung 1 npcType==5 lands troopLeaders even with a low killturn and che_집합 reservation`() {
        // killturn<=5 AND troop===self AND che_집합 would all match later rungs, but npc==5 wins FIRST.
        val g = general(7, npcType = 5, troop = 7, killturn = 3)
        val v = view(generals = listOf(gv(g, reservedCommandName = "che_집합")))
        v.categorizeNationGeneral()
        assertEquals(listOf(7), v.troopLeaders.keys.toList())
        assertTrue(v.npcCivilGenerals.isEmpty())
    }

    @Test
    fun `rung 2 non-npc che_집합 troop-leader lands troopLeaders only when troop===self AND reserved is che_집합`() {
        val leader = general(8, npcType = 0, troop = 8, killturn = 1000)
        val v = view(generals = listOf(gv(leader, reservedCommandName = "che_집합")))
        v.categorizeNationGeneral()
        assertEquals(listOf(8), v.troopLeaders.keys.toList())

        // troop !== self -> falls through to userGenerals (npcType<2).
        val notSelfTroop = general(9, npcType = 0, troop = 99, killturn = 1000)
        val v2 = view(generals = listOf(gv(notSelfTroop, reservedCommandName = "che_집합")))
        v2.categorizeNationGeneral()
        assertTrue(v2.troopLeaders.isEmpty())
        assertEquals(listOf(9), v2.userGenerals.keys.toList())

        // reserved !== che_집합 -> falls through.
        val notJiphap = general(11, npcType = 0, troop = 11, killturn = 1000)
        val v3 = view(generals = listOf(gv(notJiphap, reservedCommandName = "che_휴식")))
        v3.categorizeNationGeneral()
        assertTrue(v3.troopLeaders.isEmpty())
    }

    @Test
    fun `rung 3 killturn lte 5 lands npcCivil (a non-leader near-deletion general)`() {
        val g = general(12, npcType = 3, killturn = 5, leadership = 100)
        val v = view(generals = listOf(gv(g, fullLeadership = 100.0)), minNpcWarLeadership = 40)
        v.categorizeNationGeneral()
        // killturn<=5 wins BEFORE the npcType<2 / leadership rungs.
        assertEquals(listOf(12), v.npcCivilGenerals.keys.toList())
        assertTrue(v.npcWarGenerals.isEmpty())
    }

    @Test
    fun `rung 4 npcType lt 2 user general splits userWar by the lastWar+12 cutoff`() {
        // One general fought recently (recentWar small) -> seeds lastWar; goes userWar.
        // Another never fought (12000 sentinel) -> recentWar > lastWar+12 AND peace -> userCivil.
        val warrior = gv(general(20, npcType = 0, belong = 100), recentWarSeconds = 7200L) // recentWar=1
        val idler = gv(general(21, npcType = 1, belong = 100), recentWarSeconds = null)     // recentWar=12000
        val v = view(generals = listOf(warrior, idler), turnTerm = 120, dipState = AiInstanceState.D_PEACE)
        v.categorizeNationGeneral()

        assertEquals(listOf(20, 21), v.userGenerals.keys.toList())
        assertEquals(listOf(20), v.userWarGenerals.keys.toList(), "recentWar(1) <= lastWar(1)+12 -> userWar")
        assertEquals(listOf(21), v.userCivilGenerals.keys.toList(), "recentWar(12000) > 13 AND peace -> userCivil")
    }

    @Test
    fun `rung 4 userCivil flips to userWar via the not-peace AND crew gte minWarCrew branch`() {
        // A post-임관 seeder (belong=2 -> (belong-1)*12=12 > recentWar=1 -> NOT skipped) seeds lastWar=1, so
        // the lastWar+12 cutoff (=13) is SMALL. The idler (never fought -> recentWar=12000) then misses the
        // first userWar branch (12000 > 13) and falls to the not-peace+crew branch.
        val seeder = gv(general(19, npcType = 0, belong = 2), recentWarSeconds = 7200L) // recentWar=1 -> lastWar
        val idlerHi = gv(general(22, npcType = 0, crew = 2000, belong = 100), recentWarSeconds = null)
        val v = view(generals = listOf(seeder, idlerHi), dipState = AiInstanceState.D_WAR, minWarCrew = 1500)
        v.categorizeNationGeneral()
        assertEquals(listOf(19, 22), v.userWarGenerals.keys.toList(), "seeder(recentWar1) + idler(crew branch)")
        assertTrue(v.userCivilGenerals.isEmpty())

        // crew < minWarCrew -> stays userCivil even in war (idler misses BOTH userWar branches).
        val idlerLo = gv(general(23, npcType = 0, crew = 100, belong = 100), recentWarSeconds = null)
        val v2 = view(generals = listOf(seeder, idlerLo), dipState = AiInstanceState.D_WAR, minWarCrew = 1500)
        v2.categorizeNationGeneral()
        assertEquals(listOf(19), v2.userWarGenerals.keys.toList(), "only the seeder is userWar")
        assertEquals(listOf(23), v2.userCivilGenerals.keys.toList(), "low-crew idler stays userCivil in war")
    }

    @Test
    fun `rung 5 npc leadership gte minNPCWarLeadership lands npcWar only if no earlier rung matched`() {
        // npcType=3 (>=2, not user), killturn high, leadership 50 >= 40 -> npcWar.
        val warNpc = general(30, npcType = 3, killturn = 1000, leadership = 50)
        val v = view(generals = listOf(gv(warNpc, fullLeadership = 50.0)), minNpcWarLeadership = 40)
        v.categorizeNationGeneral()
        assertEquals(listOf(30), v.npcWarGenerals.keys.toList())

        // leadership below threshold -> npcCivil (rung 6 default).
        val civilNpc = general(31, npcType = 3, killturn = 1000, leadership = 30)
        val v2 = view(generals = listOf(gv(civilNpc, fullLeadership = 30.0)), minNpcWarLeadership = 40)
        v2.categorizeNationGeneral()
        assertEquals(listOf(31), v2.npcCivilGenerals.keys.toList())
        assertTrue(v2.npcWarGenerals.isEmpty())
    }

    // ----------------------------------------------------------------------------------------------
    // (4) pre-임관 skip — recentWar >= (belong-1)*12 is excluded from lastWar (`:3544`).
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `the pre-임관 skip excludes a battle fought before joining from the lastWar baseline`() {
        // g40: belong=1 -> (belong-1)*12 = 0; recentWar (1) >= 0 -> SKIPPED from lastWar (never seeds it).
        // g41: belong=100 -> (belong-1)*12 = 1188; recentWar (12000) >= 1188 -> also skipped.
        // -> lastWar stays PHP_INT_MAX; for npcType<2 the userWar cutoff lastWar+12 overflows-safe (huge),
        //    so a recentWar of 1 still goes userWar (1 <= MAX) -> proves lastWar was NOT seeded by g40.
        val g40 = gv(general(40, npcType = 0, belong = 1), recentWarSeconds = 7200L) // recentWar=1, belong=1 -> skip
        val v = view(generals = listOf(g40), turnTerm = 120, dipState = AiInstanceState.D_PEACE)
        v.categorizeNationGeneral()
        // lastWar never seeded -> +12 cutoff is effectively unbounded -> userWar.
        assertEquals(listOf(40), v.userWarGenerals.keys.toList())
    }

    // ----------------------------------------------------------------------------------------------
    // (5) chiefGenerals overwrite-on-dup-level + important += per officer 2-4.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `chiefGenerals keyed by officer_level overwrites on duplicate level (later wins)`() {
        val first = general(50, officerLevel = 5)
        val second = general(51, officerLevel = 5) // same level -> overwrites in no-ascending order (51 after 50)
        val v = view(generals = listOf(gv(first), gv(second)))
        v.categorizeNationGeneral()
        assertSame(second.id, v.chiefGenerals.getValue(5).general.id, "later no-ascending general wins the dup level")
    }

    @Test
    fun `important bumps by 1 per officer 2-4 general on its officer_city`() {
        // city 10 base important=1; two officers (level 2 and 4) on city 10 -> important = 1+1+1 = 3.
        // a level-5 chief does NOT bump important (officerLevel>4 -> chiefGenerals branch).
        val o2 = general(60, officerLevel = 2, officerCity = 10, cityId = 10)
        val o4 = general(61, officerLevel = 4, officerCity = 10, cityId = 10)
        val chief = general(62, officerLevel = 5, officerCity = 10, cityId = 10)
        val v = view(cityRows = listOf(city(10, 1)), generals = listOf(gv(o2), gv(o4), gv(chief)))
        v.categorizeNationGeneral()
        assertEquals(3, v.nationCities.getValue(10).important, "base 1 + officer(2) + officer(4)")
    }

    // ----------------------------------------------------------------------------------------------
    // (6) by-ref into cities + lost detection.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `generals attach by-reference into the FC1 city generals buckets in no-ascending order`() {
        val g1 = general(70, cityId = 10)
        val g2 = general(71, cityId = 10)
        val v = view(cityRows = listOf(city(10, 1), city(20, 1)), generals = listOf(gv(g2), gv(g1)))
        v.categorizeNationGeneral()
        // sortedBy{no} -> 70 then 71 attach into city 10's generals LinkedHashMap.
        assertEquals(listOf(70, 71), v.nationCities.getValue(10).generals.keys.toList())
        assertTrue(v.nationCities.getValue(20).generals.isEmpty())
    }

    @Test
    fun `a general in an unsupplied own city is lost and a general in a non-nation city is lost`() {
        // city 10 supplied; city 30 unsupplied (own nation); city 40 not in nationCities at all.
        val supplied = general(80, cityId = 10)
        val inUnsupplied = general(81, cityId = 30)
        val notOurs = general(82, cityId = 40)
        val v = view(
            cityRows = listOf(city(10, 1, supply = 1), city(30, 1, supply = 0)),
            generals = listOf(gv(supplied), gv(inUnsupplied), gv(notOurs)),
        )
        v.categorizeNationGeneral()
        assertFalse(v.lostGenerals.containsKey(80), "supplied own-city general is not lost")
        assertTrue(v.lostGenerals.containsKey(81), "own-city-but-unsupplied general is lost")
        assertTrue(v.lostGenerals.containsKey(82), "non-nation-city general is lost")
        // unsupplied own city still attaches the general into its generals bucket by-ref.
        assertEquals(listOf(81), v.nationCities.getValue(30).generals.keys.toList())
    }

    // ----------------------------------------------------------------------------------------------
    // (7) lazy-once + zero draws.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `categorizeNationGeneral is lazy-once and idempotent`() {
        var supplierCalls = 0
        val v = AiWorldView(
            ownNationId = 1,
            cityRows = listOf(city(10, 1)),
            warTargetNation = null,
            ownGeneralId = 100,
            generalsSupplier = { supplierCalls += 1; listOf(gv(general(14))) },
            dipState = AiInstanceState.D_PEACE,
            minWarCrew = 1500,
            minNpcWarLeadership = 40,
            turnTerm = 120,
        )
        v.categorizeNationGeneral()
        v.categorizeNationGeneral()
        assertEquals(1, supplierCalls, "the PK-ascending general snapshot is materialized exactly once")
    }
}

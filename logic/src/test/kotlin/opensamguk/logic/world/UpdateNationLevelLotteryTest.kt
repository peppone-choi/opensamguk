package opensamguk.logic.world

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.domain.General
import opensamguk.logic.tick.MonthScopedRng
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A3 / Task NL3 — `UpdateNationLevel` two-stage unique-item lottery (byte-match RNG split).
 *
 * Port target: `Event/Action/UpdateNationLevel.php:134-222`.
 *   - eligible = `general WHERE nation=%i AND killturn >= (env.killturn - 24*60/turnterm) AND npc<2`.
 *   - per general: `trialCnt = min(maxTrialCountByYear, count(allItems)=4)`, MINUS 1 per already-owned
 *     non-buyable (unique) item; skip if `trialCnt <= 0`.
 *   - `score = belong + 10`; +60 if officer_level==12, +30 if ==11, +15 if >4; `score *= 2**trialCnt`.
 *   - `maxTrialCountByYear` walks `GameConst.maxUniqueItemLimit` keyed on `relYear=year-startYear`.
 *   - TWO-STAGE RNG (byte-match critical): stage-1 winner selection rides
 *     `MonthScopedRng.forNationLevelUp(hidden,y,m,nationID)` via `choiceUsingWeightPair`; stage-2 the
 *     per-item grant rides `MonthScopedRng.forGivenUnique(hidden,y,m,nationID,winnerID)`.
 *   - loop `levelDiff` times: pick winner, UNSET it, derive the per-winner givenUnique RNG, grant.
 *   - finally chief `increaseInheritancePoint(unifier, 250*levelDiff)`.
 *
 * `giveRandomUniqueItem` (the actual item pick) is an INJECTED seam — its DB-wide occupancy queries
 * are a P6/infra concern. NL3 ports the byte-match-critical mechanics: eligibility, scoring, the
 * trialCnt curve, and the two-stage RNG derivation (the seeds + the selection draw + the iteration).
 */
class UpdateNationLevelLotteryTest {

    private val HIDDEN = "00000000000000000000000000000000"

    private fun gen(
        id: Int, officerLevel: Int, belong: Int = 0, killturn: Int = 2000,
        npcType: Int = 0, ownedUnique: List<String> = emptyList(),
    ): General {
        val meta = linkedMapOf<String, Any?>("belong" to belong, "killturn" to killturn)
        // model owned non-buyable items by putting a rare code in the item slot(s).
        var horse = "None"; var weapon = "None"
        ownedUnique.getOrNull(0)?.let { horse = it }
        ownedUnique.getOrNull(1)?.let { weapon = it }
        return General(
            id = id, nationId = 7, cityId = 1, leadership = 50, strength = 50, intel = 50,
            injury = 0, experience = 0.0, dedication = 0.0, officerLevel = officerLevel,
            gold = 0, rice = 0, horse = horse, weapon = weapon, npcType = npcType, meta = meta,
        )
    }

    private fun lottery(
        generals: List<General>, levelDiff: Int, year: Int = 200, startYear: Int = 180,
        killturnEnv: Int = 1200, turnterm: Int = 120,
    ): UpdateNationLevel.LotteryResult =
        UpdateNationLevel.runUniqueLottery(
            nationId = 7, year = year, month = 1, startYear = startYear,
            hiddenSeed = HIDDEN, levelDiff = levelDiff, killturnEnv = killturnEnv, turnterm = turnterm,
            generals = generals,
            giveRandomUniqueItem = { _, _ -> true },
        )

    @Test
    fun `eligibility filters killturn cutoff and npc gte 2`() {
        // env.killturn 1200, turnterm 120 → cutoff = 1200 - 24*60/120 = 1200 - 12 = 1188.
        val ok = gen(1, officerLevel = 5, killturn = 1188)        // == cutoff → included (>=)
        val tooOld = gen(2, officerLevel = 5, killturn = 1187)    // below cutoff → excluded
        val npc = gen(3, officerLevel = 5, killturn = 2000, npcType = 2) // npc>=2 → excluded
        val r = lottery(listOf(ok, tooOld, npc), levelDiff = 1)
        assertEquals(setOf(1), r.eligibleGeneralIds.toSet())
    }

    @Test
    fun `trialCnt is min(maxByYear, 4) minus owned non-buyable items`() {
        // relYear = 200-180 = 20 → maxUniqueItemLimit walk: [-1,1],[3,2],[10,3],[20,4] → relYear 20 >= 20 → 4.
        // count(allItems)=4 → min(4,4)=4.
        assertEquals(4, UpdateNationLevel.maxTrialCountByYear(relYear = 20))
        assertEquals(GameConst.allItems.size, 4)

        // a general owning ONE non-buyable rare → trialCnt 4-1=3.
        val rare = firstNonBuyableHorse()
        val withRare = gen(1, officerLevel = 5, ownedUnique = listOf(rare))
        val score = UpdateNationLevel.lotteryScore(withRare, trialCnt = 3)
        // belong 0 + 10, officer_level 5 (>4) → +15 → 25; *2^3 = 200.
        assertEquals((0 + 10 + 15) * 8, score)
    }

    @Test
    fun `score weights officer_level 12 11 and gt4`() {
        // trialCnt 4, belong 100.
        val chief = gen(1, officerLevel = 12, belong = 100)
        assertEquals((100 + 10 + 60) * 16, UpdateNationLevel.lotteryScore(chief, trialCnt = 4))
        val sub = gen(2, officerLevel = 11, belong = 100)
        assertEquals((100 + 10 + 30) * 16, UpdateNationLevel.lotteryScore(sub, trialCnt = 4))
        val officer = gen(3, officerLevel = 5, belong = 100)
        assertEquals((100 + 10 + 15) * 16, UpdateNationLevel.lotteryScore(officer, trialCnt = 4))
        val low = gen(4, officerLevel = 4, belong = 100)         // not >4 → no bonus
        assertEquals((100 + 10) * 16, UpdateNationLevel.lotteryScore(low, trialCnt = 4))
    }

    @Test
    fun `trialCnt zero or below skips the general entirely`() {
        // a general owning 4 rare items → trialCnt 4-... but slots only carry 2 here; force via maxByYear=1.
        // relYear < 3 → maxByYear 1; one owned rare → trialCnt 1-1 = 0 → skipped.
        assertEquals(1, UpdateNationLevel.maxTrialCountByYear(relYear = 0))
        val rare = firstNonBuyableHorse()
        val g = gen(1, officerLevel = 5, ownedUnique = listOf(rare))
        val r = UpdateNationLevel.runUniqueLottery(
            nationId = 7, year = 181, month = 1, startYear = 180, hiddenSeed = HIDDEN,
            levelDiff = 1, killturnEnv = 1200, turnterm = 120, generals = listOf(g),
            giveRandomUniqueItem = { _, _ -> true },
        )
        assertTrue(r.eligibleGeneralIds.contains(1), "still passes killturn/npc filter")
        assertFalse(r.weightedGeneralIds.contains(1), "but trialCnt<=0 drops it from the weight list")
    }

    @Test
    fun `two-stage RNG split selects a deterministic winner and derives the per-winner givenUnique seed`() {
        val a = gen(1, officerLevel = 12, belong = 50)
        val b = gen(2, officerLevel = 5, belong = 50)
        val r = lottery(listOf(a, b), levelDiff = 1)

        // stage-1 winner == a hand-rolled choiceUsingWeightPair over the SAME weight list with the
        // nationLevelUp seed (proving the selection rides forNationLevelUp, not monthlyRng).
        val sel = RandUtil(LiteHashDrbg(serializeSeed(HIDDEN, "nationLevelUp", 200, 1, 7)))
        val weights = r.weightPairsInOrder.map { it.first to it.second.toDouble() }
        val expectedWinner = sel.choiceUsingWeightPair(weights)
        assertEquals(expectedWinner, r.winnerIdsInOrder.first())

        // stage-2: the granted seed for that winner is the givenUnique seed (y,m,nationID,winnerID).
        val winnerId = r.winnerIdsInOrder.first()
        assertEquals(
            serializeSeed(HIDDEN, "givenUnique", 200, 1, 7, winnerId),
            r.grants.single().second,
        )
    }

    @Test
    fun `levelDiff drives the iteration count and each winner is unset before the next pick`() {
        val a = gen(1, officerLevel = 12, belong = 50)
        val b = gen(2, officerLevel = 11, belong = 50)
        val c = gen(3, officerLevel = 5, belong = 50)
        val r = lottery(listOf(a, b, c), levelDiff = 2)
        assertEquals(2, r.winnerIdsInOrder.size)
        assertEquals(2, r.winnerIdsInOrder.distinct().size, "winners are unset → no repeats")
    }

    @Test
    fun `levelDiff exceeding the pool stops when the weight list is empty`() {
        val a = gen(1, officerLevel = 12, belong = 50)
        val r = lottery(listOf(a), levelDiff = 5)
        assertEquals(1, r.winnerIdsInOrder.size, "only one eligible → at most one grant")
    }

    @Test
    fun `chief inheritance point delta is 250 times levelDiff`() {
        val chief = gen(1, officerLevel = 12, belong = 50)
        val other = gen(2, officerLevel = 5, belong = 50)
        val r = lottery(listOf(chief, other), levelDiff = 3)
        assertEquals(1, r.chiefId)
        assertEquals(250 * 3, r.chiefInheritancePointDelta)
    }

    @Test
    fun `no chief found yields a null chief and zero inheritance delta`() {
        val r = lottery(listOf(gen(1, officerLevel = 5), gen(2, officerLevel = 7)), levelDiff = 1)
        assertEquals(null, r.chiefId)
        assertEquals(0, r.chiefInheritancePointDelta)
    }

    // helper: the first cnt>0 (non-buyable / rare) horse code from the allItems catalog.
    private fun firstNonBuyableHorse(): String =
        GameConst.allItems.getValue("horse").entries.first { it.value > 0 }.key
}

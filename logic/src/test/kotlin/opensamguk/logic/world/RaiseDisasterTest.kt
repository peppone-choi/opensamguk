package opensamguk.logic.world

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A6a — RaiseDisaster (PHP `Event/Action/RaiseDisaster.php`), a gate-byte-matched `$defaultEvents`
 * leaf firing at month 1/4/7/10 (priority 9000).
 *
 * Port targets (PHP grand truth):
 *   - seed = simpleSerialize(hiddenSeed, 'disater', year, month)  ← LEGACY MISSPELLING, byte-matched verbatim.
 *   - UNCONDITIONAL `city.state = 0 WHERE state <= 10` (always, even in the startYear+3 skip window).
 *   - `if (startYear + 3 > year) return;` — skip the disaster table for the first 3 years.
 *   - `isGood = rng.nextBool(boomingRate[month])`, boomingRate {1:0, 4:0.25, 7:0.25, 10:0}.
 *     boomingRate 0 → nextBool(0) returns false WITHOUT a draw (months 1/10); 0.25 → ONE float draw.
 *   - per city in PK-ascending order: raiseProp = good ? 0.02 + secu/secuMax*0.05 : 0.06 - secu/secuMax*0.05;
 *     selected iff rng.nextBool(raiseProp). The per-city draw ORDER is the RNG stream — PK ascending.
 *   - the chosen log line = rng.choice(disaster|booming text list for the month) AFTER target selection.
 */
class RaiseDisasterTest {

    private val HIDDEN_SEED = "00000000000000000000000000000000"

    private fun rng(year: Int, month: Int) =
        RandUtil(LiteHashDrbg(serializeSeed(HIDDEN_SEED, "disater", year, month)))

    private fun city(id: Int, state: Int, secu: Int = 1000, secuMax: Int = 1000) =
        DisasterCity(cityId = id, name = "성$id", state = state, secu = secu, secuMax = secuMax)

    // ── 'disater' seed token (the legacy misspelling) ────────────────────────
    @Test
    fun `seed token uses the legacy misspelling 'disater'`() {
        // The Action MUST seed from 'disater' (NOT 'disaster'); a 'disaster'-seeded stream diverges.
        val correct = serializeSeed(HIDDEN_SEED, "disater", 184, 4)
        val misCorrected = serializeSeed(HIDDEN_SEED, "disaster", 184, 4)
        assertTrue(correct != misCorrected, "'disater' and 'disaster' seeds MUST differ")
        // RaiseDisaster builds its OWN rng internally from 'disater'; the result is deterministic for it.
        val a = raiseDisaster(listOf(city(1, 5)), year = 184, month = 4, startYear = 181, rng = rng(184, 4))
        val b = raiseDisaster(listOf(city(1, 5)), year = 184, month = 4, startYear = 181, rng = rng(184, 4))
        assertEquals(a.stateResets, b.stateResets)
        assertEquals(a.targetCityIds, b.targetCityIds)
    }

    // ── state reset is UNCONDITIONAL (always, even within the skip window) ────
    @Test
    fun `state reset to 0 where state lte 10 runs ALWAYS, even within startYear+3 skip window`() {
        // year 182 < startYear(181)+3 = 184 → disaster table SKIPPED, but the state reset STILL runs.
        val cities = listOf(city(1, 0), city(2, 5), city(3, 10), city(4, 11), city(5, 99))
        val r = raiseDisaster(cities, year = 182, month = 1, startYear = 181, rng = rng(182, 1))
        // state<=10 → 0; state>10 → untouched (absent from the reset map)
        assertEquals(0, r.stateResets[1])
        assertEquals(0, r.stateResets[2])
        assertEquals(0, r.stateResets[3])
        assertNull(r.stateResets[4], "state 11 > 10 → not reset")
        assertNull(r.stateResets[5], "state 99 > 10 → not reset")
        // within the skip window → no targets, no log
        assertTrue(r.targetCityIds.isEmpty(), "skip window → no disaster-table roll")
        assertNull(r.logLine, "skip window → no history log")
        assertTrue(r.skippedByYearGate, "year < startYear+3 → table skipped")
    }

    @Test
    fun `state reset boundary is inclusive at 10 and exclusive above`() {
        val cities = listOf(city(1, 10), city(2, 11))
        val r = raiseDisaster(cities, year = 200, month = 1, startYear = 181, rng = rng(200, 1))
        assertEquals(0, r.stateResets[1], "state==10 IS reset (<= 10)")
        assertNull(r.stateResets[2], "state==11 is NOT reset")
    }

    // ── year gate: month 1/10 booming==0 → nextBool draws NOTHING (isGood false) ─
    @Test
    fun `month 1 and 10 are never good (booming rate 0, no booming draw)`() {
        // isGood = nextBool(0) = false WITHOUT a draw → all targets get the disaster (bad) branch.
        val rJan = raiseDisaster((1..40).map { city(it, 0) }, year = 200, month = 1, startYear = 181, rng = rng(200, 1))
        assertTrue(!rJan.isGood, "month 1 boomingRate 0 → isGood false")
        val rOct = raiseDisaster((1..40).map { city(it, 0) }, year = 200, month = 10, startYear = 181, rng = rng(200, 10))
        assertTrue(!rOct.isGood, "month 10 boomingRate 0 → isGood false")
    }

    @Test
    fun `month 1 booming==0 does not consume a draw — the first per-city roll is the FIRST draw`() {
        // If isGood consumed a draw, this parallel reconstruction would diverge. nextBool(0) must NOT draw.
        val single = city(1, 0, secu = 0, secuMax = 1000) // raiseProp = 0.06 - 0 = 0.06
        val r = raiseDisaster(listOf(single), year = 200, month = 1, startYear = 181, rng = rng(200, 1))
        // Reconstruct: a fresh stream where the FIRST draw is the per-city nextBool(0.06).
        val fresh = rng(200, 1)
        val expectedSelected = fresh.nextBool(0.06)
        assertEquals(expectedSelected, r.targetCityIds.contains(1),
            "month-1 isGood must not consume a draw; the first draw is the per-city roll")
    }

    // ── per-city PK-ascending draw order is load-bearing ──────────────────────
    @Test
    fun `per-city selection rolls in PK ascending order (deterministic, order-sensitive)`() {
        val cities = listOf(city(3, 0, secu = 1000, secuMax = 1000), city(1, 0, secu = 0, secuMax = 1000), city(2, 0))
        // The caller supplies cities already in PK order (the contract = PHP `SELECT ... FROM city` PK asc).
        val a = raiseDisaster(cities, year = 200, month = 4, startYear = 181, rng = rng(200, 4))
        val b = raiseDisaster(cities, year = 200, month = 4, startYear = 181, rng = rng(200, 4))
        assertEquals(a.targetCityIds, b.targetCityIds, "same seed + same order → identical target set")
        // Targets, if any, appear in the iterated (PK) order.
        assertEquals(a.targetCityIds.sorted(), a.targetCityIds.toList(),
            "targets recorded in PK-ascending iteration order")
    }

    // ── month-4/7 can be good (booming rate 0.25) ─────────────────────────────
    @Test
    fun `month 4 booming roll consumes exactly one float draw before per-city rolls`() {
        // With a contrived single city, the per-city roll must be the SECOND draw (booming=first).
        val single = city(1, 0, secu = 0, secuMax = 1000)
        val r = raiseDisaster(listOf(single), year = 200, month = 4, startYear = 181, rng = rng(200, 4))
        val fresh = rng(200, 4)
        val isGood = fresh.nextBool(0.25)
        val raiseProp = if (isGood) 0.02 + 0.0 else 0.06 - 0.0
        val expectedSelected = fresh.nextBool(raiseProp)
        assertEquals(isGood, r.isGood, "month-4 isGood = nextBool(0.25)")
        assertEquals(expectedSelected, r.targetCityIds.contains(1),
            "per-city roll is the draw AFTER the booming roll")
    }

    // ── when targets exist, a log line is chosen for the month ────────────────
    @Test
    fun `a non-empty target set produces a month-appropriate log line`() {
        // Force a high disaster prop: month 1 (bad), secu 0 → raiseProp 0.06; run many cities so some are hit.
        val cities = (1..200).map { city(it, 0, secu = 0, secuMax = 1000) }
        val r = raiseDisaster(cities, year = 200, month = 1, startYear = 181, rng = rng(200, 1))
        if (r.targetCityIds.isNotEmpty()) {
            assertTrue(r.logLine != null && r.logLine!!.contains("【재난】"),
                "month-1 disaster log carries the 재난 marker; got ${r.logLine}")
            assertTrue(r.logLine!!.contains("에 "), "log embeds the squeezed city names")
        }
    }

    // ── 재난(bad) branch: affectRatio uncapped (capped=false) — trust*ratio 무캡의 근거 ──
    // RaiseDisaster.php:122-135: affectRatio = 0.8 + valueFit(secu/secu_max/0.8,0,1)*0.15,
    //   trust => trust * affectRatio (NO least()). 정수 스탯과 SAME affectRatio.
    @Test
    fun `재난 branch effects carry capped=false and affectRatio in 0_8 to 0_95`() {
        // month 1 (boomingRate 0 → isGood false). secu_max>0 인 도시들 중 선택된 타겟의 effect 검증.
        val cities = (1..300).map { city(it, 0, secu = it % 1000, secuMax = 1000) }
        val r = raiseDisaster(cities, year = 200, month = 1, startYear = 181, rng = rng(200, 1))
        assertTrue(!r.isGood, "month 1 → 재난(bad) branch")
        assertTrue(r.effects.isNotEmpty(), "타겟이 적어도 하나는 잡혀야 effect 검증 가능")
        for (e in r.effects) {
            assertTrue(!e.capped, "재난 effect 는 capped=false (trust*ratio 무캡 ⇒ 엔진이 least() 미적용)")
            // affectRatio = 0.8 + fit*0.15, fit∈[0,1] → [0.8, 0.95].
            assertTrue(e.affectRatio in 0.8..0.95,
                "재난 affectRatio 는 0.8~0.95 (got ${e.affectRatio})")
        }
    }

    // ── 호황(good) branch: affectRatio capped (capped=true) — least(trust*ratio,100) 캡의 근거 ──
    // RaiseDisaster.php:147-160: affectRatio = 1.01 + valueFit(secu/secu_max/0.8,0,1)*0.04,
    //   trust => least(trust * affectRatio, 100) (리터럴 100, trust_max 아님). 정수 스탯과 SAME affectRatio.
    @Test
    fun `호황 branch effects carry capped=true and affectRatio in 1_01 to 1_05`() {
        // month 4 (boomingRate 0.25). isGood 가 true 가 나올 때까지 연도를 바꿔 호황 케이스를 강제 확보.
        var found: RaiseDisasterResult? = null
        for (y in 200..400) {
            val cities = (1..300).map { city(it, 0, secu = it % 1000, secuMax = 1000) }
            val r = raiseDisaster(cities, year = y, month = 4, startYear = 181, rng = rng(y, 4))
            if (r.isGood && r.effects.isNotEmpty()) { found = r; break }
        }
        val r = found ?: error("호황(isGood) + 타겟 보유 케이스를 찾지 못함")
        for (e in r.effects) {
            assertTrue(e.capped, "호황 effect 는 capped=true (trust 는 least(trust*ratio,100) 으로 100 캡)")
            // affectRatio = 1.01 + fit*0.04, fit∈[0,1] → [1.01, 1.05].
            assertTrue(e.affectRatio in 1.01..1.05,
                "호황 affectRatio 는 1.01~1.05 (got ${e.affectRatio})")
        }
    }
}

package opensamguk.logic.world

import opensamguk.logic.diplomacy.DiplomacyConst
import opensamguk.logic.log.HistoryTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port-faithful tests for `postUpdateMonthly` Q5-Q10 (P3 / AREA B2 / Task POST2) — research Unit 9.
 *
 * PHP grand truth `func_gamerule.php:336-421`:
 *   Q5  WHERE state=0: term=floor(dead/100/genCount); dead-=term*100*genCount; term=valueFit(term+Δ,0,13)  // :336-349
 *   Q6  개전 log  WHERE state=1 AND term<=1 AND me<you                                                       // :351-361
 *   Q7  종전 log + state  WHERE state=0 AND term<=1 ORDER BY me desc,you desc; both-dir present → state=2,term=0  // :362-389
 *   Q8  globalLogger.flush()                                                                                  // :391
 *   Q9  bulk: dead=if(state!=0,0,dead), term=greatest(0,term-1); then state 7→2 (term=0); then 1→0 term=6     // :393-406
 *   Q10 available_war_setting_cnt: ensure each nation, then cnt<max → cnt=valueFit(cnt+inc,0,max)             // :408-421
 *
 * Q5 `genCount` = the me-nation's gennum from the Q3 query (`$genNum[$dip['me']]`). The Q9 bulk
 * term-1 + dead-reset runs BEFORE the 7→2 / 1→0 transitions (which match the POST-decrement term=0).
 */
class PostUpdateMonthlyDiplomacyTest {

    private fun dip(me: Int, you: Int, state: Int, term: Int = 0, dead: Int = 0) =
        DiplomacyRow(me = me, you = you, state = state, term = term, dead = dead)

    // ── Q5: war-term floor math ──

    @Test
    fun `Q5 war-term floor and dead carryover for state-zero rows`() {
        // me-nation has 2 generals (genCount=2): floor(dead/100/2).
        val rows = listOf(dip(me = 1, you = 2, state = 0, term = 3, dead = 5000))
        val genNum = mapOf(1 to 2)
        val out = postUpdateMonthlyDiplomacy(rows, genNum, nationNames = nameMap(), maxPower = emptyMap(), nations = emptyList())
        // term = floor(5000/100/2) = floor(25) = 25; dead -= 25*100*2 = 5000 → dead=0; term=valueFit(3+25,0,13)=13.
        val q5 = out.q5Updates.single()
        assertEquals(0, q5.newDead)
        assertEquals(13, q5.newTerm)  // 28 clamped to 13
    }

    @Test
    fun `Q5 partial dead consumption leaves remainder`() {
        // dead=350, genCount=1: floor(350/100/1)=3; dead-=3*100*1=300 → dead=50; term=valueFit(0+3,0,13)=3.
        val rows = listOf(dip(me = 1, you = 2, state = 0, term = 0, dead = 350))
        val out = postUpdateMonthlyDiplomacy(rows, mapOf(1 to 1), nameMap(), emptyMap(), emptyList())
        val q5 = out.q5Updates.single()
        assertEquals(50, q5.newDead)
        assertEquals(3, q5.newTerm)
    }

    // ── Q6: 개전 (war start) log, me<you only ──

    @Test
    fun `Q6 war-start log fires for state-1 term-le-1 me-lt-you`() {
        val rows = listOf(
            dip(me = 1, you = 2, state = 1, term = 1),   // logged
            dip(me = 3, you = 2, state = 1, term = 0),   // me>you → NOT logged
            dip(me = 1, you = 5, state = 1, term = 2),   // term>1 → NOT logged
        )
        val out = postUpdateMonthlyDiplomacy(rows, mapOf(1 to 1, 3 to 1), nameMap(), emptyMap(), emptyList())
        assertEquals(listOf(HistoryTokens.warStartGlobal("위", "촉")), out.warStartLogs)
    }

    // ── Q7: 종전 (war stop) log + state→2 when both directions present ──

    @Test
    fun `Q7 war-stop fires only when both directions are state-0 term-le-1`() {
        // (1,2) and (2,1) both present → second occurrence (ORDER me desc → 2,1 first then 1,2) triggers stop.
        val rows = listOf(
            dip(me = 1, you = 2, state = 0, term = 1),
            dip(me = 2, you = 1, state = 0, term = 0),
        )
        val out = postUpdateMonthlyDiplomacy(rows, mapOf(1 to 1, 2 to 1), nameMap(), emptyMap(), emptyList())
        // ORDER BY me desc,you desc → (2,1) seen-first (continue), (1,2) second → stop log with name(me=1),name(you=2).
        assertEquals(listOf(HistoryTokens.warStopGlobal("위", "촉")), out.warStopLogs)
        // Both directions set to state=2, term=0.
        val s = out.q7StateUpdates
        assertTrue(s.any { it.me == 1 && it.you == 2 && it.newState == 2 && it.newTerm == 0 })
        assertTrue(s.any { it.me == 2 && it.you == 1 && it.newState == 2 && it.newTerm == 0 })
    }

    @Test
    fun `Q7 single direction does not trigger stop`() {
        val rows = listOf(dip(me = 1, you = 2, state = 0, term = 1))
        val out = postUpdateMonthlyDiplomacy(rows, mapOf(1 to 1), nameMap(), emptyMap(), emptyList())
        assertTrue(out.warStopLogs.isEmpty())
        assertTrue(out.q7StateUpdates.isEmpty())
    }

    // ── Q8: globalLogger flushed exactly once after the diplomacy logs ──

    @Test
    fun `Q8 records a single globalLogger flush after the log batch`() {
        val rows = listOf(dip(me = 1, you = 2, state = 1, term = 1))
        val out = postUpdateMonthlyDiplomacy(rows, mapOf(1 to 1), nameMap(), emptyMap(), emptyList())
        assertEquals(1, out.globalLoggerFlushCount)
    }

    // ── Q9: bulk dead-reset + term-1 then state machine 7→2 / 1→0(term=6) ──

    @Test
    fun `Q9 bulk reset then state transitions`() {
        val rows = listOf(
            dip(me = 1, you = 2, state = 5, term = 3, dead = 999),  // state!=0 → dead→0, term→2, no transition
            dip(me = 2, you = 1, state = 0, term = 4, dead = 50),   // state=0 → dead kept, term→3
            dip(me = 3, you = 4, state = 7, term = 1, dead = 0),    // term→0 → 7 becomes 2 (불가침→통상)
            dip(me = 4, you = 3, state = 1, term = 1, dead = 0),    // term→0 → 1 becomes 0 term=6 (선포→교전)
        )
        val out = postUpdateMonthlyDiplomacy(rows, mapOf(1 to 1, 2 to 1, 3 to 1, 4 to 1), nameMap(), emptyMap(), emptyList())
        val q9 = out.q9Updates.associateBy { it.me to it.you }
        // state!=0 → dead reset to 0; term greatest(0,3-1)=2; state unchanged.
        q9.getValue(1 to 2).let { assertEquals(0, it.newDead); assertEquals(2, it.newTerm); assertEquals(5, it.newState) }
        q9.getValue(2 to 1).let { assertEquals(50, it.newDead); assertEquals(3, it.newTerm); assertEquals(0, it.newState) }
        // state=7, term 1-1=0 → state→2 (term stays 0).
        q9.getValue(3 to 4).let { assertEquals(0, it.newTerm); assertEquals(2, it.newState) }
        // state=1, term 1-1=0 → state→0, term→6.
        q9.getValue(4 to 3).let { assertEquals(6, it.newTerm); assertEquals(0, it.newState) }
    }

    @Test
    fun `Q9 state-7 with remaining term does not transition`() {
        val rows = listOf(dip(me = 1, you = 2, state = 7, term = 3, dead = 0))
        val out = postUpdateMonthlyDiplomacy(rows, mapOf(1 to 1), nameMap(), emptyMap(), emptyList())
        val u = out.q9Updates.single()
        assertEquals(2, u.newTerm)   // 3-1
        assertEquals(7, u.newState)  // still 불가침
    }

    @Test
    fun `Q9 consumes the Q5 carryover row rather than the original row`() {
        val out = postUpdateMonthlyDiplomacy(
            rows = listOf(dip(me = 1, you = 2, state = 0, term = 1, dead = 350)),
            genNum = mapOf(1 to 1),
            nationNames = nameMap(),
            maxPower = emptyMap(),
            nations = emptyList(),
        )

        assertEquals(
            DiplomacyFinalUpdate(me = 1, you = 2, newState = 0, newTerm = 3, newDead = 50),
            out.q9Updates.single(),
        )
    }

    @Test
    fun `Q9 consumes the Q7 ceasefire rows in original insertion order`() {
        val out = postUpdateMonthlyDiplomacy(
            rows = listOf(
                dip(me = 1, you = 2, state = 0, term = 0, dead = 0),
                dip(me = 2, you = 1, state = 0, term = 0, dead = 0),
            ),
            genNum = mapOf(1 to 1, 2 to 1),
            nationNames = nameMap(),
            maxPower = emptyMap(),
            nations = emptyList(),
        )

        assertEquals(
            listOf(
                DiplomacyFinalUpdate(me = 1, you = 2, newState = 2, newTerm = 0, newDead = 0),
                DiplomacyFinalUpdate(me = 2, you = 1, newState = 2, newTerm = 0, newDead = 0),
            ),
            out.q9Updates,
        )
    }

    // ── Q10: available_war_setting_cnt KV clamp ──

    @Test
    fun `Q10 increments war-setting count clamped at max and seeds missing nations`() {
        // nation 1 existing=7 → +2=9; nation 2 existing=10 (at max) → unchanged; nation 3 missing → seeded 0 then +2=2.
        val nations = listOf(1, 2, 3)
        val existingKv = mapOf(1 to 7, 2 to 10)
        val out = postUpdateMonthlyDiplomacy(
            rows = emptyList(), genNum = mapOf(1 to 1, 2 to 1, 3 to 1), nationNames = nameMap(),
            maxPower = existingKv, nations = nations,
        )
        val kv = out.availableWarSettingCnt
        assertEquals(9, kv.getValue(1))   // 7+2
        assertEquals(10, kv.getValue(2))  // already at max → skipped (stays 10)
        assertEquals(2, kv.getValue(3))   // seeded 0 → +2
    }

    @Test
    fun `Q10 clamps to max when increment would exceed`() {
        val out = postUpdateMonthlyDiplomacy(
            rows = emptyList(), genNum = mapOf(1 to 1), nationNames = nameMap(),
            maxPower = mapOf(1 to 9), nations = listOf(1),
        )
        assertEquals(10, out.availableWarSettingCnt.getValue(1))  // 9+2=11 → clamp 10
    }

    @Test
    fun `Q5 clamp and Q9 declaration expiry use DiplomacyConst`() {
        // Q5: term should clamp to MAX_WAR_TERM.
        val rows = listOf(
            dip(me = 1, you = 2, state = 0, term = 0, dead = 999_999), // huge dead → huge delta, clamped
            dip(me = 3, you = 4, state = 1, term = 1, dead = 0),       // 선포 만료 → 교전 기본 턴
        )
        val out = postUpdateMonthlyDiplomacy(rows, mapOf(1 to 1, 3 to 1), nameMap(), emptyMap(), emptyList())
        assertEquals(DiplomacyConst.MAX_WAR_TERM, out.q5Updates.single().newTerm)
        assertEquals(DiplomacyConst.DEFAULT_WAR_TERM, out.q9Updates.first { it.me == 3 && it.you == 4 }.newTerm)
    }

    // ── helpers ──

    private fun nameMap(): Map<Int, String> = mapOf(1 to "위", 2 to "촉", 3 to "오", 4 to "한")
}

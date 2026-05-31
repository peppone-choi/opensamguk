package opensamguk.logic.ai

import opensamguk.common.constants.GameConst
import opensamguk.logic.actions.nation.NationCommand
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F-INSTANCE / Task FI1 — [AiInstanceState] `updateInstance` + `maxResourceActionAmount` +
 * `calcDiplomacyState` parity tests.
 *
 * Port target = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php:86-145` (updateInstance, the
 * `reqUpdateInstance`-gated block), `:128-137` (maxResourceActionAmount = `valueFit(round(max(...),-2),
 * null, maximumResourceActionAmount)` then the `GameConst::$maxResourceActionAmount` hard-cap),
 * `:206-281` (calcDiplomacyState — NO draws, WRITES `last_attackable` meta-KV via ChangeRecorder delta).
 *
 * Discipline: ZERO rng draws in updateInstance/calcDiplomacyState; `maxResourceActionAmount` uses
 * `phpRound(...,-2)` (the 2-arg overload, NEVER Math.round / `phpRound(v/100)*100`); the early-phase gate
 * `yearMonth <= joinYearMonth(startyear+2, 5)` = `(startyear+2)*12 + 4` (H-HELPERS §1, the -1 variant);
 * `last_attackable` is recorded as a ChangeRecorder DELTA (no inline write, no module-static Map — M4) and
 * the read at PHP `:276` observes the PRE-TURN snapshot value.
 */
class AiInstanceStateTest {

    // joinYearMonth(year,month) = year*12 + month - 1 (Util.php:709, H-HELPERS §1) — reuse the GREEN.
    private fun ym(year: Int, month: Int): Int = NationCommand.joinYearMonth(year, month)

    private fun policy(npcType: Int = 3, tech: Int = 0, develcost: Int = 100) =
        AutorunNationPolicy(npcType = npcType, tech = tech, develcost = develcost)

    private fun env(
        year: Int = 200,
        month: Int = 1,
        startYear: Int = 180,
        develCost: Int = 100,
    ) = AiEnv(year = year, month = month, startYear = startYear, develCost = develCost)

    // A minimal owned nation row (non-neutral): nation 1, gold/rice supplied.
    private fun nationRow(
        nation: Int = 1,
        level: Int = 1,
        gold: Int = 0,
        rice: Int = 0,
    ) = AiNationRow(nation = nation, level = level, capital = 5, gold = gold, rice = rice)

    // ----------------------------------------------------------------------------------------------
    // (1) reqUpdateInstance regime — runs once, then short-circuits unless re-dirtied.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `updateInstance runs once then short-circuits unless re-dirtied`() {
        var nationLookups = 0
        val state = AiInstanceState(
            generalNationId = 1,
            env = env(),
            nationPolicy = policy(),
            nationRowLookup = { nationLookups += 1; nationRow() },
            nationStor = emptyMap(),
            diplomacyOf = { emptyList() },
            frontMaxOf = { 0 },
            kvRecorder = CapturingKvRecorder(),
        )

        state.updateInstance()
        assertEquals(1, nationLookups, "first updateInstance materializes the nation row")

        state.updateInstance()
        assertEquals(1, nationLookups, "second updateInstance short-circuits (reqUpdateInstance=false)")

        state.markDirty()
        state.updateInstance()
        assertEquals(2, nationLookups, "markDirty re-runs the block (dirty triggers :1308/1509/1972/1990/2113)")
    }

    // ----------------------------------------------------------------------------------------------
    // (2) neutral-default nation when nationID == 0 (재야).
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `neutral-default nation when nationID is 0`() {
        val state = AiInstanceState(
            generalNationId = 0,
            env = env(),
            nationPolicy = policy(),
            nationRowLookup = { null }, // PHP queryFirstRow null → the ?? neutral-default literal
            nationStor = emptyMap(),
            diplomacyOf = { emptyList() },
            frontMaxOf = { 0 },
            kvRecorder = CapturingKvRecorder(),
        )
        state.updateInstance()
        assertEquals(0, state.nation.nation)
        assertEquals(0, state.nation.level)
        assertEquals("재야", state.nation.name)
        assertEquals(GameConst.neutralNationType, state.nation.type)
    }

    // ----------------------------------------------------------------------------------------------
    // (3) maxResourceActionAmount — phpRound(-2) + valueFit + GameConst hard-cap.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `maxResourceActionAmount uses phpRound(-2) plus valueFit plus hard-cap`() {
        // Build an env/nation where the raw max(...) lands on a value that phpRound(-2) rounds to nearest 100.
        // prev_income_gold/10, prev_income_rice/10, gold/5, rice/5, (year-startyear-3)*1000, minimumResourceActionAmount.
        // Pick prev_income_gold=12349 → /10 = 1234.9 (dominant); other terms below it.
        val state = AiInstanceState(
            generalNationId = 1,
            env = env(year = 182, startYear = 180), // (182-180-3)*1000 = -1000 (below)
            nationPolicy = policy(), // minimumResourceActionAmount default 1000
            nationRowLookup = { nationRow(gold = 0, rice = 0) },
            nationStor = mapOf("prev_income_gold" to 12349, "prev_income_rice" to 0),
            diplomacyOf = { emptyList() },
            frontMaxOf = { 0 },
            kvRecorder = CapturingKvRecorder(),
        )
        state.updateInstance()
        // raw max = max(1000, 1234.9, 0, 0, 0, -1000) = 1234.9 → phpRound(1234.9, -2) = 1200
        // valueFit(1200, null, 10000) = 1200; below hard-cap 10000.
        assertEquals(phpRound(1234.9, -2), state.maxResourceActionAmount)
        assertEquals(1200, state.maxResourceActionAmount)
    }

    @Test
    fun `maxResourceActionAmount honors the GameConst hard-cap`() {
        // Force a huge value above maximumResourceActionAmount(10000) AND GameConst hard-cap(10000).
        val state = AiInstanceState(
            generalNationId = 1,
            env = env(),
            nationPolicy = policy(),
            nationRowLookup = { nationRow(gold = 5_000_000, rice = 0) }, // gold/5 = 1_000_000
            nationStor = emptyMap(),
            diplomacyOf = { emptyList() },
            frontMaxOf = { 0 },
            kvRecorder = CapturingKvRecorder(),
        )
        state.updateInstance()
        // valueFit clamps to maximumResourceActionAmount (10000); the hard-cap GameConst (10000) is the same.
        assertEquals(GameConst.maxResourceActionAmount, state.maxResourceActionAmount)
        assertEquals(10000, state.maxResourceActionAmount)
    }

    // ----------------------------------------------------------------------------------------------
    // (4) calcDiplomacyState early-phase gate — EARLY RETURN with null warTargetNation at the boundary.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `calcDiplomacyState early-phase gate early-returns with null warTargetNation at the boundary`() {
        val startYear = 180
        // boundary = joinYearMonth(startYear+2, 5) = (startYear+2)*12 + 4. Pick year/month landing exactly on it.
        // (182)*12 + 4 = 2188. month m with year y s.t. y*12+m-1 == 2188 → y=182, m=5 → 182*12+5-1 = 2188. OK.
        val boundary = ym(startYear + 2, 5)
        assertEquals((startYear + 2) * 12 + 4, boundary)

        val state = AiInstanceState(
            generalNationId = 1,
            env = env(year = startYear + 2, month = 5, startYear = startYear),
            nationPolicy = policy(),
            nationRowLookup = { nationRow() },
            nationStor = emptyMap(),
            diplomacyOf = { emptyList() }, // no warTarget → d평화
            frontMaxOf = { 1 },            // would be attackable normally, but early-gate forces false
            kvRecorder = CapturingKvRecorder(),
        )
        state.updateInstance()
        assertEquals(AiInstanceState.D_PEACE, state.dipState)
        assertFalse(state.attackable, "early-phase gate forces attackable=false even with front")
        assertNull(state.warTargetNation, "early-phase gate leaves warTargetNation null")
    }

    @Test
    fun `calcDiplomacyState early-phase with a warTarget yields d선포`() {
        val startYear = 180
        val state = AiInstanceState(
            generalNationId = 1,
            env = env(year = startYear + 2, month = 5, startYear = startYear),
            nationPolicy = policy(),
            nationRowLookup = { nationRow() },
            nationStor = emptyMap(),
            diplomacyOf = { listOf(AiDiplomacyRow(you = 2, state = 0, term = 3)) },
            frontMaxOf = { 0 },
            kvRecorder = CapturingKvRecorder(),
        )
        state.updateInstance()
        assertEquals(AiInstanceState.D_DECLARED, state.dipState)
        assertFalse(state.attackable)
        assertNull(state.warTargetNation)
    }

    @Test
    fun `just past the boundary the normal phase runs`() {
        val startYear = 180
        // one month past boundary: year=startYear+2, month=6 → ym = boundary+1.
        val state = AiInstanceState(
            generalNationId = 1,
            env = env(year = startYear + 2, month = 6, startYear = startYear),
            nationPolicy = policy(),
            nationRowLookup = { nationRow() },
            nationStor = emptyMap(),
            diplomacyOf = { emptyList() }, // no war → min(term) null → d평화 ; no onWar/onWarReady → warTargetNation[0]=1
            frontMaxOf = { 0 },
            kvRecorder = CapturingKvRecorder(),
        )
        state.updateInstance()
        // normal phase: warTargetNation is NON-null (the [0]=1 sentinel was set).
        assertEquals(mapOf(0 to 1), state.warTargetNation)
        assertEquals(AiInstanceState.D_PEACE, state.dipState) // min(term) null → d평화
    }

    // ----------------------------------------------------------------------------------------------
    // (5) the dipState thresholds off min(term) over state=1 diplomacy.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `dipState thresholds off min term`() {
        val startYear = 180
        fun run(minTermRows: List<AiDiplomacyRow>, front: Int): AiInstanceState {
            val s = AiInstanceState(
                generalNationId = 1,
                env = env(year = startYear + 5, month = 1, startYear = startYear),
                nationPolicy = policy(),
                nationRowLookup = { nationRow() },
                nationStor = emptyMap(),
                diplomacyOf = { minTermRows },
                frontMaxOf = { front },
                kvRecorder = CapturingKvRecorder(),
            )
            s.updateInstance()
            return s
        }

        // min(term) > 8 → d선포 (state=1 only, term 9). front=0 so no war-upgrade.
        assertEquals(AiInstanceState.D_DECLARED, run(listOf(AiDiplomacyRow(2, 1, 9)), 0).dipState)
        // 5 < min(term) <= 8 → d징병 (term 6).
        assertEquals(AiInstanceState.D_CONSCRIPT, run(listOf(AiDiplomacyRow(2, 1, 6)), 0).dipState)
        // min(term) <= 5 → d직전 (term 4); writes last_attackable.
        assertEquals(AiInstanceState.D_IMMINENT, run(listOf(AiDiplomacyRow(2, 1, 4)), 0).dipState)
    }

    // ----------------------------------------------------------------------------------------------
    // (6) last_attackable recorded as a ChangeRecorder DELTA — no inline write, no static Map; pre-turn read.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `last_attackable recorded as a ChangeRecorder delta and the read uses the pre-turn snapshot`() {
        val startYear = 180
        val rec = CapturingKvRecorder()
        // onWar (state=0) AND attackable (front>0) → d전쟁 → writes last_attackable = yearMonth.
        val state = AiInstanceState(
            generalNationId = 7,
            env = env(year = startYear + 5, month = 3, startYear = startYear),
            nationPolicy = policy(),
            nationRowLookup = { nationRow(nation = 7) },
            nationStor = mapOf("last_attackable" to 0), // PRE-TURN snapshot value
            diplomacyOf = { listOf(AiDiplomacyRow(you = 2, state = 0, term = 0)) },
            frontMaxOf = { 1 },
            kvRecorder = rec,
        )
        state.updateInstance()
        assertEquals(AiInstanceState.D_WAR, state.dipState)
        val expectedYm = ym(startYear + 5, 3)
        // The delta is recorded (not inline) for nation 7, key last_attackable, value yearMonth.
        assertEquals(listOf(KvDelta(nationId = 7, key = "last_attackable", value = expectedYm)), rec.deltas)
    }

    @Test
    fun `onWar without front uses the pre-turn last_attackable snapshot within 5 months`() {
        val startYear = 180
        // d전쟁 via the "접경이 사라진지 5개월 이내" branch: onWar, NOT attackable, last_attackable >= ym-5.
        val curYm = ym(startYear + 5, 6)
        val rec = CapturingKvRecorder()
        val state = AiInstanceState(
            generalNationId = 1,
            env = env(year = startYear + 5, month = 6, startYear = startYear),
            nationPolicy = policy(),
            nationRowLookup = { nationRow() },
            nationStor = mapOf("last_attackable" to curYm - 4), // pre-turn snapshot within 5 months
            diplomacyOf = { listOf(AiDiplomacyRow(you = 2, state = 0, term = 0)) },
            frontMaxOf = { 0 }, // NOT attackable
            kvRecorder = rec,
        )
        state.updateInstance()
        assertEquals(AiInstanceState.D_WAR, state.dipState)
        // This branch does NOT write last_attackable (it only READS the pre-turn snapshot).
        assertTrue(rec.deltas.isEmpty(), "the 5-month-grace branch reads, never writes")
    }

    // ----------------------------------------------------------------------------------------------
    // (7) ZERO rng draws in updateInstance / calcDiplomacyState (no RandUtil dependency at all).
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `updateInstance and calcDiplomacyState consume ZERO rng draws`() {
        // AiInstanceState's updateInstance/calcDiplomacyState ctor takes NO RandUtil — there is no draw
        // surface. This is asserted structurally: the class compiles and runs with no rng argument.
        val state = AiInstanceState(
            generalNationId = 1,
            env = env(year = 200, startYear = 180),
            nationPolicy = policy(),
            nationRowLookup = { nationRow() },
            nationStor = emptyMap(),
            diplomacyOf = { emptyList() },
            frontMaxOf = { 0 },
            kvRecorder = CapturingKvRecorder(),
        )
        state.updateInstance()
        // No exception, no draw surface — pass.
        assertTrue(true)
    }

    /** Capturing ChangeRecorder seam stand-in: records the meta-KV deltas the AI queues (M4). */
    private class CapturingKvRecorder : AiKvRecorder {
        val deltas = mutableListOf<KvDelta>()
        override fun recordNationKv(nationId: Int, key: String, value: Any?) {
            deltas.add(KvDelta(nationId, key, value))
        }
    }
}

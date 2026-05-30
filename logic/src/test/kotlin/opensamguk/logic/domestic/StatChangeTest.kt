package opensamguk.logic.domestic

import opensamguk.logic.domain.General
import opensamguk.logic.domain.dedlevel
import opensamguk.logic.domain.explevel
import opensamguk.logic.domain.intelExp
import opensamguk.logic.domain.leadershipExp
import opensamguk.logic.domain.strengthExp
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port-faithful tests for the level-change helpers + the per-turn-finalize PLAIN level logs.
 *
 * PHP grand truth:
 *   - getExpLevel / getDedLevel / getDedLevelText / getBillByLevel : func_converter.php:631-670
 *   - GameConst::$upgradeLimit=30 / $maxDedLevel=30 / $maxLevel=255  : GameConstBase.php:18/80/95
 *   - checkStatChange (per-turn finalize)                           : General.php:753-785
 *   - addExperience / addDedication                                  : General.php:448-495
 *
 * The P1 pipeline is empty (identity); addExperience/addDedication fold the increment through
 * onCalcStat('experience'|'dedication', value) BEFORE increaseVar — a personality *1.1 module
 * proves the fold lands ahead of the accumulate.
 */
class StatChangeTest {

    private val pipeline = GeneralActionPipeline()

    private fun general(
        leadership: Int = 70, strength: Int = 70, intel: Int = 70,
        experience: Double = 0.0, dedication: Double = 0.0,
        explevel: Int = 0, dedlevel: Int = 0,
        leadershipExp: Double = 0.0, strengthExp: Double = 0.0, intelExp: Double = 0.0,
    ) = General(
        id = 1, nationId = 1, cityId = 1,
        leadership = leadership, strength = strength, intel = intel, injury = 0,
        experience = experience, dedication = dedication, officerLevel = 0,
        gold = 1000, rice = 1000,
        meta = linkedMapOf(
            "explevel" to explevel, "dedlevel" to dedlevel,
            "leadership_exp" to leadershipExp, "strength_exp" to strengthExp, "intel_exp" to intelExp,
        ),
    )

    // === level-conversion helpers (func_converter.php) ===

    @Test
    fun `getExpLevel below 1000 is intdiv by 100`() {
        assertEquals(0, getExpLevel(0.0))
        assertEquals(0, getExpLevel(99.0))
        assertEquals(1, getExpLevel(100.0))
        assertEquals(9, getExpLevel(999.0))
    }

    @Test
    fun `getExpLevel at and above 1000 is toInt sqrt of exp over 10`() {
        // 1000 -> toInt(sqrt(100)) = 10 ; 1690 -> toInt(sqrt(169)) = 13
        assertEquals(10, getExpLevel(1000.0))
        assertEquals(13, getExpLevel(1690.0))
    }

    @Test
    fun `getExpLevel clamps to maxLevel 255`() {
        // sqrt(exp/10) >= 255 at exp >= 650250
        assertEquals(255, getExpLevel(1_000_000.0))
    }

    @Test
    fun `getDedLevel is ceil sqrt over 10 clamped to maxDedLevel 30`() {
        assertEquals(0, getDedLevel(0.0))
        // sqrt(100)=10 -> ceil(1.0)=1
        assertEquals(1, getDedLevel(100.0))
        // sqrt(101)=10.0499 -> /10 = 1.005 -> ceil = 2
        assertEquals(2, getDedLevel(101.0))
        // very large -> clamp 30
        assertEquals(30, getDedLevel(1_000_000.0))
    }

    @Test
    fun `getDedLevelText 0 is 무품관 else inverted 품관`() {
        assertEquals("무품관", getDedLevelText(0))
        // maxDedLevel 30: level 1 -> 30품관 ; level 30 -> 1품관
        assertEquals("30품관", getDedLevelText(1))
        assertEquals("1품관", getDedLevelText(30))
    }

    @Test
    fun `getBillByLevel is level times 200 plus 400`() {
        assertEquals(400, getBillByLevel(0))
        assertEquals(600, getBillByLevel(1))
        assertEquals(6400, getBillByLevel(30))
    }

    @Test
    fun `level consts match GameConst`() {
        assertEquals(30, UPGRADE_LIMIT)
        assertEquals(30, MAX_DED_LEVEL)
        assertEquals(255, MAX_LEVEL)
    }

    // === checkStatChange (General.php:753-785) ===

    @Test
    fun `checkStatChange up exp ge 30 raises stat by 1 caps 255 subtracts 30 unconditionally and logs`() {
        // leadership_exp = 35 -> >= 30 : leadership 70 -> 71, exp 35 -> 5, PLAIN up log
        val g = general(leadership = 70, leadershipExp = 35.0)
        val r = checkStatChange(g)
        assertEquals(71, r.general.leadership)
        assertEquals(5.0, r.general.leadershipExp())
        assertEquals(listOf("<S>통솔</>이 <C>1</> 올랐습니다!"), r.plainLogs)
    }

    @Test
    fun `checkStatChange down exp lt 0 lowers stat by 1 adds 30 and logs`() {
        // strength_exp = -2 -> < 0 : strength 70 -> 69, exp -2 -> 28, PLAIN down log
        val g = general(strength = 70, strengthExp = -2.0)
        val r = checkStatChange(g)
        assertEquals(69, r.general.strength)
        assertEquals(28.0, r.general.strengthExp())
        assertEquals(listOf("<R>무력</>이 <C>1</> 떨어졌습니다!"), r.plainLogs)
    }

    @Test
    fun `checkStatChange up at maxLevel does not raise stat or log but still subtracts 30`() {
        // intel already at 255: exp 40 >= 30 -> NO stat change, NO log, but exp 40 -> 10
        val g = general(intel = 255, intelExp = 40.0)
        val r = checkStatChange(g)
        assertEquals(255, r.general.intel)
        assertEquals(10.0, r.general.intelExp())
        assertTrue(r.plainLogs.isEmpty(), "no up-log when stat already at maxLevel")
    }

    @Test
    fun `checkStatChange iterates 통솔 무력 지력 in order`() {
        // all three trip the up-branch -> logs in leadership, strength, intel order
        val g = general(
            leadership = 70, strength = 70, intel = 70,
            leadershipExp = 30.0, strengthExp = 30.0, intelExp = 30.0,
        )
        val r = checkStatChange(g)
        assertEquals(71, r.general.leadership)
        assertEquals(71, r.general.strength)
        assertEquals(71, r.general.intel)
        assertEquals(
            listOf(
                "<S>통솔</>이 <C>1</> 올랐습니다!",
                "<S>무력</>이 <C>1</> 올랐습니다!",
                "<S>지력</>이 <C>1</> 올랐습니다!",
            ),
            r.plainLogs,
        )
    }

    @Test
    fun `checkStatChange no change leaves stats and emits no log`() {
        val g = general(leadershipExp = 10.0, strengthExp = 0.0, intelExp = 29.0)
        val r = checkStatChange(g)
        assertEquals(70, r.general.leadership)
        assertEquals(70, r.general.strength)
        assertEquals(70, r.general.intel)
        assertEquals(10.0, r.general.leadershipExp())
        assertEquals(0.0, r.general.strengthExp())
        assertEquals(29.0, r.general.intelExp())
        assertTrue(r.plainLogs.isEmpty())
    }

    // === addExperience (General.php:448-469) — fold THEN accumulate THEN level compare ===

    @Test
    fun `addExperience folds through onCalcStat experience before increaseVar`() {
        // a personality-style module multiplies the experience increment by 1.1.
        val mod = object : GeneralActionModule {
            override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
                if (statName == "experience") value * 1.1 else value
        }
        val p = GeneralActionPipeline(listOf(mod))
        // increment 100 -> folded to 100*1.1 -> experience 0 -> 100*1.1 (NOT raw 100; IEEE 110.0000…1)
        val g = general(experience = 0.0, explevel = 0)
        val r = addExperience(g, 100.0, p)
        assertEquals(100.0 * 1.1, r.general.experience)
    }

    @Test
    fun `addExperience level boundary cross emits exact PLAIN levelup log with josa ro`() {
        // experience 90 -> +20 = 110 -> getExpLevel(110)=1 ; stored explevel 0 -> comp>0 -> levelup
        val g = general(experience = 90.0, explevel = 0)
        val r = addExperience(g, 20.0, pipeline)
        assertEquals(110.0, r.general.experience)
        assertEquals(1, r.general.explevel())
        // JosaUtil.pick("1","로") -> "로" (digit 1 has jongsung but rieul -> isRo strips it) ... actually 1 -> 일 jongsung -> "로"
        assertEquals("<C>Lv 1</>로 <C>레벨업</>!", r.plainLog)
    }

    @Test
    fun `addExperience level down emits exact PLAIN leveldown log`() {
        // experience 100 (level 1), negative folded increment drops below 100 -> level 0 -> comp<0 -> leveldown
        // JosaUtil.pick("0","로"): digit 0 has jongsung (not rieul) -> isRo keeps it -> "으로"
        // PHP General.php:467 — 레벨다운 wraps in <R> (red), NOT <C>.
        val g = general(experience = 100.0, explevel = 1)
        val r = addExperience(g, -5.0, pipeline)
        assertEquals(95.0, r.general.experience)
        assertEquals(0, r.general.explevel())
        assertEquals("<C>Lv 0</>으로 <R>레벨다운</>!", r.plainLog)
    }

    @Test
    fun `addExperience no level change emits no log`() {
        // experience 100 (level 1) -> +50 = 150 -> still level 1 -> comp==0 -> no log, no explevel write
        val g = general(experience = 100.0, explevel = 1)
        val r = addExperience(g, 50.0, pipeline)
        assertEquals(150.0, r.general.experience)
        assertEquals(1, r.general.explevel())
        assertNull(r.plainLog)
    }

    @Test
    fun `addExperience caps level at maxLevel`() {
        // experience already at the maxLevel plateau; a big increment cannot exceed level 255
        val g = general(experience = 650_250.0, explevel = 255)
        val r = addExperience(g, 1_000_000.0, pipeline)
        assertEquals(255, r.general.explevel())
        assertNull(r.plainLog)
    }

    // === addDedication (General.php:471-495) — fold THEN accumulate THEN level compare ===

    @Test
    fun `addDedication folds through onCalcStat dedication before increaseVar`() {
        val mod = object : GeneralActionModule {
            override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
                if (statName == "dedication") value * 1.1 else value
        }
        val p = GeneralActionPipeline(listOf(mod))
        val g = general(dedication = 0.0, dedlevel = 0)
        val r = addDedication(g, 100.0, p)
        assertEquals(100.0 * 1.1, r.general.dedication)
    }

    @Test
    fun `addDedication 승급 emits exact PLAIN log with josa ro on dedText and bill`() {
        // dedication 0 (level 0) -> +100 = 100 -> getDedLevel(100)=1 ; level 0 -> comp>0 -> 승급
        // dedLevelText(1)="30품관" ; billByLevel(1)=600 ; number_format(600)="600"
        // josa로 on "30품관" (관 jongsung) -> "으로" ; josa로 on "600" (0 jongsung) -> "으로"
        val g = general(dedication = 0.0, dedlevel = 0)
        val r = addDedication(g, 100.0, pipeline)
        assertEquals(100.0, r.general.dedication)
        assertEquals(1, r.general.dedlevel())
        assertEquals(
            "<Y>30품관</>으로 <C>승급</>하여 봉록이 <C>600</>으로 <C>상승</>했습니다!",
            r.plainLog,
        )
    }

    @Test
    fun `addDedication 강등 emits exact PLAIN downgrade log`() {
        // getDedLevel(d)=ceil(sqrt(d)/10): level 0 ONLY at d==0, level 1 across (0,100]. To 강등 to level 0
        // dedication must reach exactly 0 -> start 3 (level 1), increment -3 -> 0 -> level 0 -> comp<0.
        // dedLevelText(0)="무품관" ; billByLevel(0)=400 ; number_format(400)="400"
        // josa로 on "무품관" (관) -> "으로" ; josa로 on "400" (0) -> "으로"
        val g = general(dedication = 3.0, dedlevel = 1)
        val r = addDedication(g, -3.0, pipeline)
        assertEquals(0.0, r.general.dedication)
        assertEquals(0, r.general.dedlevel())
        assertEquals(
            "<Y>무품관</>으로 <R>강등</>되어 봉록이 <C>400</>으로 <R>하락</>했습니다!",
            r.plainLog,
        )
    }

    @Test
    fun `addDedication bill josa ro varies with the formatted number tail`() {
        // Drive a level whose number_format(bill) ends in a digit with NO jongsung so josa로 -> "로".
        // billByLevel(2) = 800 ... ends in 0 (jongsung). Find a level whose bill ends with a no-jongsung digit.
        // billByLevel(7) = 1800 -> "1,800" ends in 0. billByLevel(4)=1200 -> "1,200".
        // billByLevel(? ): 200*L+400. Tail digit cycles 0 always -> bill always ends in 00. So bill josa로 is always "으로".
        // Instead assert the dedText path on a non-관-ending text is impossible (all are "N품관"/"무품관" -> 관),
        // so BOTH josa로 are "으로" for every dedication level — pin that invariant.
        for (lvl in 1..5) {
            val text = getDedLevelText(lvl)
            assertTrue(text.endsWith("품관"), "dedText always ends 품관")
        }
        // and bill always ends in two zeros -> formatted tail digit 0 -> jongsung -> "으로"
        for (lvl in 0..5) {
            assertTrue(getBillByLevel(lvl) % 100 == 0, "bill is always a multiple of 100")
        }
    }

    @Test
    fun `addDedication no level change emits no log`() {
        // dedication 100 (level 1) +10 = 110 -> getDedLevel(110)= ceil(sqrt(110)/10)= ceil(1.0488)=2 ... that changes.
        // use +0.5 -> 100.5 -> sqrt=10.025 -> /10=1.0025 -> ceil=2. Hmm. Pick a window that stays level 1:
        // level 1 window: dedication in (0,100] keeps ceil(sqrt(d)/10)=1. Start 50 (level 1), +20 -> 70 -> still level 1.
        val g = general(dedication = 50.0, dedlevel = 1)
        val r = addDedication(g, 20.0, pipeline)
        assertEquals(70.0, r.general.dedication)
        assertEquals(1, r.general.dedlevel())
        assertNull(r.plainLog)
    }
}

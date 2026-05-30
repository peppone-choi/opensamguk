package opensamguk.logic.war.specialty

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.General
import opensamguk.logic.war.WarStatName
import opensamguk.logic.war.specialty.triggers.CheBangyeBaldong
import opensamguk.logic.war.specialty.triggers.CheBangyeSido
import opensamguk.logic.war.specialty.triggers.CheBusangMuhyo
import opensamguk.logic.war.specialty.triggers.CheFilsalGanghwaHoipibulga
import opensamguk.logic.war.specialty.triggers.CheJeontuChiryoBaldong
import opensamguk.logic.war.specialty.triggers.CheJeontuChiryoSido
import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.WarUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port-faithful tests for AW2 — the stat-delta war specialties (`ActionSpecialWar/che_*.php`
 * `onCalcStat` / `onCalcOpposeStat` / `onCalcStatRange` / injection at the documented priority).
 *
 * Grand truth: 필살 +0.30 + range rewrite [(min+max)/2,max]; 견고 oppose −0.1/−0.20 + 부상무효 DEDUP*404;
 * 신중 +1 → prob≥1 (the downstream nextBool short-circuits to NO-draw); 집중 ×1.5 / 환술 ×1.3 multiplicative;
 * 신산 +0.2 trial/success; aux-conditional 반계 +0.9 only when magic=='반목'; 귀병/궁병 dex-fold + magic/avoid;
 * 징병 leadership +25%. ZERO RNG in any hook.
 */
class WarSpecialtyStatTest {

    private fun general(leadership: Int = 80, strength: Int = 60, intel: Int = 90) = General(
        id = 1, nationId = 1, cityId = 5,
        leadership = leadership, strength = strength, intel = intel, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 1,
        gold = 1000, rice = 1000,
    )

    private val g = general()
    private val fakeUnit: WarUnit = object : WarUnit {
        override val rng: RandUtil = RandUtil(LiteHashDrbg("00"))
        override fun getUnitId(): String = "G1"
        override fun isAttacker(): Boolean = true
        override fun getPhase(): Int = 0
        override fun hasActivatedSkill(skillName: String): Boolean = false
        override fun activateSkill(vararg skillNames: String) {}
        override fun multiplyWarPowerMultiply(multiply: Double) {}
        override fun criticalDamage(): Double = 1.7
        override fun getComputedCriticalRatio(): Double = 0.0
        override fun getComputedAvoidRatio(): Double = 0.0
        override fun pushBattleDetailLog(message: String) {}
        override fun pushPlainActionLog(message: String) {}
        override fun getItemName(): String = "item"
        override fun getItemRawName(): String = "item"
        override fun deleteItem() {}
        override fun isGeneral(): Boolean = true
        override fun isCity(): Boolean = false
        override fun getMagicTrialProb(oppose: WarUnit): Double = 0.0
        override fun getMagicSuccessProb(oppose: WarUnit): Double = 0.7
        override fun foldMagicSuccessDamage(oppose: WarUnit, magic: String, raw: Double): Double = raw
        override fun foldMagicFailDamage(oppose: WarUnit, magic: String, raw: Double): Double = raw
    }

    // --- che_필살 ---------------------------------------------------------------------------------------
    @Test
    fun `필살 warCriticalRatio +0_30 and criticalDamageRange rewrite to mid-max`() {
        assertEquals(0.30, ChePilsal.onCalcStat(g, WarStatName.WAR_CRITICAL_RATIO, 0.0), 1e-12)
        // unrelated key untouched
        assertEquals(0.5, ChePilsal.onCalcStat(g, WarStatName.WAR_AVOID_RATIO, 0.5), 1e-12)
        // [1.3, 2.0] → [(1.3+2.0)/2, 2.0] = [1.65, 2.0]
        val (lo, hi) = ChePilsal.onCalcStatRange(g, WarStatName.CRITICAL_DAMAGE_RANGE, 1.3 to 2.0)
        assertEquals(1.65, lo, 1e-12)
        assertEquals(2.0, hi, 1e-12)
    }

    @Test
    fun `필살 injects 필살강화_회피불가 at PRE+150`() {
        assertFalse(ChePilsal.getBattlePhaseSkillTriggerList(fakeUnit).isEmpty())
        assertEquals(ObjectTrigger.PRIORITY_PRE + 150, CheFilsalGanghwaHoipibulga(fakeUnit).priority)
    }

    // --- che_견고 ---------------------------------------------------------------------------------------
    @Test
    fun `견고 oppose magic-success −0_1 and crit −0_20, other keys identity`() {
        assertEquals(0.6, CheGyeongo.onCalcOpposeStat(g, WarStatName.WAR_MAGIC_SUCCESS_PROB, 0.7), 1e-12)
        assertEquals(0.10, CheGyeongo.onCalcOpposeStat(g, WarStatName.WAR_CRITICAL_RATIO, 0.30), 1e-12)
        assertEquals(0.4, CheGyeongo.onCalcOpposeStat(g, WarStatName.WAR_AVOID_RATIO, 0.4), 1e-12)
        // own-side untouched
        assertEquals(0.7, CheGyeongo.onCalcStat(g, WarStatName.WAR_MAGIC_SUCCESS_PROB, 0.7), 1e-12)
    }

    @Test
    fun `견고 getWarPowerMultiplier is 1, 0_9 and injects 부상무효 with DEDUP times 404`() {
        assertEquals(1.0 to 0.9, CheGyeongo.getWarPowerMultiplier(fakeUnit))
        assertFalse(CheGyeongo.getBattleInitSkillTriggerList(fakeUnit).isEmpty())
        val expectedRaiseType = BaseWarUnitTrigger.TYPE_NONE + BaseWarUnitTrigger.TYPE_DEDUP_TYPE_BASE * 404
        // The injected 부상무효 must carry the DEDUP*404 raiseType so its uniqueID does not collapse.
        val plain = CheBusangMuhyo(fakeUnit)
        val dedup = CheBusangMuhyo(fakeUnit, expectedRaiseType)
        assertEquals(ObjectTrigger.PRIORITY_BEGIN + 200, dedup.priority)
        assertTrue(plain.getUniqueID() != dedup.getUniqueID(), "raiseType suffix must differ")
    }

    // --- che_신중 / 집중 / 환술 -------------------------------------------------------------------------
    @Test
    fun `신중 warMagicSuccessProb +1 reaches 1_0 or more so nextBool short-circuits NO-draw`() {
        val p = CheSinjung.onCalcStat(g, WarStatName.WAR_MAGIC_SUCCESS_PROB, 0.7)
        assertEquals(1.7, p, 1e-12)
        assertTrue(p >= 1.0, "prob>=1 → nextBool short-circuits true with NO draw")
    }

    @Test
    fun `집중 and 환술 are multiplicative on warMagicSuccessDamage`() {
        assertEquals(1.5, CheJipjung.onCalcStat(g, WarStatName.WAR_MAGIC_SUCCESS_DAMAGE, 1.0), 1e-12)
        assertEquals(1.3, CheHwansul.onCalcStat(g, WarStatName.WAR_MAGIC_SUCCESS_DAMAGE, 1.0), 1e-12)
        assertEquals(0.8, CheHwansul.onCalcStat(g, WarStatName.WAR_MAGIC_SUCCESS_PROB, 0.7), 1e-12)
    }

    // --- che_신산 ---------------------------------------------------------------------------------------
    @Test
    fun `신산 +0_2 trial and success, domestic 계략 success +0_1`() {
        assertEquals(0.2, CheSinsan.onCalcStat(g, WarStatName.WAR_MAGIC_TRIAL_PROB, 0.0), 1e-12)
        assertEquals(0.9, CheSinsan.onCalcStat(g, WarStatName.WAR_MAGIC_SUCCESS_PROB, 0.7), 1e-12)
        assertEquals(0.6, CheSinsan.onCalcDomestic(g, "계략", "success", 0.5), 1e-12)
        assertEquals(0.5, CheSinsan.onCalcDomestic(g, "농업", "success", 0.5), 1e-12)
    }

    // --- che_반계 (aux-conditional) --------------------------------------------------------------------
    @Test
    fun `반계 warMagicSuccessDamage +0_9 ONLY when magic is 반목`() {
        assertEquals(1.9, CheBangye.onCalcStat(g, WarStatName.WAR_MAGIC_SUCCESS_DAMAGE, 1.0, mapOf("magic" to "반목")), 1e-12)
        assertEquals(1.0, CheBangye.onCalcStat(g, WarStatName.WAR_MAGIC_SUCCESS_DAMAGE, 1.0, mapOf("magic" to "화계")), 1e-12)
        assertEquals(1.0, CheBangye.onCalcStat(g, WarStatName.WAR_MAGIC_SUCCESS_DAMAGE, 1.0), 1e-12)
        // oppose magic-success −0.1
        assertEquals(0.6, CheBangye.onCalcOpposeStat(g, WarStatName.WAR_MAGIC_SUCCESS_PROB, 0.7), 1e-12)
    }

    @Test
    fun `반계 injects 반계시도 BODY+300 and 반계발동 POST+250`() {
        assertFalse(CheBangye.getBattlePhaseSkillTriggerList(fakeUnit).isEmpty())
        assertEquals(ObjectTrigger.PRIORITY_BODY + 300, CheBangyeSido(fakeUnit).priority)
        assertEquals(ObjectTrigger.PRIORITY_POST + 250, CheBangyeBaldong(fakeUnit).priority)
    }

    // --- che_의술 (injection) --------------------------------------------------------------------------
    @Test
    fun `의술 injects 전투치료시도 PRE+350 and 전투치료발동 POST+550`() {
        assertFalse(CheUisul.getBattlePhaseSkillTriggerList(fakeUnit).isEmpty())
        assertEquals(ObjectTrigger.PRIORITY_PRE + 350, CheJeontuChiryoSido(fakeUnit).priority)
        assertEquals(ObjectTrigger.PRIORITY_POST + 550, CheJeontuChiryoBaldong(fakeUnit).priority)
    }

    // --- che_귀병 / 궁병 (magic/avoid + dex-fold + recruit cost) ---------------------------------------
    @Test
    fun `귀병 magic-success +0_2 and 궁병 avoid +0_2`() {
        assertEquals(0.9, CheGwibyeong.onCalcStat(g, WarStatName.WAR_MAGIC_SUCCESS_PROB, 0.7), 1e-12)
        assertEquals(0.7, CheGungbyeong.onCalcStat(g, WarStatName.WAR_AVOID_RATIO, 0.5), 1e-12)
    }

    @Test
    fun `궁병 recruit cost ×0_9 only for matching armType`() {
        assertEquals(90.0, CheGungbyeong.onCalcDomestic(g, "징병", "cost", 100.0, mapOf("armType" to GameUnitConst.T_ARCHER)), 1e-12)
        assertEquals(100.0, CheGungbyeong.onCalcDomestic(g, "징병", "cost", 100.0, mapOf("armType" to GameUnitConst.T_CAVALRY)), 1e-12)
    }

    @Test
    fun `궁병 dex-fold adds my dex to opposeType key as attacker`() {
        // meta carries the per-armType dex accumulator
        val gd = general().copy(meta = mapOf(WarStatName.dexKey(GameUnitConst.T_ARCHER) to 12.0))
        // crewType of the OPPOSING unit = footman (armType 1); attacker → key 'dex1' gets my dexArcher added.
        val opposeType = GameUnitConst.byId(1100)!!   // 보병 (armType = T_FOOTMAN)
        assertEquals(GameUnitConst.T_FOOTMAN, opposeType.armType)
        val aux = mapOf("isAttacker" to true, "opposeType" to opposeType)
        val key = WarStatName.dexKey(GameUnitConst.T_FOOTMAN)
        assertEquals(112.0, CheGungbyeong.onCalcStat(gd, key, 100.0, aux), 1e-9)
        // a non-matching dex key is identity
        val other = WarStatName.dexKey(GameUnitConst.T_CAVALRY)
        assertEquals(100.0, CheGungbyeong.onCalcStat(gd, other, 100.0, aux), 1e-9)
    }

    // --- che_징병 -------------------------------------------------------------------------------------
    @Test
    fun `징병 leadership +25% pure-stat boost`() {
        // leadership 80 → +80*0.25 = +20
        assertEquals(120.0, CheJingbyeong.onCalcStat(g, "leadership", 100.0), 1e-9)
        assertEquals(70.0, CheJingbyeong.onCalcDomestic(g, "징병", "train", 0.0), 1e-12)
        assertEquals(84.0, CheJingbyeong.onCalcDomestic(g, "모병", "atmos", 0.0), 1e-12)
        assertEquals(0.0, CheJingbyeong.onCalcDomestic(g, "징집인구", "score", 999.0), 1e-12)
    }
}

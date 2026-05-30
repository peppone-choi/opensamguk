package opensamguk.logic.war.trigger

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.GameUnitDetail
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.war.trigger.triggers.CheBangeoryeokJeunga5p
import opensamguk.logic.war.trigger.triggers.CheBangyeBaldong
import opensamguk.logic.war.trigger.triggers.CheBangyeSido
import opensamguk.logic.war.trigger.triggers.CheBusangMuhyo
import opensamguk.logic.war.trigger.triggers.CheDolgyeokJisok
import opensamguk.logic.war.trigger.triggers.CheGabyeongByeongjongJeontu
import opensamguk.logic.war.trigger.triggers.CheGyeonoBaldong
import opensamguk.logic.war.trigger.triggers.CheGyeonoSido
import opensamguk.logic.war.trigger.triggers.CheJeonjuSido
import opensamguk.logic.war.trigger.triggers.CheJeonjuBaldong
import opensamguk.logic.war.trigger.triggers.CheJeonmyeolsiPhaseJeunga
import opensamguk.logic.war.trigger.triggers.CheJeontuChiryoBaldong
import opensamguk.logic.war.trigger.triggers.CheJeontuChiryoSido
import opensamguk.logic.war.trigger.triggers.CheFilsalGanghwaHoipiBulga
import opensamguk.logic.war.trigger.triggers.CheSeongbyeokBusangMuhyo
import opensamguk.logic.war.trigger.triggers.CheSeonjeSagyeokBaldong
import opensamguk.logic.war.trigger.triggers.CheSeonjeSagyeokSido
import opensamguk.logic.war.trigger.triggers.CheGungbyeongSeonjeSagyeok
import opensamguk.logic.war.trigger.triggers.CheToegakBusangMuhyo
import opensamguk.logic.war.trigger.triggers.CheWiapBaldong
import opensamguk.logic.war.trigger.triggers.CheWiapSido
import opensamguk.logic.war.trigger.triggers.CheYaktalBaldong
import opensamguk.logic.war.trigger.triggers.CheYaktalSido
import opensamguk.logic.war.trigger.triggers.CheJeogyeokBaldong
import opensamguk.logic.war.trigger.triggers.CheJeogyeokSido
import opensamguk.logic.war.trigger.triggers.EventChungchaItemConsume
import opensamguk.logic.war.trigger.triggers.NeungryeokchiByeongyeong
import opensamguk.logic.war.trigger.triggers.JeontuRyeokBojeong
import opensamguk.logic.war.trigger.triggers.WarActivateSkills
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port target = PHP `WarUnitTrigger` `*.php` (the 29 catalog leaves beyond the FT0 base-7), tabulated in
 * `docs/superpowers/research/2026-05-30-p4-war-trigger-draw-catalog.md` §3 (rows 1-36 minus the base-7).
 *
 * Pins each priority EXACTLY (the draw-order slot) + the DRAW STRUCTURE (the F1 parity surface): which
 * triggers draw `nextBool` / `nextRangeInt` / `criticalDamage()`, the no-draw short-circuits, the
 * `stopNextAction` halt (저지발동), `setWarPowerMultiply(0)` (위압발동 makes calcDamage still draw), and the
 * processConsumableItem delete-delta (저격발동/전투치료발동/약탈발동/능력치변경/전투력보정).
 *
 * Value parity of folded probabilities / theft amounts is delegated to the F2/F5/A1 impl and asserted
 * draw-for-draw at G1; here we pin only the priorities, draw COUNT, and order.
 */
class ExtendedTriggersTest {

    private val archer: GameUnitDetail = GameUnitConst.byId(1200)!!
    private val footman: GameUnitDetail = GameUnitConst.byId(1100)!!
    private val castle: GameUnitDetail = GameUnitConst.byId(GameUnitConst.CREWTYPE_CASTLE)!!

    /** A FakeWarUnit covering the A3-widened [WarUnit] contract; records var/skill side-effects. */
    private class FakeWarUnit(
        private val id: String,
        private val attacker: Boolean,
        private val isGenFlag: Boolean = true,
        private val isCityUnit: Boolean = false,
        private val crew: GameUnitDetail,
        private val phaseField: Int = 0,
        private val maxPhaseField: Int = 5,
        private val atmos: Double = 70.0,
        private val train: Double = 70.0,
        private val warPowerField: Double = 1000.0,
        private val recorder: MutableList<String>,
        private val boolScript: ArrayDeque<Boolean>,
        private val intScript: ArrayDeque<Int> = ArrayDeque(),
    ) : WarUnit {
        val activatedSkills = linkedMapOf<String, Boolean>()
        val logSkills = linkedMapOf<String, Int>()
        val multiplies = mutableListOf<Double>()
        var warPowerMul: Double = 1.0
        var bonusPhaseDelta: Int = 0
        var phaseDelta: Int = 0
        var atmosDelta: Int = 0
        var injurySet: Int = -1
        var injuryDelta: Int = 0
        var ramRemain: Int = 0
        var deletedItem: Boolean = false
        var blockRewardApplied: Boolean = false
        var theftRatioApplied: Double? = null
        var critDamageDrawn: Boolean = false

        override val rng: RandUtil = ScriptedRng(recorder, boolScript, intScript)
        override fun getUnitId(): String = id
        override fun isAttacker(): Boolean = attacker
        override fun getPhase(): Int = phaseField
        override fun hasActivatedSkill(skillName: String): Boolean = activatedSkills[skillName] ?: false
        override fun activateSkill(vararg skillNames: String) { for (s in skillNames) activatedSkills[s] = true }
        override fun deactivateSkill(vararg skillNames: String) { for (s in skillNames) activatedSkills[s] = false }
        override fun hasActivatedSkillOnLog(skillName: String): Int =
            (logSkills[skillName] ?: 0) + (if (hasActivatedSkill(skillName)) 1 else 0)
        override fun multiplyWarPowerMultiply(multiply: Double) { multiplies.add(multiply); warPowerMul *= multiply }
        override fun setWarPowerMultiply(multiply: Double) { warPowerMul = multiply }
        override fun getWarPower(): Double = warPowerField * warPowerMul
        override fun criticalDamage(): Double { recorder.add("criticalDamage"); critDamageDrawn = true; return 1.7 }
        override fun getComputedCriticalRatio(): Double = 0.0
        override fun getComputedAvoidRatio(): Double = 0.0
        override fun getComputedAtmos(): Double = atmos
        override fun getComputedTrain(): Double = train
        override fun getMaxPhase(): Int = maxPhaseField
        override fun addPhase(phase: Int) { phaseDelta += phase }
        override fun addBonusPhase(cnt: Int) { bonusPhaseDelta += cnt }
        override fun getCrewType(): GameUnitDetail = crew
        override fun isGeneralUnit(): Boolean = isGenFlag
        override fun pushBattleDetailLog(message: String) {}
        override fun pushPlainActionLog(message: String) {}
        override fun getItemName(): String = "item"
        override fun getItemRawName(): String = "item"
        override fun deleteItem() { deletedItem = true }
        override fun isGeneral(): Boolean = isGenFlag
        override fun isCity(): Boolean = isCityUnit
        override fun getMagicTrialProb(oppose: WarUnit): Double = 0.0
        override fun getMagicSuccessProb(oppose: WarUnit): Double = 0.7
        override fun foldMagicSuccessDamage(oppose: WarUnit, magic: String, raw: Double): Double = raw
        override fun foldMagicFailDamage(oppose: WarUnit, magic: String, raw: Double): Double = raw
        override fun applyVarChange(variable: String, operator: String, value: Double, limitMin: Double?, limitMax: Double?) {}
        override fun increaseAtmos(delta: Int) { atmosDelta += delta }
        override fun increaseInjury(amount: Int) { injuryDelta += amount }
        override fun decreaseAtmos(delta: Int, min: Int) { atmosDelta -= delta }
        override fun stealFrom(oppose: WarUnit, theftRatio: Double): Pair<Double, Double> { theftRatioApplied = theftRatio; return 0.0 to 0.0 }
        override fun clearInjury() { injurySet = 0 }
        override fun applyBlockReward(oppose: WarUnit) { blockRewardApplied = true }
        override fun getSiegeRamRemain(): Int = ramRemain
        override fun setSiegeRamRemain(value: Int) { ramRemain = value }
    }

    private class ScriptedRng(
        private val recorder: MutableList<String>,
        private val boolScript: ArrayDeque<Boolean>,
        private val intScript: ArrayDeque<Int>,
    ) : RandUtil(LiteHashDrbg("00")) {
        override fun nextBool(prob: Double): Boolean {
            recorder.add("nextBool($prob)")
            return boolScript.removeFirstOrNull() ?: (prob >= 1.0)
        }
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int {
            recorder.add("nextRangeInt($minInclusive,$maxInclusive)")
            return intScript.removeFirstOrNull() ?: minInclusive
        }
    }

    private fun rng() = RandUtil(LiteHashDrbg("00"))

    private fun newUnit(
        id: String, attacker: Boolean, rec: MutableList<String>,
        crew: GameUnitDetail = footman,
        isGeneralUnit: Boolean = true, isCityUnit: Boolean = false,
        phase: Int = 0, maxPhase: Int = 5, atmos: Double = 70.0, train: Double = 70.0, warPower: Double = 1000.0,
        boolScript: ArrayDeque<Boolean> = ArrayDeque(), intScript: ArrayDeque<Int> = ArrayDeque(),
    ) = FakeWarUnit(id, attacker, isGeneralUnit, isCityUnit, crew, phase, maxPhase, atmos, train, warPower, rec, boolScript, intScript)

    // ============================ priorities (every catalog row exact) ============================
    @Test
    fun `all 29 trigger priorities match the catalog exactly`() {
        val rec = mutableListOf<String>()
        val u = newUnit("u", true, rec)
        val B = ObjectTrigger.PRIORITY_BEGIN
        val P = ObjectTrigger.PRIORITY_PRE
        val BD = ObjectTrigger.PRIORITY_BODY
        val PO = ObjectTrigger.PRIORITY_POST
        val F = ObjectTrigger.PRIORITY_FINAL

        assertEquals(B, WarActivateSkills(u, 0, true, "특수").priority)
        assertEquals(B + 10, NeungryeokchiByeongyeong(u, 0, "atmos", "+", 5.0).priority)
        assertEquals(B + 20, JeontuRyeokBojeong(u, 1.1, 0.9).priority)
        assertEquals(B + 50, CheSeonjeSagyeokSido(u).priority)
        assertEquals(B + 50, CheGungbyeongSeonjeSagyeok(u).priority)
        assertEquals(B + 51, CheSeonjeSagyeokBaldong(u).priority)
        assertEquals(B + 100, CheWiapSido(u).priority)
        assertEquals(B + 150, CheSeongbyeokBusangMuhyo(u).priority)
        assertEquals(B + 200, CheBusangMuhyo(u, 0).priority)
        assertEquals(B + 300, CheToegakBusangMuhyo(u).priority)

        assertEquals(P + 0, CheJeonjuSido(u).priority)
        assertEquals(P + 100, CheJeogyeokSido(u, 0, 0.5, 20.0, 40.0).priority)
        assertEquals(P + 150, CheFilsalGanghwaHoipiBulga(u).priority)
        assertEquals(P + 200, EventChungchaItemConsume(u).priority)
        assertEquals(P + 350, CheJeontuChiryoSido(u).priority)
        assertEquals(P + 400, CheYaktalSido(u, 0, 0.5, 0.2).priority)

        assertEquals(BD + 300, CheBangyeSido(u, 0, 0.4).priority)
        assertEquals(BD + 400, CheGyeonoSido(u).priority)

        assertEquals(PO + 0, CheJeonjuBaldong(u).priority)
        assertEquals(PO + 100, CheJeogyeokBaldong(u, 0).priority)
        assertEquals(PO + 250, CheBangyeBaldong(u).priority)
        assertEquals(PO + 350, CheYaktalBaldong(u).priority)
        assertEquals(PO + 550, CheJeontuChiryoBaldong(u).priority)
        assertEquals(PO + 600, CheGyeonoBaldong(u).priority)
        assertEquals(PO + 700, CheWiapBaldong(u).priority)
        assertEquals(PO + 800, CheJeonmyeolsiPhaseJeunga(u).priority)
        assertEquals(PO + 900, CheDolgyeokJisok(u).priority)

        assertEquals(F + 100, CheGabyeongByeongjongJeontu(u).priority)
        assertEquals(F + 200, CheBangeoryeokJeunga5p(u).priority)
    }

    private fun env() = TriggerEnv()
    private fun fire(t: BaseWarUnitTrigger, att: WarUnit, def: WarUnit, e: TriggerEnv = env()): TriggerEnv {
        t.action(rng(), e, listOf(att, def))
        return e
    }

    // ============================ DRAWING triggers ============================

    @Test
    fun `저지시도 draws nextBool of (atmos+train)over400 and activates 저지 on hit`() {
        val rec = mutableListOf<String>()
        // defender (저지시도 returns early for attacker)
        val def = newUnit("def", false, rec, atmos = 100.0, train = 100.0, boolScript = ArrayDeque(listOf(true)))
        val att = newUnit("att", true, rec)
        CheJeonjuSido(def).action(rng(), env(), listOf(att, def))
        assertEquals(listOf("nextBool(${200.0 / 400})"), rec)
        assertTrue(def.hasActivatedSkill("저지"))
        assertTrue(def.hasActivatedSkill("특수"))
    }

    @Test
    fun `저지시도 attacker draws nothing`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec, atmos = 100.0, train = 100.0)
        CheJeonjuSido(att).action(rng(), env(), listOf(att, newUnit("def", false, rec)))
        assertTrue(rec.isEmpty())
    }

    @Test
    fun `저격시도 draws nextBool(ratio) and stores wound env on hit`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec, boolScript = ArrayDeque(listOf(true)))
        val def = newUnit("def", false, rec)
        val e = fire(CheJeogyeokSido(att, 0, 0.5, 20.0, 40.0), att, def)
        assertEquals(listOf("nextBool(0.5)"), rec)
        assertTrue(att.hasActivatedSkill("저격"))
        @Suppress("UNCHECKED_CAST")
        val eAtt = e["e_attacker"] as TriggerEnv
        assertEquals(0, eAtt["저격발동자"])
        assertEquals(20.0, eAtt["woundMin"])
        assertEquals(40.0, eAtt["woundMax"])
    }

    @Test
    fun `저격시도 phase nonzero both draws nothing`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec, phase = 1)
        val def = newUnit("def", false, rec, phase = 1)
        fire(CheJeogyeokSido(att, 0, 0.5, 20.0, 40.0), att, def)
        assertTrue(rec.isEmpty())
    }

    @Test
    fun `전투치료시도 draws nextBool(0_4) and activates 치료 on hit`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec, boolScript = ArrayDeque(listOf(true)))
        fire(CheJeontuChiryoSido(att), att, newUnit("def", false, rec))
        assertEquals(listOf("nextBool(0.4)"), rec)
        assertTrue(att.hasActivatedSkill("치료"))
    }

    @Test
    fun `약탈시도 draws nextBool(ratio) and stores theftRatio on hit`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec, boolScript = ArrayDeque(listOf(true)))
        val def = newUnit("def", false, rec)
        val e = fire(CheYaktalSido(att, 0, 0.5, 0.2), att, def)
        assertEquals(listOf("nextBool(0.5)"), rec)
        assertTrue(att.hasActivatedSkill("약탈"))
        @Suppress("UNCHECKED_CAST")
        val eAtt = e["e_attacker"] as TriggerEnv
        assertEquals(0.2, eAtt["theftRatio"])
    }

    @Test
    fun `약탈시도 oppose not general draws nothing`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        val city = newUnit("city", false, rec, isGeneralUnit = false, isCityUnit = true, crew = castle)
        fire(CheYaktalSido(att, 0, 0.5, 0.2), att, city)
        assertTrue(rec.isEmpty())
    }

    @Test
    fun `반계시도 draws nextBool(prob) only when oppose has 계략`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec, boolScript = ArrayDeque(listOf(true)))
        val def = newUnit("def", false, rec)
        def.activateSkill("계략")
        // oppose's magic env must exist (assert key 'magic')
        val e = env()
        val eDef = TriggerEnv(); eDef["magic"] = listOf("위보", 1.2); e["e_defender"] = eDef
        CheBangyeSido(att, 0, 0.4).action(rng(), e, listOf(att, def))
        assertEquals(listOf("nextBool(0.4)"), rec)
        assertTrue(att.hasActivatedSkill("반계"))
        assertFalse(def.hasActivatedSkill("계략"))
    }

    @Test
    fun `반계시도 oppose lacks 계략 draws nothing`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        fire(CheBangyeSido(att, 0, 0.4), att, newUnit("def", false, rec))
        assertTrue(rec.isEmpty())
    }

    @Test
    fun `격노시도 against 필살 attacker draws one nextBool(half) for 진노`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec, boolScript = ArrayDeque(listOf(true)))
        val def = newUnit("def", false, rec)
        def.activateSkill("필살")
        fire(CheGyeonoSido(att), att, def)
        // 필살 branch: NO trial nextBool, then attacker-only 진노 nextBool(1/2)
        assertEquals(listOf("nextBool(0.5)"), rec)
        assertTrue(att.hasActivatedSkill("격노"))
        assertTrue(att.hasActivatedSkill("진노"))
    }

    @Test
    fun `격노시도 against 회피 draws nextBool(quarter) then nextBool(half) on attacker`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec, boolScript = ArrayDeque(listOf(true, true)))
        val def = newUnit("def", false, rec)
        def.activateSkill("회피")
        fire(CheGyeonoSido(att), att, def)
        assertEquals(listOf("nextBool(0.25)", "nextBool(0.5)"), rec)
        assertTrue(att.hasActivatedSkill("격노"))
        assertTrue(att.hasActivatedSkill("진노"))
    }

    @Test
    fun `격노시도 against 회피 defender draws only nextBool(quarter) - no 진노 roll`() {
        val rec = mutableListOf<String>()
        val def = newUnit("def", false, rec, boolScript = ArrayDeque(listOf(true)))
        val att = newUnit("att", true, rec)
        att.activateSkill("회피")
        // self = defender (격노 trigger on def), oppose = att who has 회피
        fire(CheGyeonoSido(def), att, def)
        assertEquals(listOf("nextBool(0.25)"), rec)  // defender: no isAttacker 진노 roll
        assertTrue(def.hasActivatedSkill("격노"))
        assertFalse(def.hasActivatedSkill("진노"))
    }

    @Test
    fun `격노시도 oppose has neither 필살 nor 회피 draws nothing`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        fire(CheGyeonoSido(att), att, newUnit("def", false, rec))
        assertTrue(rec.isEmpty())
    }

    @Test
    fun `저격발동 draws nextRangeInt(woundMin,woundMax) when matched and oppose is general lacking 부상무효`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec, intScript = ArrayDeque(listOf(30)))
        val def = newUnit("def", false, rec)
        att.activateSkill("저격")
        val e = env()
        val eAtt = TriggerEnv()
        eAtt["저격발동자"] = 0; eAtt["woundMin"] = 20.0; eAtt["woundMax"] = 40.0; eAtt["addAtmos"] = 20
        e["e_attacker"] = eAtt
        CheJeogyeokBaldong(att, 0).action(rng(), e, listOf(att, def))
        assertEquals(listOf("nextRangeInt(20,40)"), rec)
        assertEquals(30, def.injuryDelta)
        assertEquals(20, att.atmosDelta)
    }

    @Test
    fun `저격발동 skips injury draw when oppose has 부상무효`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        val def = newUnit("def", false, rec)
        att.activateSkill("저격")
        def.activateSkill("부상무효")
        val e = env()
        val eAtt = TriggerEnv()
        eAtt["저격발동자"] = 0; eAtt["woundMin"] = 20.0; eAtt["woundMax"] = 40.0; eAtt["addAtmos"] = 20
        e["e_attacker"] = eAtt
        CheJeogyeokBaldong(att, 0).action(rng(), e, listOf(att, def))
        assertTrue(rec.isEmpty())  // atmos add is no-draw; injury draw skipped
        assertEquals(20, att.atmosDelta)
    }

    @Test
    fun `저격발동 raiseType mismatch draws nothing`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        att.activateSkill("저격")
        val e = env()
        val eAtt = TriggerEnv(); eAtt["저격발동자"] = 5; e["e_attacker"] = eAtt
        CheJeogyeokBaldong(att, 0).action(rng(), e, listOf(att, newUnit("def", false, rec)))
        assertTrue(rec.isEmpty())
    }

    @Test
    fun `격노발동 draws criticalDamage and applies multiply`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        val def = newUnit("def", false, rec)
        att.activateSkill("격노")
        fire(CheGyeonoBaldong(att), att, def)
        assertEquals(listOf("criticalDamage"), rec)
        assertTrue(att.multiplies.contains(1.7))
    }

    @Test
    fun `격노발동 진노 also addBonusPhase`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        att.activateSkill("격노", "진노")
        fire(CheGyeonoBaldong(att), att, newUnit("def", false, rec))
        assertEquals(1, att.bonusPhaseDelta)
    }

    // ============================ no-draw 발동 / pure triggers ============================

    @Test
    fun `위압발동 sets oppose warPowerMultiply to 0 (no draw)`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        val def = newUnit("def", false, rec)
        att.activateSkill("위압")
        fire(CheWiapBaldong(att), att, def)
        assertTrue(rec.isEmpty())
        assertEquals(0.0, def.warPowerMul)
        assertEquals(-5, def.atmosDelta)  // general oppose −5
    }

    @Test
    fun `위압발동 oppose is city - no atmos decrement`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        val city = newUnit("city", false, rec, isGeneralUnit = false, isCityUnit = true, crew = castle)
        att.activateSkill("위압")
        fire(CheWiapBaldong(att), att, city)
        assertEquals(0.0, city.warPowerMul)
        assertEquals(0, city.atmosDelta)
    }

    @Test
    fun `저지발동 returns false to halt the phase (stopNextAction) and zeroes both warpower`() {
        val rec = mutableListOf<String>()
        val def = newUnit("def", false, rec, maxPhase = 5)
        val att = newUnit("att", true, rec)
        def.activateSkill("저지")
        val e = fire(CheJeonjuBaldong(def), att, def)
        assertTrue(rec.isEmpty())
        assertEquals(true, e["stopNextAction"])
        assertEquals(0.0, def.warPowerMul)
        assertEquals(0.0, att.warPowerMul)
        assertTrue(def.blockRewardApplied)
        assertEquals(-1, def.phaseDelta)
        assertEquals(-1, att.phaseDelta)
    }

    @Test
    fun `저지발동 not 저지 draws nothing and does not stop`() {
        val rec = mutableListOf<String>()
        val def = newUnit("def", false, rec)
        val e = fire(CheJeonjuBaldong(def), newUnit("att", true, rec), def)
        assertTrue(rec.isEmpty())
        assertFalse(e["stopNextAction"] == true)
    }

    @Test
    fun `반계발동 applies oppose magic damage multiply (no draw)`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        val def = newUnit("def", false, rec)
        att.activateSkill("반계")
        val e = env()
        val eDef = TriggerEnv(); eDef["magic"] = listOf("위보", 1.3); e["e_defender"] = eDef
        CheBangyeBaldong(att).action(rng(), e, listOf(att, def))
        assertTrue(rec.isEmpty())
        assertTrue(att.multiplies.contains(1.3))
    }

    @Test
    fun `약탈발동 transfers via stealFrom (no draw) and processConsumableItem on item raiseType`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        val def = newUnit("def", false, rec)
        att.activateSkill("약탈")
        val e = env()
        val eAtt = TriggerEnv(); eAtt["theftRatio"] = 0.2; e["e_attacker"] = eAtt
        CheYaktalBaldong(att).action(rng(), e, listOf(att, def))
        assertTrue(rec.isEmpty())
        assertEquals(0.2, att.theftRatioApplied)
    }

    @Test
    fun `전투치료발동 heals - oppose mul 0_7, self injury cleared, no draw`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        val def = newUnit("def", false, rec)
        att.activateSkill("치료")
        fire(CheJeontuChiryoBaldong(att), att, def)
        assertTrue(rec.isEmpty())
        assertTrue(def.multiplies.contains(0.7))
        assertEquals(0, att.injurySet)
    }

    @Test
    fun `전멸시페이즈증가 adds bonus phase when self phase nonzero and oppose phase zero`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec, phase = 2)
        val def = newUnit("def", false, rec, phase = 0)
        fire(CheJeonmyeolsiPhaseJeunga(att), att, def)
        assertTrue(rec.isEmpty())
        assertEquals(1, att.bonusPhaseDelta)
    }

    @Test
    fun `전멸시페이즈증가 no bonus when oppose phase nonzero`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec, phase = 2)
        val def = newUnit("def", false, rec, phase = 1)
        fire(CheJeonmyeolsiPhaseJeunga(att), att, def)
        assertEquals(0, att.bonusPhaseDelta)
    }

    @Test
    fun `돌격지속 adds bonus phase at last phase with attackCoef ge 1`() {
        val rec = mutableListOf<String>()
        // cavalry attacker vs footman: attackCoef >= 1 (cavalry beats footman)
        val cav = GameUnitConst.byId(1300) ?: footman
        val att = newUnit("att", true, rec, crew = cav, phase = 4, maxPhase = 5)
        val def = newUnit("def", false, rec, crew = footman)
        fire(CheDolgyeokJisok(att), att, def)
        assertTrue(rec.isEmpty())
        // attackCoef(cav vs footman) — pin only that no draw happens; bonus depends on coef table
    }

    @Test
    fun `돌격지속 oppose is city returns early`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec, phase = 4, maxPhase = 5)
        val city = newUnit("city", false, rec, isGeneralUnit = false, isCityUnit = true, crew = castle)
        fire(CheDolgyeokJisok(att), att, city)
        assertEquals(0, att.bonusPhaseDelta)
    }

    @Test
    fun `기병병종전투 applies muls by side - no draw`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        val def = newUnit("def", false, rec)
        fire(CheGabyeongByeongjongJeontu(att), att, def)
        assertTrue(rec.isEmpty())
        // attacker vs general → oppose *0.97, self *1.02
        assertTrue(att.multiplies.contains(1.02))
        assertTrue(def.multiplies.contains(0.97))
    }

    @Test
    fun `방어력증가5p defender multiplies oppose by 1over1_05`() {
        val rec = mutableListOf<String>()
        val def = newUnit("def", false, rec)
        val att = newUnit("att", true, rec)
        fire(CheBangeoryeokJeunga5p(def), att, def)
        assertTrue(rec.isEmpty())
        assertTrue(att.multiplies.contains(1.0 / 1.05))
    }

    @Test
    fun `방어력증가5p attacker is no-op`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        val def = newUnit("def", false, rec)
        fire(CheBangeoryeokJeunga5p(att), att, def)
        assertTrue(att.multiplies.isEmpty() && def.multiplies.isEmpty())
    }

    @Test
    fun `위압시도 phase0 activates 위압 and oppose forced bulgas - no draw`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec, phase = 0)
        val def = newUnit("def", false, rec, phase = 0)
        fire(CheWiapSido(att), att, def)
        assertTrue(rec.isEmpty())
        assertTrue(att.hasActivatedSkill("위압"))
        assertTrue(def.hasActivatedSkill("회피불가"))
        assertTrue(def.hasActivatedSkill("필살불가"))
        assertTrue(def.hasActivatedSkill("계략불가"))
    }

    @Test
    fun `필살강화_회피불가 forces oppose 회피불가 only when self has 필살`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        val def = newUnit("def", false, rec)
        att.activateSkill("필살")
        fire(CheFilsalGanghwaHoipiBulga(att), att, def)
        assertTrue(def.hasActivatedSkill("회피불가"))
    }

    @Test
    fun `부상무효 activates 부상무효 (no draw)`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        fire(CheBusangMuhyo(att, 0), att, newUnit("def", false, rec))
        assertTrue(att.hasActivatedSkill("부상무효"))
    }

    @Test
    fun `성벽부상무효 activates only when oppose is city`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        val city = newUnit("city", false, rec, isGeneralUnit = false, isCityUnit = true, crew = castle)
        fire(CheSeongbyeokBusangMuhyo(att), att, city)
        assertTrue(att.hasActivatedSkill("부상무효"))

        val att2 = newUnit("att2", true, rec)
        fire(CheSeongbyeokBusangMuhyo(att2), att2, newUnit("g", false, rec))
        assertFalse(att2.hasActivatedSkill("부상무효"))
    }

    @Test
    fun `퇴각부상무효 activates 퇴각부상무효`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        fire(CheToegakBusangMuhyo(att), att, newUnit("def", false, rec))
        assertTrue(att.hasActivatedSkill("퇴각부상무효"))
    }

    @Test
    fun `선제사격시도 activates 특수 선제 at phase0 (no draw)`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec, phase = 0)
        val def = newUnit("def", false, rec, phase = 0)
        fire(CheSeonjeSagyeokSido(att), att, def)
        assertTrue(rec.isEmpty())
        assertTrue(att.hasActivatedSkill("선제"))
        assertTrue(att.hasActivatedSkill("특수"))
    }

    @Test
    fun `선제사격발동 not 선제 returns early`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        fire(CheSeonjeSagyeokBaldong(att), att, newUnit("def", false, rec))
        assertEquals(0, att.phaseDelta)
    }

    @Test
    fun `WarActivateSkills self activates given skills (no draw)`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        fire(WarActivateSkills(att, 0, true, "특수", "선제"), att, newUnit("def", false, rec))
        assertTrue(att.hasActivatedSkill("특수"))
        assertTrue(att.hasActivatedSkill("선제"))
    }

    @Test
    fun `능력치변경 and 전투력보정 are no-draw and call processConsumableItem`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        val def = newUnit("def", false, rec)
        fire(NeungryeokchiByeongyeong(att, 0, "atmos", "+", 5.0), att, def)
        fire(JeontuRyeokBojeong(att, 1.1, 0.9), att, def)
        assertTrue(rec.isEmpty())
        // 전투력보정 muls
        assertTrue(att.multiplies.contains(1.1))
        assertTrue(def.multiplies.contains(0.9))
    }

    @Test
    fun `충차아이템소모 is no-draw`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        val city = newUnit("city", false, rec, isGeneralUnit = false, isCityUnit = true, crew = castle)
        fire(EventChungchaItemConsume(att), att, city)
        assertTrue(rec.isEmpty())
        assertTrue(att.hasActivatedSkill("충차공격"))
    }
}

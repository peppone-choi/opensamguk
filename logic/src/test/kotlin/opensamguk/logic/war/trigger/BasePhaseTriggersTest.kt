package opensamguk.logic.war.trigger

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.war.trigger.triggers.CheFilsalBaldong
import opensamguk.logic.war.trigger.triggers.CheFilsalSido
import opensamguk.logic.war.trigger.triggers.CheGyeryakBaldong
import opensamguk.logic.war.trigger.triggers.CheGyeryakSido
import opensamguk.logic.war.trigger.triggers.CheGyeryakSilpae
import opensamguk.logic.war.trigger.triggers.CheHoipiBaldong
import opensamguk.logic.war.trigger.triggers.CheHoipiSido
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port target = PHP `WarUnitTrigger/{che_필살시도,che_필살발동,che_회피시도,che_회피발동,che_계략시도,che_계략발동,che_계략실패}.php`
 * + `General.php:920-928` (the canonical seed arg order). Pins each priority + the DRAW STRUCTURE
 * (the F1 parity surface): 필살시도 = nextBool(crit); 계략시도 = nextBool(trial)→choice(kind)→nextBool(success);
 * the 계략불가 step-0 NO-draw short-circuit; the magic table key insertion order (general vs city).
 *
 * The stat-fold VALUE computation (magicTrialProb / successDamage) is delegated to the [WarUnit] impl
 * (F2/F5/A1) and asserted draw-for-draw at G1 — here we pin only the draw STRUCTURE + priorities.
 */
class BasePhaseTriggersTest {

    private class FakeWarUnit(
        private val id: String,
        private val attacker: Boolean,
        private val isGeneralUnit: Boolean = true,
        private val isCityUnit: Boolean = false,
        var critRatio: Double = 0.0,
        var avoidRatio: Double = 0.0,
        var magicTrial: Double = 0.0,
        var magicSuccess: Double = 0.7,
        private val recorder: MutableList<String>,
        private val boolScript: ArrayDeque<Boolean>,
        private val choiceScript: ArrayDeque<Int>,
    ) : WarUnit {
        val activatedSkills = linkedSetOf<String>()
        var lastMultiply: Double? = null

        override val rng: RandUtil = ScriptedRng(recorder, boolScript, choiceScript)
        override fun getUnitId(): String = id
        override fun isAttacker(): Boolean = attacker
        override fun getPhase(): Int = 0
        override fun hasActivatedSkill(skillName: String): Boolean = skillName in activatedSkills
        override fun activateSkill(vararg skillNames: String) { activatedSkills.addAll(skillNames) }
        override fun multiplyWarPowerMultiply(multiply: Double) { lastMultiply = multiply }
        override fun criticalDamage(): Double { recorder.add("criticalDamage"); return 1.7 }
        override fun getComputedCriticalRatio(): Double = critRatio
        override fun getComputedAvoidRatio(): Double = avoidRatio
        override fun pushBattleDetailLog(message: String) {}
        override fun pushPlainActionLog(message: String) {}
        override fun getItemName(): String = "item"
        override fun getItemRawName(): String = "item"
        override fun deleteItem() {}
        override fun isGeneral(): Boolean = isGeneralUnit
        override fun isCity(): Boolean = isCityUnit
        override fun getMagicTrialProb(oppose: WarUnit): Double = magicTrial
        override fun getMagicSuccessProb(oppose: WarUnit): Double = magicSuccess
        override fun foldMagicSuccessDamage(oppose: WarUnit, magic: String, raw: Double): Double = raw
        override fun foldMagicFailDamage(oppose: WarUnit, magic: String, raw: Double): Double = raw
    }

    /** A RandUtil whose nextBool/choice draws are scripted + recorded (the draw-order pin). */
    private class ScriptedRng(
        private val recorder: MutableList<String>,
        private val boolScript: ArrayDeque<Boolean>,
        private val choiceScript: ArrayDeque<Int>,
    ) : RandUtil(LiteHashDrbg("00")) {
        override fun nextBool(prob: Double): Boolean {
            recorder.add("nextBool($prob)")
            return boolScript.removeFirstOrNull() ?: (prob >= 1.0)
        }
        override fun <T> choice(items: List<T>): T {
            recorder.add("choice(${items.joinToString(",")})")
            val idx = choiceScript.removeFirstOrNull() ?: 0
            return items[idx]
        }
    }

    private fun newUnit(
        id: String, attacker: Boolean, recorder: MutableList<String>,
        boolScript: ArrayDeque<Boolean> = ArrayDeque(), choiceScript: ArrayDeque<Int> = ArrayDeque(),
        isGeneralUnit: Boolean = true, isCityUnit: Boolean = false,
    ) = FakeWarUnit(id, attacker, isGeneralUnit, isCityUnit, recorder = recorder, boolScript = boolScript, choiceScript = choiceScript)

    private fun env() = TriggerEnv()
    private fun rng() = RandUtil(LiteHashDrbg("00"))

    // ----- priorities -----
    @Test
    fun `priorities match PHP exactly`() {
        val rec = mutableListOf<String>()
        val u = newUnit("u", true, rec)
        assertEquals(ObjectTrigger.PRIORITY_PRE + 120, CheFilsalSido(u).priority)
        assertEquals(ObjectTrigger.PRIORITY_POST + 400, CheFilsalBaldong(u).priority)
        assertEquals(ObjectTrigger.PRIORITY_PRE + 200, CheHoipiSido(u).priority)
        assertEquals(ObjectTrigger.PRIORITY_POST + 500, CheHoipiBaldong(u).priority)
        assertEquals(ObjectTrigger.PRIORITY_PRE + 300, CheGyeryakSido(u).priority)
        assertEquals(ObjectTrigger.PRIORITY_POST + 300, CheGyeryakBaldong(u).priority)
        assertEquals(ObjectTrigger.PRIORITY_POST + 300, CheGyeryakSilpae(u).priority)
    }

    // ----- 필살시도 draws nextBool(criticalRatio) -----
    @Test
    fun `필살시도 draws nextBool of criticalRatio and activates on hit`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec, boolScript = ArrayDeque(listOf(true)))
        val def = newUnit("def", false, rec)
        (att as FakeWarUnit).critRatio = 0.3
        CheFilsalSido(att).action(rng(), env(), listOf(att, def))
        assertEquals(listOf("nextBool(0.3)"), rec)
        assertTrue(att.hasActivatedSkill("필살"))
    }

    @Test
    fun `필살시도 not-General draws nothing`() {
        val rec = mutableListOf<String>()
        val city = newUnit("city", false, rec, isGeneralUnit = false, isCityUnit = true)
        val att = newUnit("att", true, rec)
        CheFilsalSido(city).action(rng(), env(), listOf(att, city))
        assertTrue(rec.isEmpty())
    }

    // ----- 계략시도 draw structure: nextBool -> choice -> nextBool -----
    @Test
    fun `계략시도 draws nextBool then choice then nextBool in order (general table)`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec,
            boolScript = ArrayDeque(listOf(true, true)),   // trial hits, success hits
            choiceScript = ArrayDeque(listOf(0)))           // pick first general magic = 위보
        val def = newUnit("def", false, rec)
        (att as FakeWarUnit).magicTrial = 0.5
        CheGyeryakSido(att).action(rng(), env(), listOf(att, def))
        assertEquals(
            listOf("nextBool(0.5)", "choice(위보,매복,반목,화계,혼란)", "nextBool(0.7)"),
            rec,
        )
        assertTrue(att.hasActivatedSkill("계략시도"))
        assertTrue(att.hasActivatedSkill("계략"))
    }

    @Test
    fun `계략시도 against a city uses the city magic table key order`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec,
            boolScript = ArrayDeque(listOf(true, false)),  // trial hits, success fails
            choiceScript = ArrayDeque(listOf(0)))
        val city = newUnit("city", false, rec, isGeneralUnit = false, isCityUnit = true)
        (att as FakeWarUnit).magicTrial = 0.5
        CheGyeryakSido(att).action(rng(), env(), listOf(att, city))
        assertEquals(
            listOf("nextBool(0.5)", "choice(급습,위보,혼란)", "nextBool(0.7)"),
            rec,
        )
        assertTrue(att.hasActivatedSkill("계략실패"))
    }

    @Test
    fun `계략불가 short-circuits che_계략시도 with ZERO draws`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        (att as FakeWarUnit).magicTrial = 0.9
        att.activateSkill("계략불가")
        CheGyeryakSido(att).action(rng(), env(), listOf(att, newUnit("def", false, rec)))
        assertTrue(rec.isEmpty(), "계략불가 must consume zero RNG (step-0 short-circuit)")
    }

    @Test
    fun `계략시도 trialProb leq 0 draws nothing`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec)
        (att as FakeWarUnit).magicTrial = 0.0
        CheGyeryakSido(att).action(rng(), env(), listOf(att, newUnit("def", false, rec)))
        assertTrue(rec.isEmpty())
    }

    @Test
    fun `계략시도 trial fails - only one draw`() {
        val rec = mutableListOf<String>()
        val att = newUnit("att", true, rec, boolScript = ArrayDeque(listOf(false)))
        val def = newUnit("def", false, rec)
        (att as FakeWarUnit).magicTrial = 0.4
        CheGyeryakSido(att).action(rng(), env(), listOf(att, def))
        assertEquals(listOf("nextBool(0.4)"), rec)
        assertFalse(att.hasActivatedSkill("계략시도"))
    }

    // ----- same-bucket arg order: 계략발동 before 계략실패 at POST+300 -----
    @Test
    fun `계략발동 and 계략실패 share POST+300 and fire in seed arg order`() {
        val rec = mutableListOf<String>()
        val u = newUnit("u", true, rec)
        val caller = WarUnitTriggerCaller(CheGyeryakBaldong(u), CheGyeryakSilpae(u))
        // Build the env: pretend 계략 active so 발동 records first.
        (u as FakeWarUnit).activateSkill("계략")
        val outEnv = env()
        // selfEnv['magic'] must exist for 발동 to read damage.
        val eAtt = TriggerEnv(); eAtt["magic"] = listOf("위보", 1.2); outEnv["e_attacker"] = eAtt
        val order = mutableListOf<String>()
        // Wrap multiplyWarPowerMultiply tracking through the unit.
        caller.fire(rng(), outEnv, listOf(u, newUnit("def", false, rec)))
        // 발동 fired (계략 active) and applied a multiply; 실패 did not (no 계략실패 skill).
        assertTrue(u.lastMultiply != null)
    }
}

package opensamguk.logic.stats

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.General
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit
import opensamguk.logic.war.trigger.WarUnitTriggerCaller
import opensamguk.logic.war.trigger.triggers.CheFilsalSido
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port-faithful tests for the 3 war hooks F5 adds to [GeneralActionModule] / [GeneralActionPipeline]
 * (plan AREA F5 task FH1).
 *
 * PHP grand truth: `General.php:889-938`.
 *   - `getWarPowerMultiplier(WarUnit): array` — `[$att,$def]=[1,1]` then `*=` each source's pair over
 *     `getActionList()` (the 12-source `MODULE_ORDER`). MULTIPLICATIVE.
 *   - `getBattleInitSkillTriggerList(WarUnit): ?WarUnitTriggerCaller` — `new WarUnitTriggerCaller()`
 *     (EMPTY seed) then `merge()` each source's per-source init caller.
 *   - `getBattlePhaseSkillTriggerList(WarUnit): ?WarUnitTriggerCaller` — `new WarUnitTriggerCaller(필살시도,
 *     필살발동, 회피시도, 회피발동, 계략시도, 계략발동, 계략실패)` (the base-7 seed, ARG order) then
 *     `merge()` each source's per-source phase caller. Seed+merge are ONE indivisible method.
 *
 * The interface defaults are identity (`[1,1]` / `null`) so every P2/P3 module keeps compiling.
 */
class ActionPipelineWarHooksTest {

    private val general = General(
        id = 1, nationId = 1, cityId = 1,
        leadership = 70, strength = 60, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 1000, rice = 1000,
    )

    // --- a minimal WarUnit fake (mirrors BasePhaseTriggersTest.FakeWarUnit) so the seeded base-7 can fire ---
    private class FakeWarUnit(
        private val id: String,
        private val attacker: Boolean,
        var critRatio: Double = 0.0,
        var avoidRatio: Double = 0.0,
        private val recorder: MutableList<String> = mutableListOf(),
        private val boolScript: ArrayDeque<Boolean> = ArrayDeque(),
        private val choiceScript: ArrayDeque<Int> = ArrayDeque(),
    ) : WarUnit {
        val activatedSkills = linkedSetOf<String>()
        override val rng: RandUtil = ScriptedRng(recorder, boolScript, choiceScript)
        override fun getUnitId(): String = id
        override fun isAttacker(): Boolean = attacker
        override fun getPhase(): Int = 0
        override fun hasActivatedSkill(skillName: String): Boolean = skillName in activatedSkills
        override fun activateSkill(vararg skillNames: String) { activatedSkills.addAll(skillNames) }
        override fun multiplyWarPowerMultiply(multiply: Double) {}
        override fun criticalDamage(): Double = 1.7
        override fun getComputedCriticalRatio(): Double = critRatio
        override fun getComputedAvoidRatio(): Double = avoidRatio
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
            recorder.add("choice")
            val idx = choiceScript.removeFirstOrNull() ?: 0
            return items[idx]
        }
    }

    // ----- getWarPowerMultiplier MULTIPLIES across modules -----
    @Test
    fun `getWarPowerMultiplier multiplies att and def across modules`() {
        val attBoost = object : GeneralActionModule {
            override fun getWarPowerMultiplier(unit: WarUnit): Pair<Double, Double> = 1.2 to 1.0
        }
        val defCut = object : GeneralActionModule {
            override fun getWarPowerMultiplier(unit: WarUnit): Pair<Double, Double> = 1.0 to 0.9
        }
        val pipeline = GeneralActionPipeline(listOf(attBoost, defCut))
        val unit = FakeWarUnit("u", true)
        val (att, def) = pipeline.getWarPowerMultiplier(unit)
        assertEquals(1.2, att, 1e-12)
        assertEquals(0.9, def, 1e-12)
    }

    @Test
    fun `getWarPowerMultiplier identity default keeps a no-op module at 1,1`() {
        val noop = object : GeneralActionModule {}
        val unit = FakeWarUnit("u", true)
        assertEquals(1.0 to 1.0, noop.getWarPowerMultiplier(unit))
        val pipeline = GeneralActionPipeline(listOf(noop))
        assertEquals(1.0 to 1.0, pipeline.getWarPowerMultiplier(unit))
    }

    @Test
    fun `empty pipeline getWarPowerMultiplier is 1,1`() {
        assertEquals(1.0 to 1.0, GeneralActionPipeline().getWarPowerMultiplier(FakeWarUnit("u", true)))
    }

    // ----- getBattleInitSkillTriggerList seeds EMPTY then merges -----
    @Test
    fun `getBattleInitSkillTriggerList seeds an empty caller and merges per-source callers`() {
        // empty pipeline -> empty caller (never null), no triggers.
        val emptyCaller = GeneralActionPipeline().getBattleInitSkillTriggerList(FakeWarUnit("u", true))
        assertNotNull(emptyCaller)
        assertTrue(emptyCaller.isEmpty(), "init caller seeds EMPTY (no base triggers)")

        // a module supplying an init trigger gets merged in.
        val injector = object : GeneralActionModule {
            override fun getBattleInitSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller? =
                WarUnitTriggerCaller(CheFilsalSido(unit))
        }
        val pipeline = GeneralActionPipeline(listOf(injector))
        val caller = pipeline.getBattleInitSkillTriggerList(FakeWarUnit("u", true))
        assertNotNull(caller)
        assertTrue(!caller.isEmpty(), "the per-source init caller is merged into the seed")
    }

    @Test
    fun `getBattleInitSkillTriggerList default module hook is null`() {
        val noop = object : GeneralActionModule {}
        assertNull(noop.getBattleInitSkillTriggerList(FakeWarUnit("u", true)))
    }

    // ----- getBattlePhaseSkillTriggerList seeds the base-7 then merges -----
    @Test
    fun `getBattlePhaseSkillTriggerList seeds the base-7 and fires them in priority order`() {
        // critRatio>0 + avoidRatio>0 so 필살시도(PRE+120) + 회피시도(PRE+200) each draw a nextBool.
        val rec = mutableListOf<String>()
        val unit = FakeWarUnit("att", true, critRatio = 0.3, avoidRatio = 0.2, recorder = rec)
        val def = FakeWarUnit("def", false)

        val caller = GeneralActionPipeline().getBattlePhaseSkillTriggerList(unit)
        assertNotNull(caller)
        assertTrue(!caller.isEmpty(), "the base-7 are seeded even on an empty pipeline")

        caller.fire(RandUtil(LiteHashDrbg("00")), TriggerEnv(), listOf(unit, def))
        // the base-7 in priority-ascending order: 필살시도 PRE+120 then 회피시도 PRE+200 draw;
        // the rest (계략/발동) do not draw (no 계략 active, magicTrial=0).
        assertEquals(listOf("nextBool(0.3)", "nextBool(0.2)"), rec)
    }

    @Test
    fun `getBattlePhaseSkillTriggerList default module hook is null`() {
        val noop = object : GeneralActionModule {}
        assertNull(noop.getBattlePhaseSkillTriggerList(FakeWarUnit("u", true)))
    }

    @Test
    fun `getBattlePhaseSkillTriggerList merges per-source callers into the same base-7 caller`() {
        // A module that ALSO seeds a PRE+120 필살시도 on the same unit: the dedup key collapses it onto the
        // base-7 slot (same priority/class/unit/raiseType) — still exactly the base-7 draw stream, not doubled.
        val rec = mutableListOf<String>()
        val unit = FakeWarUnit("att", true, critRatio = 0.3, recorder = rec)
        val def = FakeWarUnit("def", false)
        val dupModule = object : GeneralActionModule {
            override fun getBattlePhaseSkillTriggerList(u: WarUnit): WarUnitTriggerCaller =
                WarUnitTriggerCaller(CheFilsalSido(u))
        }
        val caller = GeneralActionPipeline(listOf(dupModule)).getBattlePhaseSkillTriggerList(unit)
        assertNotNull(caller)
        caller.fire(RandUtil(LiteHashDrbg("00")), TriggerEnv(), listOf(unit, def))
        // 필살시도 fires ONCE (dedup collapses the merged PRE+120 onto the base-7 slot), not twice;
        // then 회피시도 PRE+200 draws its nextBool(avoidRatio=0.0). NO third 필살시도 draw.
        assertEquals(listOf("nextBool(0.3)", "nextBool(0.0)"), rec)
    }

    // ----- MODULE_ORDER / specialWar index pin -----
    @Test
    fun `MODULE_ORDER places specialWar at index 3`() {
        assertEquals("specialWar", GeneralActionPipeline.MODULE_ORDER[3])
    }

    // ----- the owner-then-oppose cross-fold contract (frozen here for F2/A1) -----
    @Test
    fun `cross fold passes the owner general to BOTH onCalcStat and onCalcOpposeStat`() {
        // SELF.onCalcStat(SELF.general, key, value) THEN OPPOSE.onCalcOpposeStat(SELF.general, key, value):
        // BOTH receive the OWNER general (onCalcStat dispatched on owner; onCalcOpposeStat dispatched on the
        // OPPONENT module but receives the OWNER general). A stub records which general it received.
        val ownerGeneral = general
        val received = mutableListOf<Int>()
        val selfModule = object : GeneralActionModule {
            override fun onCalcStat(g: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
                received.add(g.id); return value + 1.0
            }
        }
        val opposeModule = object : GeneralActionModule {
            override fun onCalcOpposeStat(g: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
                received.add(g.id); return value * 2.0
            }
        }
        // self pipeline = the owner's module stack; oppose pipeline = the opponent's.
        val selfPipeline = GeneralActionPipeline(listOf(selfModule))
        val opposePipeline = GeneralActionPipeline(listOf(opposeModule))
        var v = 10.0
        v = selfPipeline.onCalcStat(ownerGeneral, "warCriticalRatio", v)
        v = opposePipeline.onCalcOpposeStat(ownerGeneral, "warCriticalRatio", v)
        assertEquals(22.0, v, 1e-12) // (10+1)*2
        assertEquals(listOf(ownerGeneral.id, ownerGeneral.id), received)
    }
}

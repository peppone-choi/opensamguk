package opensamguk.logic.scenario

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.GameUnitDetail
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.war.trigger.WarUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScenarioEffectRegistryTest {
    private val general = General(
        id = 1,
        nationId = 1,
        cityId = 1,
        leadership = 70,
        strength = 60,
        intel = 80,
        injury = 0,
        experience = 0.0,
        dedication = 0.0,
        officerLevel = 1,
        gold = 1000,
        rice = 1000,
    )

    private class FakeWarUnit(
        private val attacker: Boolean,
    ) : WarUnit {
        override val rng: RandUtil = RandUtil(LiteHashDrbg("scenario-effect-test"))
        override fun getUnitId(): String = if (attacker) "att" else "def"
        override fun isAttacker(): Boolean = attacker
        override fun getPhase(): Int = 1
        override fun hasActivatedSkill(skillName: String): Boolean = false
        override fun activateSkill(vararg skillNames: String) {}
        override fun multiplyWarPowerMultiply(multiply: Double) {}
        override fun getComputedAtmos(): Double = 70.0
        override fun getComputedTrain(): Double = 70.0
        override fun hasActivatedSkillOnLog(skillName: String): Int = 0
        override fun deactivateSkill(vararg skillNames: String) {}
        override fun getMaxPhase(): Int = 5
        override fun addPhase(phase: Int) {}
        override fun addBonusPhase(cnt: Int) {}
        override fun setWarPowerMultiply(multiply: Double) {}
        override fun getWarPower(): Double = 100.0
        override fun getCrewType(): GameUnitDetail = GameUnitConst.byId(GameUnitConst.DEFAULT_CREWTYPE)!!
        override fun isGeneralUnit(): Boolean = true
        override fun applyVarChange(variable: String, operator: String, value: Double, limitMin: Double?, limitMax: Double?) {}
        override fun increaseAtmos(delta: Int) {}
        override fun increaseInjury(amount: Int) {}
        override fun decreaseAtmos(delta: Int, min: Int) {}
        override fun stealFrom(oppose: WarUnit, theftRatio: Double): Pair<Double, Double> = 0.0 to 0.0
        override fun clearInjury() {}
        override fun applyBlockReward(oppose: WarUnit) {}
        override fun getSiegeRamRemain(): Int = 0
        override fun setSiegeRamRemain(value: Int) {}
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
        override fun getMagicSuccessProb(oppose: WarUnit): Double = 0.0
        override fun foldMagicSuccessDamage(oppose: WarUnit, magic: String, raw: Double): Double = raw
        override fun foldMagicFailDamage(oppose: WarUnit, magic: String, raw: Double): Double = raw
    }

    @Test
    fun `registry resolves only the three PHP scenario effect modules`() {
        assertEquals(EventStrongAttacker, ScenarioEffectRegistry.resolve("event_StrongAttacker"))
        assertEquals(EventUnlimitedDefenceThresholdChange, ScenarioEffectRegistry.resolve("event_UnlimitedDefenceThresholdChange"))
        assertEquals(EventMoreEffect, ScenarioEffectRegistry.resolve("event_MoreEffect"))
        assertNull(ScenarioEffectRegistry.resolve(null))
        assertNull(ScenarioEffectRegistry.resolve("None"))
        assertNull(ScenarioEffectRegistry.resolve("unknown"))
    }

    @Test
    fun `strong attacker applies PHP war and defence-change hooks`() {
        val pipeline = GeneralActionPipeline(listOf(EventStrongAttacker))

        assertEquals(0.0, pipeline.onCalcDomestic(general, "changeDefenceTrain", "train999", 70.0))
        assertEquals(1.4 to 0.7143, pipeline.getWarPowerMultiplier(FakeWarUnit(attacker = true)))
        assertEquals(1.0 to 1.0, pipeline.getWarPowerMultiplier(FakeWarUnit(attacker = false)))
        assertNotNull(pipeline.getBattlePhaseSkillTriggerList(FakeWarUnit(attacker = true)))
    }

    @Test
    fun `unlimited defence threshold only clears defence train changes`() {
        val pipeline = GeneralActionPipeline(listOf(EventUnlimitedDefenceThresholdChange))

        assertEquals(0.0, pipeline.onCalcDomestic(general, "changeDefenceTrain", "atmos999", 70.0))
        assertEquals(70.0, pipeline.onCalcDomestic(general, "농업", "score", 70.0))
        assertEquals(1.0 to 1.0, pipeline.getWarPowerMultiplier(FakeWarUnit(attacker = true)))
    }

    @Test
    fun `more effect doubles listed domestic scores income and attacker war effect`() {
        val pipeline = GeneralActionPipeline(listOf(EventMoreEffect))

        assertEquals(200.0, pipeline.onCalcDomestic(general, "상업", "score", 100.0))
        assertEquals(100.0, pipeline.onCalcDomestic(general, "계략", "score", 100.0))
        assertEquals(0.0, pipeline.onCalcDomestic(general, "changeDefenceTrain", "train999", 70.0))
        assertEquals(200.0, EventMoreEffect.onCalcNationalIncome(general, "gold", 100.0))
        assertEquals(200.0, EventMoreEffect.onCalcNationalIncome(general, "rice", 100.0))
        assertEquals(200.0, EventMoreEffect.onCalcNationalIncome(general, "pop", 100.0))
        assertEquals(-100.0, EventMoreEffect.onCalcNationalIncome(general, "pop", -100.0))
        assertEquals(1.4 to 0.7143, pipeline.getWarPowerMultiplier(FakeWarUnit(attacker = true)))
    }
}

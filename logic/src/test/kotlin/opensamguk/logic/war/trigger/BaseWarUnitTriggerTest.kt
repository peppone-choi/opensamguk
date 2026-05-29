package opensamguk.logic.war.trigger

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port target = PHP `sammo/BaseWarUnitTrigger.php:7-104` + `sammo/WarUnitTriggerCaller.php:3-9`.
 * Pins:
 *   - TYPE consts (NONE0 / ITEM1 / CONSUMABLE3 / DEDUP_BASE1024);
 *   - getUniqueID OVERRIDE appends `_{raiseType}` (raiseType coexistence — decision #4);
 *   - action() splits env into e_attacker/e_defender by isAttacker then re-stitches;
 *   - stopNextAction short-circuit (no actionWar call);
 *   - processConsumableItem fires only when raiseType&TYPE_ITEM and records a delete-DELTA (not inline DB);
 *   - WarUnitTriggerCaller.checkValidTrigger rejects a non-WarUnit trigger.
 */
class BaseWarUnitTriggerTest {

    // A recording WarUnit fake: captures skill activations + item deletions (the flush-delta sink).
    private class FakeWarUnit(
        private val id: String,
        private val attacker: Boolean,
    ) : WarUnit {
        val activatedSkills = linkedSetOf<String>()
        val battleDetailLogs = mutableListOf<String>()
        val plainActionLogs = mutableListOf<String>()
        var itemDeleted = false
        var heldItem = "item"  // overwritten in test

        override val rng: RandUtil = RandUtil(LiteHashDrbg("00"))
        override fun getUnitId(): String = id
        override fun isAttacker(): Boolean = attacker
        override fun getPhase(): Int = 0
        override fun hasActivatedSkill(skillName: String): Boolean = skillName in activatedSkills
        override fun activateSkill(vararg skillNames: String) { activatedSkills.addAll(skillNames) }
        override fun multiplyWarPowerMultiply(multiply: Double) {}
        override fun criticalDamage(): Double = 1.5
        override fun getComputedCriticalRatio(): Double = 0.0
        override fun getComputedAvoidRatio(): Double = 0.0
        override fun pushBattleDetailLog(message: String) { battleDetailLogs.add(message) }
        override fun pushPlainActionLog(message: String) { plainActionLogs.add(message) }
        override fun getItemName(): String = heldItem
        override fun getItemRawName(): String = heldItem
        override fun deleteItem() { itemDeleted = true }
        override fun isGeneral(): Boolean = true
        override fun isCity(): Boolean = false
        override fun getMagicTrialProb(oppose: WarUnit): Double = 0.0
        override fun getMagicSuccessProb(oppose: WarUnit): Double = 0.7
        override fun foldMagicSuccessDamage(oppose: WarUnit, magic: String, raw: Double): Double = raw
        override fun foldMagicFailDamage(oppose: WarUnit, magic: String, raw: Double): Double = raw
    }

    // A concrete war trigger that records the (self, oppose, selfEnv) it saw.
    private class RecordingWarTrigger(
        unit: WarUnit,
        override val priority: Int = ObjectTrigger.PRIORITY_PRE + 100,
        raiseType: Int = TYPE_NONE,
        private val continueAfter: Boolean = true,
    ) : BaseWarUnitTrigger(unit, raiseType) {
        var sawSelfAttacker: Boolean? = null
        override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
            sawSelfAttacker = self.isAttacker()
            selfEnv["mark"] = self.getUnitId()
            return continueAfter
        }
    }

    private fun rng() = RandUtil(LiteHashDrbg("00"))

    @Test
    fun `TYPE constants match PHP`() {
        assertEquals(0, BaseWarUnitTrigger.TYPE_NONE)
        assertEquals(1, BaseWarUnitTrigger.TYPE_ITEM)
        assertEquals(3, BaseWarUnitTrigger.TYPE_CONSUMABLE_ITEM)   // 1 | 2
        assertEquals(1024, BaseWarUnitTrigger.TYPE_DEDUP_TYPE_BASE)
    }

    @Test
    fun `getUniqueID appends raiseType - item and specialty COEXIST`() {
        val unit = FakeWarUnit("u7", attacker = true)
        val specialty = RecordingWarTrigger(unit, priority = 20100, raiseType = BaseWarUnitTrigger.TYPE_NONE)
        val item = RecordingWarTrigger(unit, priority = 20100, raiseType = BaseWarUnitTrigger.TYPE_ITEM + 1024 * 305)
        // Same priority + class + unit, DIFFERENT raiseType ⇒ DIFFERENT uniqueID ⇒ both survive dedup.
        assertEquals("20100_${RecordingWarTrigger::class.qualifiedName}_u7_0", specialty.getUniqueID())
        assertTrue(item.getUniqueID().endsWith("_${BaseWarUnitTrigger.TYPE_ITEM + 1024 * 305}"))
        assertTrue(specialty.getUniqueID() != item.getUniqueID())
    }

    @Test
    fun `action splits env by isAttacker then re-stitches`() {
        val attUnit = FakeWarUnit("att", attacker = true)
        val trigger = RecordingWarTrigger(attUnit)
        val env = TriggerEnv()
        val arg = listOf(attUnit, FakeWarUnit("def", attacker = false))
        val out = trigger.action(rng(), env, arg)
        assertEquals(true, trigger.sawSelfAttacker)
        // The attacker's selfEnv mark landed in e_attacker.
        @Suppress("UNCHECKED_CAST")
        val eAtt = out["e_attacker"] as TriggerEnv
        assertEquals("att", eAtt["mark"])
        assertTrue(out.containsKey("e_defender"))
    }

    @Test
    fun `defender self routes its mark into e_defender`() {
        val attUnit = FakeWarUnit("att", attacker = true)
        val defUnit = FakeWarUnit("def", attacker = false)
        val trigger = RecordingWarTrigger(defUnit)
        val arg = listOf(attUnit, defUnit)
        val out = trigger.action(rng(), TriggerEnv(), arg)
        assertEquals(false, trigger.sawSelfAttacker)
        @Suppress("UNCHECKED_CAST")
        val eDef = out["e_defender"] as TriggerEnv
        assertEquals("def", eDef["mark"])
    }

    @Test
    fun `stopNextAction short-circuits - actionWar not invoked`() {
        val attUnit = FakeWarUnit("att", attacker = true)
        val trigger = RecordingWarTrigger(attUnit)
        val env = TriggerEnv()
        env["stopNextAction"] = true
        val out = trigger.action(rng(), env, listOf(attUnit, FakeWarUnit("def", false)))
        assertEquals(null, trigger.sawSelfAttacker)   // never ran
        assertEquals(true, out["stopNextAction"])
    }

    @Test
    fun `returning false from actionWar sets stopNextAction`() {
        val attUnit = FakeWarUnit("att", attacker = true)
        val trigger = RecordingWarTrigger(attUnit, continueAfter = false)
        val out = trigger.action(rng(), TriggerEnv(), listOf(attUnit, FakeWarUnit("def", false)))
        assertEquals(true, out["stopNextAction"])
    }

    @Test
    fun `processConsumableItem no-op when raiseType is not item`() {
        val unit = FakeWarUnit("u", attacker = true)
        val trigger = RecordingWarTrigger(unit, raiseType = BaseWarUnitTrigger.TYPE_NONE)
        assertFalse(trigger.processConsumableItem())
        assertFalse(unit.itemDeleted)
    }

    @Test
    fun `processConsumableItem records delete-delta and 사용 log for consumable item`() {
        val unit = FakeWarUnit("u", attacker = true)
        unit.heldItem = "비책"
        val trigger = RecordingWarTrigger(unit, raiseType = BaseWarUnitTrigger.TYPE_CONSUMABLE_ITEM)
        val consumed = trigger.processConsumableItem()
        assertTrue(consumed)
        assertTrue(unit.itemDeleted)                                  // flush-delta sink, not inline DB
        assertTrue(unit.hasActivatedSkill("아이템사용"))
        assertTrue(unit.hasActivatedSkill("아이템소모"))
        assertTrue(unit.plainActionLogs.any { it.contains("사용!") && it.contains("비책") })
    }

    @Test
    fun `processConsumableItem for plain (non-consumable) item activates use but does not delete`() {
        val unit = FakeWarUnit("u", attacker = true)
        val trigger = RecordingWarTrigger(unit, raiseType = BaseWarUnitTrigger.TYPE_ITEM)
        val consumed = trigger.processConsumableItem()
        assertFalse(consumed)                       // PHP returns false when not TYPE_CONSUMABLE_ITEM
        assertFalse(unit.itemDeleted)
        assertTrue(unit.hasActivatedSkill("아이템사용"))
        assertFalse(unit.hasActivatedSkill("아이템소모"))
    }

    @Test
    fun `WarUnitTriggerCaller rejects a non-WarUnit trigger`() {
        val plain = object : ObjectTrigger() {
            override val priority = 0
            override val objectId = "x"
            override fun action(rng: RandUtil, env: TriggerEnv, arg: Any?): TriggerEnv = env
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            WarUnitTriggerCaller(plain)
        }
    }

    @Test
    fun `WarUnitTriggerCaller accepts a BaseWarUnitTrigger`() {
        val unit = FakeWarUnit("u", attacker = true)
        val caller = WarUnitTriggerCaller(RecordingWarTrigger(unit))
        assertFalse(caller.isEmpty())
    }
}

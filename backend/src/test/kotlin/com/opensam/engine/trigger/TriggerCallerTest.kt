package com.opensam.engine.trigger

import com.opensam.entity.General
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TriggerCallerTest {

    private fun createGeneral(
        crew: Int = 1000,
        rice: Int = 500,
        injury: Short = 5,
        atmos: Short = 80,
    ): General {
        return General(
            id = 1,
            worldId = 1,
            name = "TestGeneral",
            crew = crew,
            rice = rice,
            injury = injury,
            atmos = atmos,
        )
    }

    // ========== TriggerCaller: basic mechanics ==========

    @Test
    fun `TriggerCaller fires triggers in priority order`() {
        val caller = TriggerCaller()
        val executionOrder = mutableListOf<String>()

        caller.addTrigger(object : ObjectTrigger {
            override val uniqueId = "second"
            override val priority = TriggerPriority.POST
            override fun action(env: TriggerEnv): Boolean {
                executionOrder.add("second")
                return true
            }
        })

        caller.addTrigger(object : ObjectTrigger {
            override val uniqueId = "first"
            override val priority = TriggerPriority.BEGIN
            override fun action(env: TriggerEnv): Boolean {
                executionOrder.add("first")
                return true
            }
        })

        val env = TriggerEnv(worldId = 1, year = 200, month = 3, generalId = 1)
        caller.fire(env)

        assertEquals(listOf("first", "second"), executionOrder)
    }

    @Test
    fun `TriggerCaller deduplicates by uniqueId - later overrides`() {
        val caller = TriggerCaller()
        val executed = mutableListOf<String>()

        caller.addTrigger(object : ObjectTrigger {
            override val uniqueId = "same_id"
            override val priority = TriggerPriority.BEGIN
            override fun action(env: TriggerEnv): Boolean {
                executed.add("original")
                return true
            }
        })

        caller.addTrigger(object : ObjectTrigger {
            override val uniqueId = "same_id"
            override val priority = TriggerPriority.BEGIN
            override fun action(env: TriggerEnv): Boolean {
                executed.add("override")
                return true
            }
        })

        val env = TriggerEnv(worldId = 1, year = 200, month = 3, generalId = 1)
        caller.fire(env)

        assertEquals(listOf("override"), executed)
    }

    @Test
    fun `TriggerCaller stops chain when trigger returns false`() {
        val caller = TriggerCaller()
        val executed = mutableListOf<String>()

        caller.addTrigger(object : ObjectTrigger {
            override val uniqueId = "blocker"
            override val priority = TriggerPriority.BEGIN
            override fun action(env: TriggerEnv): Boolean {
                executed.add("blocker")
                return false
            }
        })

        caller.addTrigger(object : ObjectTrigger {
            override val uniqueId = "after_blocker"
            override val priority = TriggerPriority.POST
            override fun action(env: TriggerEnv): Boolean {
                executed.add("after")
                return true
            }
        })

        val env = TriggerEnv(worldId = 1, year = 200, month = 3, generalId = 1)
        caller.fire(env)

        assertEquals(listOf("blocker"), executed)
        assertTrue(env.stopNextAction)
    }

    @Test
    fun `TriggerCaller is empty when no triggers added`() {
        val caller = TriggerCaller()
        assertTrue(caller.isEmpty())
        assertEquals(0, caller.size())
    }

    @Test
    fun `TriggerCaller fires on empty caller without error`() {
        val caller = TriggerCaller()
        val env = TriggerEnv(worldId = 1, year = 200, month = 3, generalId = 1)

        assertDoesNotThrow {
            caller.fire(env)
        }
    }

    // ========== InjuryReductionTrigger ==========

    @Test
    fun `InjuryReductionTrigger reduces injury by 1`() {
        val general = createGeneral(injury = 5)
        val trigger = InjuryReductionTrigger(general)
        val env = TriggerEnv(worldId = 1, year = 200, month = 3, generalId = general.id)

        trigger.action(env)

        assertEquals(4.toShort(), general.injury)
        assertEquals(true, env.vars["injuryReduced"])
    }

    @Test
    fun `InjuryReductionTrigger does nothing when injury is 0`() {
        val general = createGeneral(injury = 0)
        val trigger = InjuryReductionTrigger(general)
        val env = TriggerEnv(worldId = 1, year = 200, month = 3, generalId = general.id)

        trigger.action(env)

        assertEquals(0.toShort(), general.injury)
        assertNull(env.vars["injuryReduced"])
    }

    @Test
    fun `InjuryReductionTrigger has BEGIN priority`() {
        val general = createGeneral()
        val trigger = InjuryReductionTrigger(general)

        assertEquals(TriggerPriority.BEGIN, trigger.priority)
    }

    // ========== TroopConsumptionTrigger ==========

    @Test
    fun `TroopConsumptionTrigger consumes rice for troops`() {
        val general = createGeneral(crew = 1000, rice = 500)
        val trigger = TroopConsumptionTrigger(general)
        val env = TriggerEnv(worldId = 1, year = 200, month = 3, generalId = general.id)

        trigger.action(env)

        assertEquals(490, general.rice) // 1000/100 = 10 rice consumed
    }

    @Test
    fun `TroopConsumptionTrigger minimum consumption is 1`() {
        val general = createGeneral(crew = 50, rice = 100)
        val trigger = TroopConsumptionTrigger(general)
        val env = TriggerEnv(worldId = 1, year = 200, month = 3, generalId = general.id)

        trigger.action(env)

        assertEquals(99, general.rice) // min(50/100=0, 1) = 1
    }

    @Test
    fun `TroopConsumptionTrigger does nothing when crew is 0`() {
        val general = createGeneral(crew = 0, rice = 100)
        val trigger = TroopConsumptionTrigger(general)
        val env = TriggerEnv(worldId = 1, year = 200, month = 3, generalId = general.id)

        trigger.action(env)

        assertEquals(100, general.rice)
    }

    @Test
    fun `TroopConsumptionTrigger reduces atmos when not enough rice`() {
        val general = createGeneral(crew = 1000, rice = 3, atmos = 80)
        val trigger = TroopConsumptionTrigger(general)
        val env = TriggerEnv(worldId = 1, year = 200, month = 3, generalId = general.id)

        trigger.action(env)

        assertEquals(0, general.rice)
        assertEquals(75.toShort(), general.atmos) // 80 - 5
        assertEquals(true, env.vars["troopStarving"])
    }

    @Test
    fun `TroopConsumptionTrigger has FINAL priority`() {
        val general = createGeneral()
        val trigger = TroopConsumptionTrigger(general)

        assertEquals(TriggerPriority.FINAL, trigger.priority)
    }

    // ========== buildPreTurnTriggers ==========

    @Test
    fun `buildPreTurnTriggers includes injury and troop triggers`() {
        val general = createGeneral()
        val triggers = buildPreTurnTriggers(general)

        assertEquals(2, triggers.size)
        assertTrue(triggers.any { it is InjuryReductionTrigger })
        assertTrue(triggers.any { it is TroopConsumptionTrigger })
    }

    // ========== Modifier helpers ==========

    @Test
    fun `applyDomesticModifiers chains modifications`() {
        val general = createGeneral()
        val trigger1 = object : GeneralTrigger {
            override val uniqueId = "t1"
            override val priority = TriggerPriority.BEGIN
            override fun action(env: TriggerEnv) = true
            override fun onCalcDomestic(general: General, turnType: String, varType: String, value: Double, aux: Map<String, Any>): Double {
                return if (varType == "cost") value * 0.9 else value
            }
        }
        val trigger2 = object : GeneralTrigger {
            override val uniqueId = "t2"
            override val priority = TriggerPriority.POST
            override fun action(env: TriggerEnv) = true
            override fun onCalcDomestic(general: General, turnType: String, varType: String, value: Double, aux: Map<String, Any>): Double {
                return if (varType == "cost") value - 10 else value
            }
        }

        val result = applyDomesticModifiers(listOf(trigger1, trigger2), general, "징병", "cost", 100.0)

        assertEquals(80.0, result, 0.01) // 100 * 0.9 = 90, then 90 - 10 = 80
    }

    @Test
    fun `applyStatModifiers chains stat modifications`() {
        val general = createGeneral()
        val trigger = object : GeneralTrigger {
            override val uniqueId = "stat_boost"
            override val priority = TriggerPriority.BEGIN
            override fun action(env: TriggerEnv) = true
            override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any>): Double {
                return if (statName == "leadership") value + 10 else value
            }
        }

        val result = applyStatModifiers(listOf(trigger), general, "leadership", 50.0)

        assertEquals(60.0, result, 0.01)
    }
}

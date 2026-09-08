package opensamguk.logic.war

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.*

class ProcessFieldWarTest {
    private fun general(id: Int, crew: Int = 5000, rice: Int = 10000) = General(
        id = id, nationId = id, cityId = 10, leadership = 80, strength = 80, intel = 80,
        injury = 0, experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 1000, rice = rice, crew = crew, train = 100.0, atmos = 100.0, crewTypeId = 1100,
    )
    private fun input(g: General) = FieldCombatantInput(g, GameUnitConst.byId(1100)!!, 0, GeneralActionPipeline())

    @Test fun `empty field leaves attacker unchanged and consumes no random draws or hooks`() {
        val rng = RandUtil(LiteHashDrbg("empty-field"))
        val control = RandUtil(LiteHashDrbg("empty-field"))
        val g = general(1)
        val attacker = WarUnitGeneral(rng, WarUnitGeneralState(g), GeneralActionPipeline(),
            GameUnitConst.byId(1100)!!, 0, true, 0, false)
        val before = attacker.state.snapshot()
        val outcome = processFieldWarNG(rng, attacker, { _, _ -> null }, object : WarBattleHooks {
            override fun addTrain(unit: WarUnit, amount: Int) = error("empty training")
            override fun onFieldAdvanceLog(attacker: WarUnitGeneral) = error("empty advance")
            override fun onBattleResultLog(unit: WarUnit) = error("empty result")
        })
        assertFalse(outcome.contact)
        assertFalse(outcome.attackerRetreated)
        assertEquals(emptyList(), outcome.defeatedDefenderIds)
        assertEquals(before, attacker.state.snapshot())
        assertEquals(g, processFieldWar("empty", input(g), emptyList()).attackerAfter)
        assertEquals(control.nextRange(0.0, 100.0), rng.nextRange(0.0, 100.0))
    }

    @Test fun `exhausted field defenders end without city callbacks and deterministic real casualties`() {
        fun run(): ProcessFieldWarResult = processFieldWar("field-victory", input(general(1, 10000)),
            listOf(input(general(2, 1))), object : WarBattleHooks {
                override fun onAdvanceLog(attacker: WarUnitGeneral, city: WarUnitCity) = error("city advance")
                override fun addConflict(city: WarUnitCity, attacker: WarUnitGeneral): Boolean = error("city conflict")
                override fun onSupplyRout(attacker: WarUnitGeneral, city: WarUnitCity) = error("city supply")
            })
        val first = run(); val second = run()
        assertTrue(first.outcome.contact)
        assertEquals(listOf(2), first.outcome.defeatedDefenderIds)
        assertFalse(first.outcome.attackerRetreated)
        assertEquals(first.attacker.state.snapshot(), second.attacker.state.snapshot())
        assertEquals(first.defenders.map { it.state.snapshot() }, second.defenders.map { it.state.snapshot() })
        assertTrue(first.defenders.single().getCrew() < 1)
        assertEquals(10, first.attacker.state.snapshot().cityId)
    }

    @Test fun `field attacker retreat is explicit and does not defeat surviving defender`() {
        val result = processFieldWar("field-retreat", input(general(1, 5000, 0)), listOf(input(general(2, 10000))))
        assertTrue(result.outcome.contact)
        assertTrue(result.outcome.attackerRetreated)
        assertEquals(emptyList(), result.outcome.defeatedDefenderIds)
    }

    @Test fun `uncontacted later defenders keep exact original state`() {
        val untouched = general(3)
        val result = processFieldWar("early-retreat", input(general(1, 5000, 0)),
            listOf(input(general(2, 10000)), input(untouched)))
        assertTrue(result.outcome.attackerRetreated)
        assertEquals(untouched, result.defendersAfter[1])
        assertEquals(0, result.defenders[1].getPhase())
    }

    @Test fun `all field defenders can fall without an extra terminal wound or city phase`() {
        var resultLogs = 0
        val result = processFieldWar("two-defenders", input(general(1, 10000)),
            listOf(input(general(2, 1)), input(general(3, 1))), object : WarBattleHooks by FieldWarBattleHooks {
                override fun onBattleResultLog(unit: WarUnit) { resultLogs++ }
            })
        assertEquals(listOf(2, 3), result.outcome.defeatedDefenderIds)
        assertEquals(4, resultLogs, "one result pair per defeated defender; no extra terminal branch")
        assertEquals(2, result.attacker.getPhase())
    }

    @Test fun `duplicate combatant identities are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            processFieldWar("duplicate", input(general(1)), listOf(input(general(1))))
        }
    }
}

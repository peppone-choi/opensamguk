package opensamguk.logic.war.hwiha

import kotlin.test.*
import opensamguk.logic.input.*
import opensamguk.logic.world.StrategicNodeRef.LandProvince
import opensamguk.logic.war.hwiha.HwihaBattleConditions.CommandKey

class HwihaBattleConditionsTest {
    private val encounter=HwihaCorpsEncounter(HwihaEncounterParticipant("a",9,9,1,listOf(90,91)),
        listOf(HwihaEncounterParticipant("c",3,3,3,listOf(30)),HwihaEncounterParticipant("b",2,2,2,listOf(20))),
        LandProvince("B"),LandProvince("A"),HwihaPhase(200,1,1),"qa","a".repeat(64))
    private val forces=HwihaEncounterForces(encounter.encounterId,
        listOf(20 to 2,30 to 3,90 to 9,91 to 9).map { (id,commander) ->
            EncounterUnitForce(id,commander,commander,1100,100,50,50,0,100,null) },
        listOf(2,3,9).map { EncounterCommanderForce(it,70,70,70,70,70) })
    private val plans=HwihaBattlePlans.defaultFor(encounter)
    private val units=forces.units.map { HwihaGridExchange.UnitState(it.bugokId,it.troops,it.morale,it.fatigue,null) }
    private fun evaluator(p:HwihaBattlePlans=plans)=HwihaBattleConditions(encounter,forces,p)
    private fun planForNine(commands:List<BattlePlanCommand>)=HwihaBattlePlans(encounter.encounterId,
        listOf(CommanderBattlePlan(9,BattlePlanAction.ADVANCE,commands),
            CommanderBattlePlan(2,BattlePlanAction.HOLD,emptyList()),CommanderBattlePlan(3,BattlePlanAction.HOLD,emptyList())))

    @Test fun `same snapshot first slot wins in attacker then sorted defender order and only winner is consumed`() {
        val snapshot=units.map { it.copy(troops=50,morale=0) }
        val first=evaluator().evaluate(24,snapshot,emptySet())
        assertEquals(listOf(9,2,3),first.activations.map { it.commanderGeneralId })
        assertTrue(first.activations.all { it.slot==0 && it.action==BattlePlanAction.RETREAT })
        assertEquals(setOf(CommandKey(9,0),CommandKey(2,0),CommandKey(3,0)),first.triggered)
        val second=evaluator().evaluate(24,snapshot,first.triggered)
        assertTrue(second.activations.all { it.slot==1 })
        val third=evaluator().evaluate(24,snapshot,second.triggered)
        assertTrue(third.activations.all { it.slot==2 })
        assertTrue(evaluator().evaluate(24,snapshot,third.triggered).activations.isEmpty())
        assertFailsWith<UnsupportedOperationException> { (first.triggered as MutableSet<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (first.activations as MutableList<*>).clear() }
    }
    @Test fun `loss percentage is inclusive without rounding and includes reserve troops`() {
        val below=units.map { if(it.bugokId==90) it.copy(troops=1) else it }
        assertTrue(evaluator().evaluate(1,below,emptySet()).activations.isEmpty()) // 99 of 200 lost
        val boundary=below.map { if(it.bugokId==90) it.copy(troops=0) else it }
        assertEquals(listOf(9),evaluator().evaluate(1,boundary,emptySet()).activations.map { it.commanderGeneralId })
        assertEquals(0,evaluator().evaluate(1,boundary,emptySet()).activations.single().slot)
    }
    @Test fun `morale compares exact surviving troop weighted ratio strictly below threshold`() {
        val custom=planForNine(listOf(BattlePlanCommand(0,BattlePlanCondition.MORALE_BELOW,20,BattlePlanAction.HOLD)))
        val at=units.map { if(it.bugokId>=90) it.copy(morale=20) else it }
        assertTrue(evaluator(custom).evaluate(1,at,emptySet()).activations.isEmpty())
        val below=at.map { if(it.bugokId==90) it.copy(troops=1,morale=0) else it.copy(troops=99) }
        assertEquals(BattlePlanAction.HOLD,evaluator(custom).evaluate(1,below,emptySet()).activations.single().action)
        val empty=units.map { if(it.bugokId>=90) it.copy(troops=0) else it }
        assertEquals(BattlePlanAction.HOLD,evaluator(custom).evaluate(1,empty,emptySet()).activations.single().action)
    }
    @Test fun `round boundary and conflicting actions respect slot order not input order`() {
        val custom=planForNine(listOf(BattlePlanCommand(2,BattlePlanCondition.ROUND_AT_LEAST,2,BattlePlanAction.RETREAT),
            BattlePlanCommand(0,BattlePlanCondition.ROUND_AT_LEAST,2,BattlePlanAction.ADVANCE)))
        assertTrue(evaluator(custom).evaluate(1,units,emptySet()).activations.isEmpty())
        assertEquals(BattlePlanAction.ADVANCE,evaluator(custom).evaluate(2,units.reversed(),emptySet()).activations.single().action)
        assertEquals(BattlePlanAction.RETREAT,evaluator(custom).evaluate(3,units,setOf(CommandKey(9,0))).activations.single().action)
    }
    @Test fun `unknown history incomplete state and invalid thresholds are rejected`() {
        assertFailsWith<IllegalArgumentException> { evaluator().evaluate(1,units,setOf(CommandKey(99,0))) }
        assertFailsWith<IllegalArgumentException> { evaluator().evaluate(1,units,setOf(CommandKey(9,3))) }
        for(round in listOf(0,25)) assertFailsWith<IllegalArgumentException> { evaluator().evaluate(round,units,emptySet()) }
        for(bad in listOf(units.drop(1),units+units[0],units.map { it.copy(troops=101) },units.map { it.copy(morale=-1) }))
            assertFailsWith<IllegalArgumentException> { evaluator().evaluate(1,bad,emptySet()) }
        assertFailsWith<IllegalArgumentException> { HwihaBattleConditions(encounter,forces,
            HwihaBattlePlans("b".repeat(64),plans.plans)) }
    }
}

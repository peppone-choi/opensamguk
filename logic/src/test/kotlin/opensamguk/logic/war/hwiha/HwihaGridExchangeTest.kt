package opensamguk.logic.war.hwiha

import kotlin.test.*
import opensamguk.logic.input.*
import opensamguk.logic.world.*
import opensamguk.logic.world.HwihaBattlefieldGeometry.Position
import opensamguk.logic.war.hwiha.HwihaGridExchange.AttackIntent
import opensamguk.logic.war.hwiha.HwihaGridExchange.Outcome

class HwihaGridExchangeTest {
    private data class Fixture(val exchange: HwihaGridExchange, val encounter: HwihaCorpsEncounter,
        val forces: HwihaEncounterForces, val relations: HwihaEncounterRelations,
        val combat: HwihaEncounterCombatProfiles, val deployment: HwihaEncounterDeployment)
    private fun fixture(troops: Int = 500, leadership: Int = 70, power: Int = 100, allHostile: Boolean = false): Fixture {
        val phase = HwihaPhase(200,1,1)
        val node = StrategicNodeRef.LandProvince("B")
        val state = DeploymentProjection(RuleProfile.HWIHA,
            (1..3).map { DeploymentPerson(it,it,true,node,true) },
            (1..3).map { DeploymentUnit(it*10,it,troops,null) }, emptyList(),
            (1..3).map { HwihaDeployedCorps("order-$it",it,it,null,it,listOf(it*10),phase) })
        val encounter = HwihaCorpsEncounter(HwihaEncounterParticipant.from(state.deployed[0]),
            state.deployed.drop(1).map(HwihaEncounterParticipant::from), node, StrategicNodeRef.LandProvince("A"),phase,"qa","a".repeat(64))
        val relations = HwihaEncounterRelations.capture(encounter,state,
            if (allHostile) setOf(1 to 2,1 to 3,2 to 3) else setOf(1 to 2,1 to 3))
        val forces = HwihaEncounterForces(encounter.encounterId,
            (1..3).map { EncounterUnitForce(it*10,it,it,1100,troops,50,50,0,troops,null) },
            (1..3).map { EncounterCommanderForce(it,leadership,70,70,70,70) })
        val rules = HwihaUnitProfiles(1,"c".repeat(64),listOf(HwihaUnitProfile(1100,1,1,power,120,20)),emptySet())
        val combat = HwihaEncounterCombatProfiles.capture(forces,rules)
        val index = HanProvinceCellIndex("qa","a".repeat(64),"b".repeat(64),10,4,mapOf('1' to "PLAIN"),
            mapOf("A" to listOf(HanProvinceCell(0,1,'1')),
                "B" to (1..2).flatMap { row -> (1..7).map { col -> HanProvinceCell(col,row,'1') } }))
        val deployment = assertIs<HwihaEncounterDeployment.Result.Ready>(HwihaEncounterDeployment.prepareDefault(encounter,index)).deployment
        return Fixture(HwihaGridExchange(encounter,forces,relations,combat,deployment),encounter,forces,relations,combat,deployment)
    }
    private fun positioned(f: Fixture) = f.exchange.initialUnits.mapIndexed { i, unit ->
        unit.copy(position=listOf(Position(0,0),Position(1,0),Position(1,1))[i]) }

    @Test fun `frozen stats produce simultaneous losses morale and attack fatigue without live writes`() {
        val f=fixture();val before=positioned(f)
        val result=f.exchange.resolve(before,listOf(AttackIntent(10,20),AttackIntent(20,10)))
        assertEquals(listOf(20,20),result.attacks.map { it.potentialCasualties })
        assertEquals(listOf(480,480,500),result.units.map { it.troops })
        assertEquals(listOf(46,46,50),result.units.map { it.morale })
        assertEquals(listOf(2,2,0),result.units.map { it.fatigue })
        assertTrue(before.all { it.troops==500 && it.fatigue==0 })
        assertFailsWith<UnsupportedOperationException> { (result.units as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.attacks as MutableList<*>).clear() }
    }
    @Test fun `mutual destruction retains both valid attacks and clears dead occupancy`() {
        val f=fixture();val units=positioned(f).map { if(it.bugokId!=30) it.copy(troops=1) else it }
        val result=f.exchange.resolve(units,listOf(AttackIntent(20,10),AttackIntent(10,20)))
        assertTrue(result.attacks.all { it.outcome==Outcome.STRUCK && it.potentialCasualties==1 })
        assertEquals(listOf(0,0,500),result.units.map { it.troops })
        assertTrue(result.units.take(2).all { it.position==null && it.morale==0 })
    }
    @Test fun `overkill is capped once and iteration order cannot change exchange`() {
        val f=fixture(allHostile=true);val units=positioned(f).map { if(it.bugokId==20) it.copy(troops=1) else it }
        val intents=listOf(AttackIntent(10,20),AttackIntent(30,20))
        val result=f.exchange.resolve(units,intents)
        assertEquals(2,result.attacks.sumOf { it.potentialCasualties })
        assertEquals(0,result.units.single { it.bugokId==20 }.troops)
        val reversed=f.exchange.resolve(units.reversed(),intents.reversed())
        assertEquals(result.units,reversed.units);assertEquals(result.attacks,reversed.attacks)
    }
    @Test fun `nonhostile reserves zero morale and out of range actions have no cost`() {
        val f=fixture();val units=positioned(f)
        val friendly=f.exchange.resolve(units,listOf(AttackIntent(20,30)))
        assertEquals(Outcome.NOT_HOSTILE,friendly.attacks.single().outcome);assertEquals(units,friendly.units)
        val reserve=units.map { if(it.bugokId==10) it.copy(position=null) else it }
        assertEquals(reserve,f.exchange.resolve(reserve,listOf(AttackIntent(10,20))).units)
        assertEquals(Outcome.RESERVE,f.exchange.resolve(reserve,listOf(AttackIntent(20,10))).attacks.single().outcome)
        val routed=units.map { if(it.bugokId==10) it.copy(morale=0) else it }
        assertEquals(Outcome.INACTIVE,f.exchange.resolve(routed,listOf(AttackIntent(10,20))).attacks.single().outcome)
        assertEquals(Outcome.OUT_OF_REACH,f.exchange.resolve(units,listOf(AttackIntent(10,30))).attacks.single().outcome)
    }
    @Test fun `fractional percentage morale loss rounds upward`() {
        val f=fixture();val units=positioned(f).map { when(it.bugokId) {
            10 -> it.copy(troops=1)
            20 -> it.copy(troops=99)
            else -> it
        } }
        val result=f.exchange.resolve(units,listOf(AttackIntent(10,20)))
        assertEquals(1,result.attacks.single().potentialCasualties)
        assertEquals(98,result.units.single { it.bugokId==20 }.troops)
        assertEquals(48,result.units.single { it.bugokId==20 }.morale)
    }
    @Test fun `invalid state and ambiguous attack commands reject instead of partially applying`() {
        val f=fixture();val units=positioned(f)
        for (bad in listOf(units.dropLast(1),units+units[0],units.map { if(it.bugokId==10) it.copy(troops=501) else it },
            units.map { if(it.bugokId==10) it.copy(position=units[1].position) else it },
            units.map { if(it.bugokId==10) it.copy(troops=0) else it },
            units.map { if(it.bugokId==10) it.copy(position=Position(Int.MAX_VALUE,0)) else it }))
            assertFailsWith<IllegalArgumentException> { f.exchange.resolve(bad,emptyList()) }
        for (bad in listOf(listOf(AttackIntent(10,99)),listOf(AttackIntent(10,10)),
            listOf(AttackIntent(10,20),AttackIntent(10,30))))
            assertFailsWith<IllegalArgumentException> { f.exchange.resolve(units,bad) }
    }
    @Test fun `wrong frozen force snapshot and unsupported profiles reject before combat`() {
        val f=fixture();val changed=HwihaEncounterForces(f.forces.encounterId,
            f.forces.units.map { it.copy(training=51) },f.forces.commanders)
        assertFailsWith<IllegalArgumentException> { HwihaGridExchange(f.encounter,changed,f.relations,f.combat,f.deployment) }
        val unsupported=HwihaEncounterCombatProfiles.capture(f.forces,
            HwihaUnitProfiles(1,"c".repeat(64),listOf(HwihaUnitProfile(1200,1,3,80,80,10)),setOf(1100)))
        assertFailsWith<IllegalArgumentException> { HwihaGridExchange(f.encounter,f.forces,f.relations,unsupported,f.deployment) }
    }
    @Test fun `extreme legal integers cannot overflow casualties or morale and fatigue stays bounded`() {
        val f=fixture(troops=Int.MAX_VALUE,leadership=Int.MAX_VALUE,power=Int.MAX_VALUE)
        val units=positioned(f).map { it.copy(fatigue=99) }
        val result=f.exchange.resolve(units,listOf(AttackIntent(10,20)))
        assertEquals(Int.MAX_VALUE,result.attacks.single().potentialCasualties)
        assertEquals(0,result.units.single { it.bugokId==20 }.troops)
        assertEquals(0,result.units.single { it.bugokId==20 }.morale)
        assertEquals(100,result.units.single { it.bugokId==10 }.fatigue)
    }
}

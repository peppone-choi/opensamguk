package opensamguk.logic.war.hwiha

import kotlin.test.*
import opensamguk.logic.input.*
import opensamguk.logic.world.*
import opensamguk.logic.world.HwihaBattlefieldGeometry.Position

class HwihaBattlePlaybackTest {
    private fun playback(troops:Int=100, morale:Int=50, retreatAtOne:Boolean=false, noConditions:Boolean=false):HwihaBattlePlayback {
        val phase=HwihaPhase(200,1,1);val node=StrategicNodeRef.LandProvince("B")
        val state=DeploymentProjection(RuleProfile.HWIHA,(1..2).map { DeploymentPerson(it,it,true,node,true) },
            (1..2).map { DeploymentUnit(it*10,it,troops,null) },emptyList(),
            (1..2).map { HwihaDeployedCorps("order-$it",it,it,null,it,listOf(it*10),phase) })
        val encounter=HwihaCorpsEncounter(HwihaEncounterParticipant.from(state.deployed[0]),
            listOf(HwihaEncounterParticipant.from(state.deployed[1])),node,StrategicNodeRef.LandProvince("A"),phase,"qa","a".repeat(64))
        val forces=HwihaEncounterForces(encounter.encounterId,(1..2).map {
            EncounterUnitForce(it*10,it,it,1100,troops,50,morale,0,100,null) },
            (1..2).map { EncounterCommanderForce(it,70,70,70,70,70) })
        val relations=HwihaEncounterRelations.capture(encounter,state,setOf(1 to 2))
        val combat=HwihaEncounterCombatProfiles.capture(forces,HwihaUnitProfiles(1,"c".repeat(64),
            listOf(HwihaUnitProfile(1100,1,1,100,120,20)),emptySet()))
        val index=HanProvinceCellIndex("qa","a".repeat(64),"b".repeat(64),3,2,mapOf('1' to "PLAIN"),
            mapOf("A" to listOf(HanProvinceCell(0,0,'1')),"B" to listOf(HanProvinceCell(1,0,'1'),HanProvinceCell(2,0,'1'))))
        val deployment=assertIs<HwihaEncounterDeployment.Result.Ready>(HwihaEncounterDeployment.prepareDefault(encounter,index)).deployment
        val plans=if(retreatAtOne || noConditions) HwihaBattlePlans(encounter.encounterId,(1..2).map {
            CommanderBattlePlan(it,if(it==1)BattlePlanAction.ADVANCE else BattlePlanAction.HOLD,
                if(retreatAtOne)listOf(BattlePlanCommand(0,BattlePlanCondition.ROUND_AT_LEAST,1,BattlePlanAction.RETREAT)) else emptyList())
        }) else HwihaBattlePlans.defaultFor(encounter)
        return HwihaBattlePlayback(encounter,forces,relations,combat,plans,deployment)
    }
    private fun attack(round:Int)=HwihaRoundInput(round,emptyList(),listOf(HwihaGridExchange.AttackIntent(10,20),HwihaGridExchange.AttackIntent(20,10)))
    @Test fun `same round delivery is idempotent and the next round uses prior losses`() {
        val p=playback();val first=p.append(p.initialJournal(),attack(1))
        assertEquals(listOf(96,96),first.units.map { it.troops })
        val repeated=p.append(first.journal,attack(1))
        assertEquals(first.journal.snapshotId,repeated.journal.snapshotId)
        assertEquals(first.units,repeated.units);assertEquals(1,repeated.lastResolvedRound)
        val next=p.append(first.journal,attack(2))
        assertTrue(next.units.all { it.troops<96 });assertEquals(2,next.lastResolvedRound)
        assertFailsWith<IllegalArgumentException> { p.append(first.journal,HwihaRoundInput(1,emptyList(),emptyList())) }
        assertFailsWith<IllegalArgumentException> { p.append(first.journal,attack(3)) }
        assertFailsWith<UnsupportedOperationException> { (next.actions as MutableMap<*,*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (next.frames as MutableList<*>).clear() }
    }
    @Test fun `codec roundtrip replays exactly and a different frozen context is rejected`() {
        val p=playback();val result=p.append(p.initialJournal(),attack(1))
        val loaded=assertNotNull(HwihaBattleJournal.read(mapOf(HwihaBattleJournal.META_KEY to result.journal.toMetaValue())))
        val replay=p.replay(loaded)
        assertEquals(result.units,replay.units);assertEquals(result.triggered,replay.triggered)
        assertEquals(result.actions,replay.actions)
        assertFailsWith<IllegalArgumentException> { playback(morale=51).replay(loaded) }
        assertFailsWith<IllegalArgumentException> { playback(noConditions=true).replay(loaded) }
    }
    @Test fun `retreat and destruction are barriers not fake settlement and duplicate remains harmless`() {
        val p=playback(retreatAtOne=true);val input=HwihaRoundInput(1,emptyList(),emptyList())
        val result=p.append(p.initialJournal(),input)
        assertEquals(HwihaBattlePlayback.Barrier.RETREAT_REQUIRED,result.barrier)
        assertTrue(result.units.all { it.troops==100 && it.position!=null })
        assertEquals(result.units,p.append(result.journal,input).units)
        assertFailsWith<IllegalArgumentException> { p.append(result.journal,HwihaRoundInput(2,emptyList(),emptyList())) }
        val lethal=playback(troops=1);val dead=lethal.append(lethal.initialJournal(),attack(1))
        assertEquals(HwihaBattlePlayback.Barrier.DESTRUCTION_REQUIRED,dead.barrier)
        assertTrue(dead.units.all { it.troops==0 && it.position==null })
        assertTrue(dead.triggered.isEmpty())
    }
    @Test fun `hard round limit applies even without conditional commands`() {
        val p=playback(noConditions=true);var journal=p.initialJournal()
        for(round in 1..24)journal=p.append(journal,HwihaRoundInput(round,emptyList(),emptyList())).journal
        val result=p.replay(journal)
        assertEquals(24,result.lastResolvedRound);assertEquals(HwihaBattlePlayback.Barrier.ROUND_LIMIT,result.barrier)
        assertTrue(result.units.all { it.troops==100 })
    }
    @Test fun `sealed hold stance cannot be bypassed by submitted movement`() {
        val p=playback()
        assertFailsWith<IllegalArgumentException> { p.append(p.initialJournal(),HwihaRoundInput(1,
            listOf(HwihaGridExchange.MovementPlan(20,listOf(Position(0,0)))),emptyList())) }
    }
}

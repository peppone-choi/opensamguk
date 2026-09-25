package opensamguk.logic.war

import kotlin.test.*
import opensamguk.logic.input.*
import opensamguk.logic.world.*

class BattleAutopilotTest {
    private fun fixture(width:Int=5, bothAdvance:Boolean=false, morale:Int=50, count:Int=2, rows:Int=1, range:Int=1, allHostile:Boolean=false):Pair<BattlePlayback,BattleAutopilot> {
        val phase=Phase(200,1,1);val node=StrategicNodeRef.LandProvince("B")
        val state=DeploymentProjection(RuleProfile.HWIHA,(1..count).map { DeploymentPerson(it,it,true,node,true) },
            (1..count).map { DeploymentUnit(it*10,it,100,null) },emptyList(),
            (1..count).map { DeployedCorps("order-$it",it,it,null,it,listOf(it*10),phase) })
        val encounter=CorpsEncounter(EncounterParticipant.from(state.deployed[0]),
            state.deployed.drop(1).map(EncounterParticipant::from),node,StrategicNodeRef.LandProvince("A"),phase,"qa","a".repeat(64))
        val forces=EncounterForces(encounter.encounterId,(1..count).map {
            EncounterUnitForce(it*10,it,it,1100,100,50,morale,0,100,null) },
            (1..count).map { EncounterCommanderForce(it,70,70,70,70,70) })
        val relations=EncounterRelations.capture(encounter,state,if(allHostile) setOf(1 to 2,1 to 3,2 to 3) else (2..count).map { 1 to it }.toSet())
        val combat=EncounterCombatProfiles.capture(forces,UnitProfiles(1,"c".repeat(64),
            listOf(UnitProfile(1100,1,range,100,120,20)),emptySet()))
        val index=HanProvinceCellIndex("qa","a".repeat(64),"b".repeat(64),6,2,mapOf('1' to "PLAIN"),
            mapOf("A" to listOf(HanProvinceCell(0,0,'1')),"B" to (0 until rows).flatMap { row -> (1..width).map { HanProvinceCell(it,row,'1') } }))
        val deployment=assertIs<EncounterDeployment.Result.Ready>(EncounterDeployment.prepareDefault(encounter,index)).deployment
        val plans=if(bothAdvance) BattlePlans(encounter.encounterId,(1..count).map {
            CommanderBattlePlan(it,BattlePlanAction.ADVANCE,emptyList())
        }) else BattlePlans.defaultFor(encounter)
        return BattlePlayback(encounter,forces,relations,combat,plans,deployment) to
            BattleAutopilot(encounter,forces,relations,combat,plans,deployment)
    }

    @Test fun `advance closes distance while hold fires only after actual movement reaches range`() {
        val (playback,auto)=fixture();var journal=playback.initialJournal()
        for(round in 1..3) {
            val input=auto.next(journal)
            assertEquals(round,input.round)
            assertEquals(listOf(10),input.movements.map { it.bugokId })
            if(round<3)assertTrue(input.attacks.isEmpty())
            else assertEquals(listOf(10 to 20,20 to 10),input.attacks.map { it.attackerId to it.targetId })
            journal=playback.append(journal,input).journal
        }
        val result=playback.replay(journal)
        assertEquals(listOf(96,96),result.units.map { it.troops })
        assertEquals(listOf(3,4),result.units.map { it.position!!.col })
        val next=auto.next(journal)
        assertTrue(next.movements.isEmpty())
        assertEquals(2,next.attacks.size)
    }
    @Test fun `cold journal reproduces inputs and automatic play stops at an explicit barrier`() {
        val (playback,auto)=fixture();var journal=playback.initialJournal()
        while(playback.replay(journal).barrier==BattlePlayback.Barrier.NONE) {
            val input=auto.next(journal)
            val cold=assertNotNull(BattleJournal.read(mapOf(BattleJournal.META_KEY to journal.toMetaValue())))
            assertEquals(input.toMetaValue(),fixture().second.next(cold).toMetaValue())
            journal=playback.append(journal,input).journal
        }
        assertTrue(journal.rounds.size in 1..24)
        assertFailsWith<IllegalArgumentException> { auto.next(journal) }
        assertEquals(playback.replay(journal).units,fixture().first.replay(journal).units)
    }
    @Test fun `collision loser selects attacks from its actual stationary cell`() {
        val (playback,auto)=fixture(width=3,bothAdvance=true)
        val input=auto.next(playback.initialJournal())
        assertEquals(listOf(10,20),input.movements.map { it.bugokId })
        assertEquals(input.movements[0].path,input.movements[1].path)
        assertEquals(listOf(10 to 20,20 to 10),input.attacks.map { it.attackerId to it.targetId })
        val result=playback.append(playback.initialJournal(),input)
        assertEquals(listOf(1,2),result.units.map { it.position!!.col })
        assertEquals(listOf(96,96),result.units.map { it.troops })
        assertEquals(GridMovement.Outcome.CONTESTED,
            result.frames.single().movements.single { it.move.bugokId==20 }.move.outcome)
    }
    @Test fun `zero morale units neither move nor attack`() {
        val (playback,auto)=fixture(width=3,bothAdvance=true,morale=0)
        val input=auto.next(playback.initialJournal())
        assertTrue(input.movements.isEmpty());assertTrue(input.attacks.isEmpty())
        assertTrue(playback.append(playback.initialJournal(),input).units.all { it.troops==100 })
    }
    @Test fun `multiple defenders retain pairwise hostility and choose distance before identity`() {
        val (friendlyPlayback,friendlyAuto)=fixture(count=3,rows=2,range=5)
        val friendly=friendlyAuto.next(friendlyPlayback.initialJournal())
        assertTrue(friendly.movements.isEmpty())
        assertEquals(listOf(10 to 30,20 to 10,30 to 10),friendly.attacks.map { it.attackerId to it.targetId })
        val (hostilePlayback,hostileAuto)=fixture(count=3,rows=2,range=5,allHostile=true)
        val hostile=hostileAuto.next(hostilePlayback.initialJournal())
        assertEquals(listOf(10 to 30,20 to 30,30 to 20),hostile.attacks.map { it.attackerId to it.targetId })
    }
}

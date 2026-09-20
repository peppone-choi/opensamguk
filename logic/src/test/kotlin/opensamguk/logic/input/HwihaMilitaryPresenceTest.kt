package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.world.StrategicNodeRef

class HwihaMilitaryPresenceTest {
    private fun person(id: Int, nation: Int, node: String = "p$id") =
        DeploymentPerson(id, nation, true, StrategicNodeRef.LandProvince(node), false)
    private fun corps(id: Int, nation: Int) = HwihaDeployedCorps("order-$id", id, id, null, nation, listOf(id), HwihaPhase(200,1,1))
    private fun state() = DeploymentProjection(RuleProfile.HWIHA,
        listOf(person(1,1),person(2,2),person(3,0),person(4,1)),
        (1..4).map { DeploymentUnit(it,it,100,null) },emptyList(),
        listOf(corps(1,1),corps(2,2),corps(3,0),corps(4,1)))
    private fun ready(s: DeploymentProjection = state(), wars: Set<Pair<Int,Int>> = emptySet(), actor: Int = 1) =
        assertIs<MilitaryPresenceAssessment.Ready>(HwihaMilitaryPresence.assess(actor,s,wars))

    @Test fun `residents and owned units without deployment never block`() {
        assertTrue(ready(state().copy(deployed=emptyList()),setOf(1 to 2)).blockedProvinceIds.isEmpty())
    }
    @Test fun `peace allies and ceasefire do not imply war but explicit neutral blocks`() {
        assertEquals(listOf(3),ready().hostileCorps.map { it.ownerGeneralId })
        assertEquals(setOf("p3"),ready().blockedProvinceIds)
    }
    @Test fun `war direction is symmetric and own nation remains friendly`() {
        val a=ready(wars=setOf(1 to 2));val b=ready(wars=setOf(2 to 1))
        assertEquals(a,b);assertEquals(listOf(2,3),a.hostileCorps.map { it.ownerGeneralId })
    }
    @Test fun `unaffiliated actor excludes self and does not infer positive nation hostility`() {
        assertTrue(ready(actor=3,wars=setOf(0 to 2)).hostileCorps.isEmpty())
        val s=state().copy(people=state().people+person(5,0),units=state().units+DeploymentUnit(5,5,1,null),deployed=state().deployed+corps(5,0))
        assertEquals(listOf(5),ready(s,actor=3).hostileCorps.map { it.ownerGeneralId })
    }
    @Test fun `nation lookup needs no actor but rejects nonpositive nation`() {
        assertEquals(ready(),HwihaMilitaryPresence.assessNation(1,state(),emptySet()))
        assertEquals(setOf("p3"),assertIs<MilitaryPresenceAssessment.Ready>(HwihaMilitaryPresence.assessNation(99,state(),emptySet())).blockedProvinceIds)
        assertEquals(MilitaryPresenceAssessment.Unavailable,HwihaMilitaryPresence.assessNation(0,state(),emptySet()))
    }
    @Test fun `all corps including friendly must retain allegiance commander and troops`() {
        val s=state()
        for (bad in listOf(s.copy(people=s.people.map { if(it.id==4) it.copy(nationId=2) else it }),
            s.copy(units=s.units.map { if(it.id==4) it.copy(commanderRetainerId=99) else it }),
            s.copy(units=s.units.map { if(it.id==4) it.copy(troops=0) else it }),
            s.copy(deployed=s.deployed+s.deployed.first()))) {
            assertEquals(MilitaryPresenceAssessment.Unavailable,HwihaMilitaryPresence.assess(1,bad,emptySet()))
        }
    }
    @Test fun `commander location is used and changed lieutenant invalidates presence`() {
        val s=state();val c=corps(2,2).copy(commanderGeneralId=5,commanderRetainerId=20)
        val delegated=s.copy(people=s.people+person(5,2,"commander"),
            units=s.units.map { if(it.id==2) it.copy(commanderRetainerId=20) else it },
            retainers=listOf(DeploymentRetainer(20,2,5,true)),deployed=s.deployed.map { if(it.ownerGeneralId==2)c else it })
        assertEquals(setOf("commander","p3"),ready(delegated,setOf(1 to 2)).blockedProvinceIds)
        assertEquals(MilitaryPresenceAssessment.Unavailable,HwihaMilitaryPresence.assess(1,delegated.copy(retainers=emptyList()),setOf(1 to 2)))
    }
    @Test fun `insertion order cannot affect corps or province ordering`() {
        val s=state();val reversed=s.copy(people=s.people.reversed(),units=s.units.reversed(),deployed=s.deployed.reversed())
        assertEquals(ready(s,setOf(1 to 2)),ready(reversed,setOf(2 to 1)))
        assertEquals(listOf("p2","p3"),ready(reversed,setOf(1 to 2)).blockedProvinceIds.toList())
    }
    @Test fun `wrong profile unknown actor and duplicate empty snapshot identities are unavailable`() {
        for(s in listOf(state().copy(profile=RuleProfile.SAMMO),state().copy(people=emptyList()),
            state().copy(deployed=emptyList(),people=state().people+person(2,2))))
            assertEquals(MilitaryPresenceAssessment.Unavailable,HwihaMilitaryPresence.assess(1,s,emptySet()))
    }
    @Test fun `neutral deputy does not encounter the corps they command`() {
        val deployed=HwihaDeployedCorps("neutral-deputy",1,2,10,0,listOf(1),HwihaPhase(200,1,1))
        val s=DeploymentProjection(RuleProfile.HWIHA,listOf(person(1,0),person(2,0),person(3,0)),
            listOf(DeploymentUnit(1,1,100,10),DeploymentUnit(3,3,100,null)),
            listOf(DeploymentRetainer(10,1,2,true)),listOf(deployed,corps(3,0)))
        assertEquals(listOf(3),ready(s,actor=2).hostileCorps.map { it.ownerGeneralId })
        assertEquals(setOf("p3"),ready(s,actor=2).blockedProvinceIds)
    }

}

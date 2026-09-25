package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.world.StrategicNodeRef

class DeploymentRulesTest {
    private val node = StrategicNodeRef.LandProvince("p1")
    private val owner = DeploymentPerson(1, 1, false, node, false)
    private val deputy = DeploymentPerson(2, 1, true, node, false)
    private val request = DeploymentRequest(1, 4, listOf(8, 7))
    private val state = DeploymentProjection(RuleProfile.HWIHA, listOf(owner, deputy),
        listOf(DeploymentUnit(7,1,100,4),DeploymentUnit(8,1,200,4)),
        listOf(DeploymentRetainer(4,1,2,true)),emptyList())
    private val corps = DeployedCorps("order",1,2,4,1,listOf(7,8),Phase(200,1,1))
    private fun reason(s: DeploymentProjection, r: DeploymentRequest = request) =
        assertIs<DeploymentAssessment.Rejected>(DeploymentRules.assess(r,s)).reason

    @Test fun `deputy can command several cards without copying their resources`() {
        val result=assertIs<DeploymentAssessment.Eligible>(DeploymentRules.assess(request,state))
        assertEquals(2,result.commander.id)
        assertEquals(listOf(7,8),result.units.map { it.id })
        assertEquals(300,result.units.sumOf { it.troops })
    }
    @Test fun `owner can personally command unassigned cards`() {
        val own=state.copy(units=state.units.map { it.copy(commanderRetainerId=null) })
        assertEquals(1,assertIs<DeploymentAssessment.Eligible>(DeploymentRules.assess(request.copy(commanderRetainerId=null),own)).commander.id)
        assertEquals(DeploymentFailure.COMMANDER_CHANGED,reason(state,request.copy(commanderRetainerId=null)))
    }
    @Test fun `departure rejects foreign dead duplicated and reassigned units`() {
        assertEquals(DeploymentFailure.UNIT_UNAVAILABLE,reason(state.copy(units=state.units.map { it.copy(ownerId=9) })))
        assertEquals(DeploymentFailure.UNIT_UNAVAILABLE,reason(state.copy(units=state.units.map { it.copy(troops=0) })))
        assertEquals(DeploymentFailure.INVALID_INPUT,reason(state,request.copy(bugokIds=listOf(7,7))))
        assertEquals(DeploymentFailure.COMMANDER_CHANGED,reason(state.copy(units=state.units.map { it.copy(commanderRetainerId=null) })))
    }
    @Test fun `deputy must be live direct unowned npc and physically assembled`() {
        assertEquals(DeploymentFailure.COMMANDER_UNAVAILABLE,reason(state.copy(retainers=emptyList())))
        assertEquals(DeploymentFailure.COMMANDER_UNAVAILABLE,reason(state.copy(retainers=state.retainers + DeploymentRetainer(5,3,2,true))))
        assertEquals(DeploymentFailure.COMMANDER_UNAVAILABLE,reason(state.copy(people=listOf(owner,deputy.copy(isUnownedNpc=false)))))
        assertEquals(DeploymentFailure.MUST_ASSEMBLE,reason(state.copy(people=listOf(owner,deputy.copy(node=StrategicNodeRef.LandProvince("p2"))))))
        assertEquals(DeploymentFailure.DIFFERENT_NATION,reason(state.copy(people=listOf(owner,deputy.copy(nationId=2)))))
        assertEquals(DeploymentFailure.BATTLE_PENDING,reason(state.copy(people=listOf(owner,deputy.copy(inBattle=true)))))
    }
    @Test fun `duplicate deployment fails and active corps rechecks live cards and allegiance`() {
        val live=state.copy(deployed=listOf(corps))
        assertEquals(DeploymentFailure.ALREADY_DEPLOYED,reason(live))
        assertIs<DeploymentAssessment.Eligible>(DeploymentRules.assessActive(corps,live))
        assertIs<DeploymentAssessment.Rejected>(DeploymentRules.assessActive(corps,live.copy(units=emptyList())))
        assertIs<DeploymentAssessment.Rejected>(DeploymentRules.assessActive(corps,live.copy(people=listOf(owner.copy(nationId=2),deputy.copy(nationId=2)))))
        assertIs<DeploymentAssessment.Rejected>(DeploymentRules.assessActive(corps,live.copy(deployed=listOf(corps,corps))))
    }
    @Test fun `active deputy does not follow owner movement`() {
        val live=state.copy(deployed=listOf(corps),people=listOf(owner.copy(node=null),deputy))
        assertEquals(node,assertIs<DeploymentAssessment.Eligible>(DeploymentRules.assessActive(corps,live)).commander.node)
    }
    @Test fun `codec distinguishes absence from malformed and preserves multiple units`() {
        assertNull(DeploymentState.read(emptyMap()))
        val value=DeploymentState(listOf(corps))
        assertEquals(value,DeploymentState.read(mapOf(DeploymentState.META_KEY to value.toMetaValue())))
        for(raw in listOf(null,emptyMap<String,Any>(),value.toMetaValue()+ ("version" to 2),
            mapOf("version" to 1,"corps" to listOf(corps.toMetaValue()+ ("bugokIds" to listOf(7,7)))),
            mapOf("version" to 1,"corps" to listOf(corps.toMetaValue()+ ("nationId" to 1.0))))) {
            assertFailsWith<IllegalArgumentException> { DeploymentState.read(mapOf(DeploymentState.META_KEY to raw)) }
        }
    }
    @Test fun `codec rejects duplicate commanders and overlapping troops`() {
        assertFailsWith<IllegalArgumentException> { DeploymentState(listOf(corps,corps.copy(orderId="other"))) }
        assertFailsWith<IllegalArgumentException> { DeploymentState(listOf(corps.copy(commanderGeneralId=1,commanderRetainerId=null),corps)) }
        assertEquals(DeploymentFailure.WRONG_RULE_PROFILE,reason(state.copy(profile=RuleProfile.SAMMO)))
    }
}

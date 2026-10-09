package opensamguk.engine.politics

import opensamguk.engine.campaign.CampaignWorldFixture
import opensamguk.engine.campaign.DomesticContext

import kotlin.test.*
import opensamguk.engine.turn.*
import opensamguk.logic.input.*

/** Partial projection only: actual ActiveWorldMapValidator rejects missing rows at product load. */
class RiseMissingPositionEngineTest {
    @Test fun `context must preserve unknown child battlefield state rather than assume peace`() {
        val fixture=CampaignWorldFixture()
        val route=fixture.route()
        val base=fixture.person(1,0,route.startCity,userId="42",lord=false)
        val actor=base.copy(meta=base.meta+(PersonPolicyState.META_KEY to PersonPolicyState(
            0,true,"synthetic-independent-position","1",1).toMetaValue()))
        val child=fixture.person(2,0,route.startCity,lord=false)
        val world=fixture.world(listOf(actor to route.start,child to route.start),
            retainers=listOf(Retainer(20,1,"EXISTING",2,"child","guest")))
        val positions=assertNotNull(world.generalPositionSnapshot())
        assertNotNull(positions.stateFor(2))
        InMemoryTurnWorld::class.java.getDeclaredField("generalPosition").apply { isAccessible=true }
            .set(world,positions.withoutGeneral(2))
        val state=DomesticContext().projection(world)
        assertNull(state.person(2)!!.node)
        assertFalse(state.person(2)!!.inBattle)
        assertFalse(state.person(2)!!.spatialStateAvailable)
        assertEquals(PoliticalFailure.STATE_UNAVAILABLE, assertIs<PoliticalAssessment.Rejected>(
            PoliticalRules.assess(PoliticalRequest(1,PoliticalInput.RISE),state)).reason)
    }
}

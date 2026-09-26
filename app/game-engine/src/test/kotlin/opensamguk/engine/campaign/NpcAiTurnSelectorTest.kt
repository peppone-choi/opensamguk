package opensamguk.engine.campaign

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Retainer
import opensamguk.logic.input.AiSelectorKey
import opensamguk.logic.input.DeploymentRequest
import opensamguk.logic.input.MilitaryInput

class NpcAiTurnSelectorTest {
    @Test
    fun `general turn route keeps the delivered selectors in the old priority order`() {
        val fixture = CampaignWorldFixture()
        val selector = NpcAiTurnSelector(fixture.topology, fixture.metrics, DomesticContext())

        // Before the policy registry: retire -> deploy -> muster -> military -> people -> field -> personal.
        // Retire is PLANNED and already gated out; the remaining order stays intact.
        assertEquals(listOf(
            AiSelectorKey.DEPLOY,
            AiSelectorKey.MUSTER,
            AiSelectorKey.CITY_MILITARY,
            AiSelectorKey.PEOPLE,
            AiSelectorKey.FIELD,
            AiSelectorKey.PERSONAL,
        ), selector.selectionOrder)
    }

    @Test
    fun `an eligible unowned lord does not select muster before AI delivery`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val owner = fixture.person(801, 1, route.startCity)
        val lieutenant = fixture.person(802, 1, route.destinationCounty, lord = false)
        val card = Retainer(803, owner.id, "EXISTING", lieutenant.id, lieutenant.name,
            "lieutenant", hasOwnBugok = true)
        val world = fixture.world(listOf(owner to route.start, lieutenant to route.start),
            bugoks = listOf(fixture.unit(804, owner.id, 100).copy(commanderRetainerId = card.id)),
            retainers = listOf(card), wars = emptyList())
        val recorder = ChangeRecorder()
        assertIs<DeploymentExecution.Applied>(DeploymentExecutor(world, recorder,
            fixture.topology, fixture.metrics).deploy("npc-muster-corps",
                DeploymentRequest(owner.id, card.id, listOf(804))))
        recorder.moveGeneral(world, lieutenant.id, route.destination)

        val chosen = NpcAiTurnSelector(fixture.topology, fixture.metrics, DomesticContext())
            .select(world, owner.id, CampaignWorldFixture.NO_INPUT)
        assertNotEquals(MilitaryInput.MUSTER, chosen.actionCode)
    }
}

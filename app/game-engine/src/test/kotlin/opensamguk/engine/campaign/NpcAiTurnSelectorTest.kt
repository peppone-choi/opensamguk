package opensamguk.engine.campaign

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Retainer
import opensamguk.logic.input.AiSelectorKey
import opensamguk.logic.input.DeploymentRequest
import opensamguk.logic.input.MilitaryInput
import opensamguk.logic.input.PersonalInput
import opensamguk.logic.input.PeopleInput
import opensamguk.logic.input.PeopleRequest
import opensamguk.logic.input.TalentDiscovery

class NpcAiTurnSelectorTest {
    private fun selector(fixture: CampaignWorldFixture, context: DomesticContext = DomesticContext()) =
        NpcAiTurnSelector(fixture.topology, fixture.metrics, context,
            NpcObservationFactory(fixture.topology, fixture.metrics, fixture.bundle.commanderyIndex,
                domesticContext = context))

    @Test
    fun `lord in own county searches for an undiscovered local free person`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val owner = fixture.person(801, 1, route.startCity)
        val candidate = fixture.person(802, 0, route.startCity, lord = false)
        val world = fixture.world(listOf(owner to route.start, candidate to route.start),
            wars = emptyList(),
            cityChanges = { if (it.id == route.startCity) it.copy(nationId = 1) else it })
        val chosen = selector(fixture).select(world, owner.id, CampaignWorldFixture.NO_INPUT)

        assertEquals(PeopleInput.SEARCH, chosen.actionCode)
        assertEquals(PeopleInput.canonicalJson(PeopleRequest(owner.id, PeopleInput.SEARCH, null)), chosen.argJson)
        assertEquals(NpcPeopleSelector(DomesticContext()).select(world, owner.id, CampaignWorldFixture.NO_INPUT),
            chosen)
    }

    @Test
    fun `lord in own county employs a discovered local free person`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val owner = fixture.person(801, 1, route.startCity).let {
            it.copy(meta = TalentDiscovery.add(it.meta, 802))
        }
        val candidate = fixture.person(802, 0, route.startCity, lord = false)
        val world = fixture.world(listOf(owner to route.start, candidate to route.start),
            wars = emptyList(),
            cityChanges = { if (it.id == route.startCity) it.copy(nationId = 1) else it })
        val chosen = selector(fixture).select(world, owner.id, CampaignWorldFixture.NO_INPUT)

        assertEquals(PeopleInput.EMPLOY, chosen.actionCode)
        assertEquals(PeopleInput.canonicalJson(PeopleRequest(owner.id, PeopleInput.EMPLOY, candidate.id)),
            chosen.argJson)
        assertEquals(NpcPeopleSelector(DomesticContext()).select(world, owner.id, CampaignWorldFixture.NO_INPUT),
            chosen)
    }

    @Test fun `registered court reward belongs to the court turn and does not break general turn startup`() {
        val fixture = CampaignWorldFixture()
        assertNotNull(selector(fixture))
    }

    @Test
    fun `general turn route keeps the delivered selectors in the old priority order`() {
        val fixture = CampaignWorldFixture()
        val selector = selector(fixture)

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

        val chosen = selector(fixture)
            .select(world, owner.id, CampaignWorldFixture.NO_INPUT)
        assertNotEquals(MilitaryInput.MUSTER, chosen.actionCode)
    }

    @Test
    fun `missing observed domestic state cannot fall back to authoritative world selectors`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val owner = fixture.person(801, 1, route.startCity)
        val world = fixture.world(listOf(owner to route.start),
            cityChanges = { city -> if (city.id == route.startCity) city.copy(nationId = 1) else city })
        val reserved = CampaignWorldFixture.NO_INPUT
        val context = DomesticContext()
        assertEquals(PersonalInput.SELF_TRAIN,
            NpcPersonalSelector(context).select(world, owner.id, reserved).actionCode)
        val withoutDomestic = NpcObservationFactory(fixture.topology, fixture.metrics,
            fixture.bundle.commanderyIndex)

        assertEquals(reserved, NpcAiTurnSelector(fixture.topology, fixture.metrics, context, withoutDomestic)
            .select(world, owner.id, reserved))
    }
}

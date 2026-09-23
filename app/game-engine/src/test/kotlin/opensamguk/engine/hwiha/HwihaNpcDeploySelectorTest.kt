package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.input.*

class HwihaNpcDeploySelectorTest {
    private val fixture = HwihaCampaignWorldFixture()
    private val route = fixture.route()
    private val selector = HwihaNpcDeploySelector(fixture.topology, fixture.metrics)

    private fun world(userId: String? = null, troops: Int = 1000, defence: Int = 100): InMemoryTurnWorld =
        fixture.world(listOf(fixture.person(1, 1, route.startCity, userId = userId) to route.first),
            bugoks = listOf(fixture.unit(7, 1, troops)),
            cityChanges = { city -> city.copy(defence = defence, nationId = if (city.id == route.destinationCounty) 2 else city.nationId) })

    @Test fun `an unreserved npc picks the cheapest reachable hostile county it can besiege`() {
        val choice = assertNotNull(selector.choose(world(), 1))
        assertEquals(listOf(7), choice.bugokIds)
        val w = world()
        val county = w.administrativeCountyIds.first { w.landNodeOfCity(it) == choice.destination }
        assertNotEquals(1, w.getCityById(county)!!.nationId, "the target is hostile")
        assertEquals(choice, selector.choose(world(), 1), "same world, same choice")
        val reserved = selector.select(w, 1, HwihaCampaignWorldFixture.NO_INPUT)
        assertEquals(HwihaDeployInput.INPUT_ID, reserved.actionCode); assertFalse(reserved.rowExists); assertNull(reserved.requestId)
    }

    @Test fun `humans, reservations, thin armies and deployed corps are left alone`() {
        assertNull(selector.choose(world(userId = "42"), 1))
        assertNull(selector.choose(world(troops = 150), 1), "cannot reach twice the garrison anywhere")
        val reserved = ReservedTurn("action.enlist", "{}", rowExists = true)
        assertSame(reserved, selector.select(world(), 1, reserved))
        val deployed = world(); fixture.deploy(deployed, ChangeRecorder(), 1, listOf(7), route.destination)
        assertNull(selector.choose(deployed, 1))
    }

    @Test fun `the deploy handler accepts a synthesized npc order and still forbids an unowned human reservation`() {
        val w = world()
        val choice = assertNotNull(selector.choose(w, 1))
        val handler = HwihaDeployHandler(w, ChangeRecorder(), fixture.topology, fixture.metrics)
        assertIs<HwihaTurnOutcome.Applied>(handler.handle(1, HwihaDeployInput.canonicalJson(choice), null, null, npcSelected = true))
        val corps = HwihaDeploymentState.read(w.getGeneralById(1)!!.meta)!!.corps.single()
        assertEquals(HwihaNpcDeploySelector.orderId(w, 1), corps.orderId)
        assertEquals(choice.destination, HwihaCorpsOrder.read(w.getGeneralById(1)!!.meta, fixture.topology)!!.destination)
        val human = world(userId = "42")
        assertEquals("FORBIDDEN", assertIs<HwihaTurnOutcome.Rejected>(HwihaDeployHandler(human, ChangeRecorder(),
            fixture.topology, fixture.metrics).handle(1, HwihaDeployInput.canonicalJson(choice), null, null, npcSelected = true)).code)
    }
}

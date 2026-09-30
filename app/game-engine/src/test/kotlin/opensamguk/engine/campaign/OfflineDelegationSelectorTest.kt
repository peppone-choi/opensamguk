package opensamguk.engine.campaign

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.domestic.CountyPolicyState
import opensamguk.logic.domestic.FieldInput
import opensamguk.logic.domestic.PolicySetting
import opensamguk.logic.domestic.PolicySlot
import opensamguk.logic.input.CountyAssignment
import opensamguk.logic.input.Phase

class OfflineDelegationSelectorTest {
    private val fixture = CampaignWorldFixture()
    private val empty = ReservedTurn("휴식", "{}", rowExists = false)

    @Test
    fun `explicit active county policy selects its matching delivered input only`() {
        val route = fixture.route()
        val actor = fixture.person(901, 1, route.startCity, userId = "42").let { general ->
            general.copy(meta = general.meta + (CountyAssignment.META_KEY to
                CountyAssignment("seat-901", general.id, 1, route.startCity).toMetaValue()))
        }
        val policy = CountyPolicyState(PolicySlot(PolicySetting("AGRICULTURE", "policy-901", actor.id,
            Phase(200, 1, 1)), null), null)
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 1,
                meta = city.meta + (CountyPolicyState.META_KEY to policy.toMetaValue())) else city
        })
        val selector = OfflineDelegationSelector(DomesticContext())

        val chosen = selector.select(world, actor.id, empty)
        assertEquals(FieldInput.FARM, chosen.actionCode)
        assertEquals(42, chosen.reservationOwnerUserId)
        assertIs<TurnOutcome.Applied>(FieldHandler(world, ChangeRecorder(), DomesticContext()).handle(
            chosen.actionCode, actor.id, chosen.argJson, chosen.requestId,
            chosen.reservationOwnerUserId, npcSelected = !chosen.rowExists))
        val reserved = ReservedTurn(FieldInput.COMMERCE, "{}", rowExists = true)
        assertEquals(reserved, selector.select(world, actor.id, reserved), "human reservation has priority")
        world.applyGeneralDirtyFree(world.getGeneralById(actor.id)!!.copy(userId = null))
        assertEquals(empty, selector.select(world, actor.id, empty), "unowned NPC keeps its own selection path")
    }

    @Test
    fun `missing policy leaves the delegated human resting`() {
        val route = fixture.route()
        val actor = fixture.person(902, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 1) else city
        })
        assertEquals(empty, OfflineDelegationSelector(DomesticContext()).select(world, actor.id, empty))
    }
}

package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.input.HwihaMarchReactions
import opensamguk.logic.world.LandMarchEntry

/** Pending reaction records (요격·회피) must not stall every march until their resolver is wired. */
class HwihaMarchReactionPolicyTest {
    private val fixture = HwihaCampaignWorldFixture()
    private val route = fixture.route()

    private fun march(reactions: Any): Pair<LandMarchEntry, Boolean> {
        val world = fixture.world(listOf(fixture.person(1, 1, route.startCity) to route.start),
            bugoks = listOf(fixture.unit(7, 1, 1000)), extraStateMeta = mapOf(HwihaMarchReactions.META_KEY to reactions))
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, 1, listOf(7), route.destination)
        fixture.nextPhase(world)
        val entry = HwihaMilitaryPresenceProvider(world, fixture.topology, fixture.metrics)
            .entryAt(1, route.first, HwihaMarchReactionPolicy.NON_BLOCKING)
        fixture.movement(world, recorder).onTurn(1, HwihaCampaignWorldFixture.NO_INPUT)
        return entry to (world.positionOf(1) != route.start)
    }

    @Test fun `pending interception records do not stop marches, a malformed authority still does`() {
        val pending = HwihaMarchReactions.Empty.toMetaValue() + ("interceptions" to listOf(mapOf("orderId" to "enemy", "kind" to "INTERCEPT")))
        assertEquals(LandMarchEntry.CLEAR to true, march(pending))
        assertEquals(LandMarchEntry.CLEAR to true, march(HwihaMarchReactions.Empty.toMetaValue()))
        assertEquals(LandMarchEntry.UNAVAILABLE to false, march(mapOf("version" to 9)))
    }
}

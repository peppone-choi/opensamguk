package opensamguk.engine.campaign

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.input.MarchReactions
import opensamguk.logic.world.LandMarchEntry

/** Pending reaction records (요격·회피) must not stall every march until their resolver is wired. */
class MarchReactionPolicyTest {
    private val fixture = CampaignWorldFixture()
    private val route = fixture.route()

    private fun march(reactions: Any): Pair<LandMarchEntry, Boolean> {
        val world = fixture.world(listOf(fixture.person(1, 1, route.startCity) to route.start),
            bugoks = listOf(fixture.unit(7, 1, 1000)), extraStateMeta = mapOf(MarchReactions.META_KEY to reactions))
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, 1, listOf(7), route.destination)
        fixture.nextPhase(world)
        val entry = MilitaryPresenceProvider(world, fixture.topology, fixture.metrics)
            .entryAt(1, route.first, MarchReactionPolicy.NON_BLOCKING)
        fixture.movement(world, recorder).onTurn(1, CampaignWorldFixture.NO_INPUT)
        return entry to (world.positionOf(1) != route.start)
    }

    @Test fun `pending interception records do not stop marches, a malformed authority still does`() {
        val pending = MarchReactions.Empty.toMetaValue() + ("interceptions" to listOf(mapOf("orderId" to "enemy", "kind" to "INTERCEPT")))
        assertEquals(LandMarchEntry.CLEAR to true, march(pending))
        assertEquals(LandMarchEntry.CLEAR to true, march(MarchReactions.Empty.toMetaValue()))
        assertEquals(LandMarchEntry.UNAVAILABLE to false, march(mapOf("version" to 9)))
    }

    @Test fun `domestic corps policies INTERCEPT and EVADE written as reaction orders do not stall marches`() {
        // 내정 스트림(ReactionInventory)이 군단 방침에서 다시 쓰는 꼴 그대로다.
        val since = opensamguk.logic.input.Phase(200, 1, 1)
        val written = MarchReactions.of(
            listOf(opensamguk.logic.input.ReactionOrder("o-intercept", 50, 51, 2, since)),
            listOf(opensamguk.logic.input.ReactionOrder("o-evade", 60, 61, 2, since)),
        ).toMetaValue()
        assertEquals(MarchReactions.Presence.PENDING, MarchReactions.presence(mapOf(MarchReactions.META_KEY to written)))
        assertEquals(LandMarchEntry.CLEAR to true, march(written))
    }
}

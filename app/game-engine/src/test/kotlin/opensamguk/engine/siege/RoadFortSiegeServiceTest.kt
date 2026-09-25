package opensamguk.engine.siege

import opensamguk.engine.campaign.*

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.input.RoadFort
import opensamguk.logic.input.RoadFortState

class RoadFortSiegeServiceTest {
    private val fixture = CampaignWorldFixture()

    private fun prepared() : Triple<opensamguk.engine.turn.InMemoryTurnWorld, ChangeRecorder, RoadFort> {
        val route = fixture.route()
        val edge = fixture.topology.traversalEdges.first { (it.from == route.start && it.to == route.first) ||
            (!it.directed && it.from == route.first && it.to == route.start) }
        val fort = RoadFort(RoadFort.siteId(edge.id, 0, 0), edge.id, route.start.id, 0, 0,
            ownerNationId = 2, wall = 100, garrison = 0)
        val actor = fixture.person(501, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(actor to route.start), bugoks = listOf(fixture.unit(7, actor.id, 1000)),
            extraStateMeta = mapOf(RoadFortState.META_KEY to RoadFortState.toMetaValue(listOf(fort))))
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, actor.id, listOf(7), route.first)
        return Triple(world, recorder, fort)
    }

    @Test fun `three maintained phases capture a road fort and persist its new owner`() {
        val (world, recorder, fort) = prepared()
        val siege = RoadFortSiegeService(world, recorder, fixture.topology, fixture.metrics)
        assertNull(siege.start(501, fort.id))
        assertEquals(0, RoadFortState.read(world.getState().meta).single().siegeProgress)
        siege.settleBoundary()
        assertEquals(34, RoadFortState.read(world.getState().meta).single().siegeProgress)
        siege.settleBoundary()
        assertEquals(68, RoadFortState.read(world.getState().meta).single().siegeProgress)
        siege.settleBoundary()
        val captured = RoadFortState.read(world.getState().meta).single()
        assertEquals(1, captured.ownerNationId)
        assertNull(captured.besiegerGeneralId)
        assertTrue(recorder.kvDirty().keys.any { it.key == RoadFortState.META_KEY })
    }

    @Test fun `peace releases a siege without transferring the fort`() {
        val (world, recorder, fort) = prepared()
        val siege = RoadFortSiegeService(world, recorder, fixture.topology, fixture.metrics)
        assertNull(siege.start(501, fort.id))
        siege.settleBoundary()
        world.updateDiplomacy(1, 2, 2, 0)
        world.updateDiplomacy(2, 1, 2, 0)
        siege.settleBoundary()
        val released = RoadFortState.read(world.getState().meta).single()
        assertEquals(2, released.ownerNationId)
        assertNull(released.besiegerGeneralId)
        assertEquals(0, released.siegeProgress)
    }
}

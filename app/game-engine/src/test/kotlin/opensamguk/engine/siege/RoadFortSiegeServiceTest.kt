package opensamguk.engine.siege

import opensamguk.engine.campaign.*

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.input.RoadFort
import opensamguk.logic.input.RoadFortState
import opensamguk.logic.record.AudienceTarget
import opensamguk.logic.record.EventKind
import opensamguk.logic.record.EventRef
import opensamguk.logic.record.PublicationState
import opensamguk.logic.record.RefRole

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
        val events = world.consumeDirtyState().gameEvents.filter { it.kind == EventKind.ROAD_FORT_CAPTURED }
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(AudienceTarget.Public, event.audience)
        assertEquals(PublicationState.PUBLISHED, event.publication.state)
        assertEquals(mapOf(
            RefRole.ROAD_FORT to EventRef.RoadFort(fort.id),
            RefRole.FROM_NATION to EventRef.Nation(2),
            RefRole.TO_NATION to EventRef.Nation(1),
        ), event.refs)
        assertTrue(event.facts.isEmpty())
        assertEquals(world.worldId.value, event.worldId)

        siege.settleBoundary()
        assertTrue(world.consumeDirtyState().gameEvents.none { it.kind == EventKind.ROAD_FORT_CAPTURED },
            "a fort already captured cannot publish a second capture")
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
        assertTrue(world.consumeDirtyState().gameEvents.none { it.kind == EventKind.ROAD_FORT_CAPTURED },
            "start, progress and lift do not transfer ownership")
    }

    @Test fun `rejected siege start cannot publish a capture`() {
        val (world, recorder, _) = prepared()
        val siege = RoadFortSiegeService(world, recorder, fixture.topology, fixture.metrics)
        assertEquals(RoadFortSiegeService.Failure.FORT_NOT_FOUND, siege.start(501, "unknown"))
        assertTrue(world.consumeDirtyState().gameEvents.none { it.kind == EventKind.ROAD_FORT_CAPTURED })
    }

    @Test fun `two forts captured in the same phase receive different cause keys`() {
        val (world, recorder, first) = prepared()
        val second = first.copy(id = RoadFort.siteId(first.edgeId, 1, 0), row = 1)
        world.setGameEnvValue(RoadFortState.META_KEY, RoadFortState.toMetaValue(listOf(first, second)))
        val siege = RoadFortSiegeService(world, recorder, fixture.topology, fixture.metrics)
        assertNull(siege.start(501, first.id))
        assertNull(siege.start(501, second.id))

        repeat(3) { siege.settleBoundary() }

        val captures = world.consumeDirtyState().gameEvents.filter { it.kind == EventKind.ROAD_FORT_CAPTURED }
        assertEquals(setOf(first.id, second.id), captures.map {
            (it.refs.getValue(RefRole.ROAD_FORT) as EventRef.RoadFort).id
        }.toSet())
        assertEquals(2, captures.map { it.eventKey }.toSet().size)
    }
}

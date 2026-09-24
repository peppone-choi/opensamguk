package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.input.*
import opensamguk.logic.world.*

/** Direct reaction branches: a real scheme province, live FULL sight, one-province interception and EVADE retreat. */
class HwihaMarchReactionInterpreterTest {
    private val fixture = HwihaCampaignWorldFixture()
    private val route = fixture.route()
    private val policy = HwihaMarchReactionInterpreter(fixture.topology, fixture.metrics, fixture.bundle.commanderyIndex)

    @Test fun `policy inventory rebuild preserves installed schemes`() {
        val world = fixture.world(listOf(fixture.person(1, 1, route.startCity) to route.start,
            fixture.person(2, 2, route.destinationCounty) to route.destination))
        val scheme = HwihaInstalledScheme("scheme-1", 2, 2, route.first.id, HwihaPhase(200, 1, 1))
        world.setGameEnvValue(HwihaMarchReactions.META_KEY,
            HwihaMarchReactions.of(emptyList(), emptyList(), listOf(scheme)).toMetaValue())
        assertEquals(HwihaReactionInventory.Result.UNCHANGED, HwihaReactionInventory(world, ChangeRecorder()).rebuild())
        assertEquals(listOf(scheme), HwihaMarchReactions.read(world.getState().meta)?.installedSchemes)
    }

    @Test fun `entering an enemy installed scheme province causes a contact stop`() {
        val world = fixture.world(listOf(fixture.person(1, 1, route.startCity) to route.start,
            fixture.person(2, 2, route.destinationCounty) to route.destination),
            bugoks = listOf(fixture.unit(7, 1, 1000)),
            cityChanges = { city -> city.copy(nationId = if (city.id == route.destinationCounty) 2 else 1) })
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, 1, listOf(7), route.destination)
        val scheme = HwihaInstalledScheme("scheme-1", 2, 2, route.first.id, HwihaPhase(200, 1, 1))
        world.setGameEnvValue(HwihaMarchReactions.META_KEY,
            HwihaMarchReactions.of(emptyList(), emptyList(), listOf(scheme)).toMetaValue())
        fixture.nextPhase(world)
        assertEquals(LandMarchEntry.ENCOUNTER,
            HwihaMilitaryPresenceProvider(world, fixture.topology, fixture.metrics).entryAt(1, route.first, policy))
        HwihaAssignmentMarchTurn(world, recorder, fixture.topology, fixture.metrics, fixture.cells, reactions = policy)
            .onTurn(1, HwihaCampaignWorldFixture.NO_INPUT)
        assertEquals(route.first, world.positionOf(1))
        assertEquals(LandMarchStop.ENCOUNTER,
            HwihaCorpsMarchState.read(world.getGeneralById(1)!!.meta, fixture.topology, fixture.metrics)?.checkpoint?.stop)
        assertNull(HwihaCorpsEncounter.read(world.getGeneralById(1)!!.meta, fixture.topology),
            "a scheme has no fabricated defending corps")
    }

    @Test fun `one-province interceptor requires live FULL sight of the entering corps`() {
        val index = fixture.bundle.commanderyIndex
        val crossing = fixture.topology.traversalEdges.firstNotNullOf { edge ->
            val from = edge.from as? StrategicNodeRef.LandProvince
            val to = edge.to as? StrategicNodeRef.LandProvince
            val fromNo = from?.let { index.commanderyOf(it.id) }
            val toNo = to?.let { index.commanderyOf(it.id) }
            val city = if (toNo == null) null else fixture.bundle.projection.bindingsByCityId.entries.firstOrNull {
                binding -> binding.value.landProvinceId?.let(index::commanderyOf) == toNo
            }?.key
            if (from == null || to == null || fromNo == null || toNo == null || fromNo == toNo || city == null ||
                fixture.metrics.edgesById[edge.id]!!.costMm > LandMarchMetricSnapshot.NORMAL_BUDGET_MM) null
            else Triple(from, to, city)
        }
        val (from, target, towerCity) = crossing
        val world = fixture.world(listOf(fixture.person(1, 1, route.startCity) to target,
            fixture.person(2, 2, route.startCity) to from),
            bugoks = listOf(fixture.unit(7, 1, 1000), fixture.unit(8, 2, 1000)),
            cityChanges = { city -> city.copy(nationId = if (city.id == towerCity) 2 else 1) })
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, 1, listOf(7), target)
        fixture.deploy(world, recorder, 2, listOf(8), target)
        world.setGameEnvValue(HwihaMarchReactions.META_KEY,
            HwihaMarchReactions.of(listOf(HwihaReactionOrder("order-2", 2, 2, 2, HwihaPhase(200, 1, 1))), emptyList()).toMetaValue())
        assertEquals(LandMarchEntry.CLEAR, policy.entryHazard(world, 1, target), "FOG cannot intercept")
        val city = world.getCityById(towerCity)!!
        world.updateCity(city.copy(meta = city.meta + (HwihaMetaVisionSourceReader.COUNTY_WORKS_KEY to
            mapOf("version" to 1, "works" to listOf(mapOf("kind" to "WATCHTOWER_BEACON", "status" to "COMPLETE"))))))
        val distant = fixture.topology.landProvinceIds.asSequence().sorted().map { StrategicNodeRef.LandProvince(it) }
            .filter { it != from && it != target }.first { candidate ->
                (StrategicPathResolver.resolveLandMarch(fixture.topology, StrategicPathRequest(candidate, target, 1),
                    fixture.passage(), fixture.metrics) as? LandMarchPathResult.Resolved)?.path?.edgeIds?.size == 2
            }
        recorder.moveGeneral(world, 2, distant)
        assertEquals(LandMarchEntry.CLEAR, policy.entryHazard(world, 1, target), "two provinces exceed the confirmed range")
        recorder.moveGeneral(world, 2, from)
        assertEquals(LandMarchEntry.ENCOUNTER, policy.entryHazard(world, 1, target), "FULL sight within one province intercepts")
        assertEquals(LandMarchEntry.ENCOUNTER,
            HwihaMilitaryPresenceProvider(world, fixture.topology, fixture.metrics).entryAt(1, target, policy))
        policy.onEntered(world, recorder, 1, target)
        assertEquals(target, world.positionOf(2), "interceptor moves only when the target was actually entered")
    }

    @Test fun `lone direct traveler can be intercepted while assignment travel keeps its prior policy`() {
        val index = fixture.bundle.commanderyIndex
        val (from, target, towerCity) = fixture.topology.traversalEdges.firstNotNullOf { edge ->
            val a = edge.from as? StrategicNodeRef.LandProvince
            val b = edge.to as? StrategicNodeRef.LandProvince
            val aNo = a?.let { index.commanderyOf(it.id) }
            val bNo = b?.let { index.commanderyOf(it.id) }
            val city = if (bNo == null) null else fixture.bundle.projection.bindingsByCityId.entries.firstOrNull {
                it.value.landProvinceId?.let(index::commanderyOf) == bNo
            }?.key
            if (a == null || b == null || aNo == null || bNo == null || aNo == bNo || city == null ||
                fixture.metrics.edgesById[edge.id]!!.costMm > LandMarchMetricSnapshot.NORMAL_BUDGET_MM) null
            else Triple(a, b, city)
        }
        val world = fixture.world(listOf(fixture.person(11, 1, route.startCity) to target,
            fixture.person(12, 2, route.startCity) to from), bugoks = listOf(fixture.unit(812, 12, 100)),
            cityChanges = { city -> city.copy(nationId = if (city.id == towerCity) 2 else 1) })
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, 12, listOf(812), target, "order-12")
        world.setGameEnvValue(HwihaMarchReactions.META_KEY,
            HwihaMarchReactions.of(listOf(HwihaReactionOrder("order-12", 12, 12, 2,
                HwihaPhase(200, 1, 1))), emptyList()).toMetaValue())
        val tower = world.getCityById(towerCity)!!
        world.updateCity(tower.copy(meta = tower.meta + (HwihaMetaVisionSourceReader.COUNTY_WORKS_KEY to
            mapOf("version" to 1, "works" to listOf(mapOf("kind" to "WATCHTOWER_BEACON", "status" to "COMPLETE"))))))
        assertEquals(LandMarchEntry.CLEAR, policy.entryHazard(world, 11, target))
        assertEquals(LandMarchEntry.ENCOUNTER, policy.directEntryHazard(world, 11, target))
        policy.onDirectEntered(world, recorder, 11, target)
        assertEquals(target, world.positionOf(12))
    }

    @Test fun `evasive defender retreats into its own adjacent province and yields passage`() {
        val world = fixture.world(listOf(fixture.person(1, 1, route.startCity) to route.start,
            fixture.person(2, 2, route.destinationCounty) to route.first),
            bugoks = listOf(fixture.unit(7, 1, 1000), fixture.unit(8, 2, 1000)),
            cityChanges = { city -> city.copy(nationId = if (city.id == route.destinationCounty) 2 else 1) })
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, 1, listOf(7), route.destination)
        fixture.deploy(world, recorder, 2, listOf(8), route.start)
        world.setGameEnvValue(HwihaMarchReactions.META_KEY,
            HwihaMarchReactions.of(emptyList(), listOf(HwihaReactionOrder("order-2", 2, 2, 2,
                HwihaPhase(200, 1, 1)))).toMetaValue())
        assertEquals(LandMarchEntry.CLEAR,
            HwihaMilitaryPresenceProvider(world, fixture.topology, fixture.metrics).entryAt(1, route.first, policy))
        recorder.moveGeneral(world, 1, route.first)
        policy.onEntered(world, recorder, 1, route.first)
        assertEquals(route.destination, world.positionOf(2))
        assertEquals(route.first, world.positionOf(1))
    }

    @Test fun `interceptor enters with the marching corps and seals a real encounter`() {
        val world = fixture.world(listOf(fixture.person(1, 1, route.startCity) to route.start,
            fixture.person(2, 2, route.destinationCounty) to route.destination),
            bugoks = listOf(fixture.unit(7, 1, 1000), fixture.unit(8, 2, 1000)),
            cityChanges = { city -> city.copy(nationId = if (city.id == route.destinationCounty) 2 else 1) })
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, 1, listOf(7), route.destination)
        fixture.deploy(world, recorder, 2, listOf(8), route.first)
        val targetCommandery = fixture.bundle.commanderyIndex.commanderyOf(route.first.id)
        val towerCity = fixture.bundle.projection.bindingsByCityId.entries.first { (_, binding) ->
            binding.landProvinceId?.let(fixture.bundle.commanderyIndex::commanderyOf) == targetCommandery
        }.key
        val city = world.getCityById(towerCity)!!
        world.updateCity(city.copy(nationId = 2, meta = city.meta + (HwihaMetaVisionSourceReader.COUNTY_WORKS_KEY to
            mapOf("version" to 1, "works" to listOf(mapOf("kind" to "WATCHTOWER_BEACON", "status" to "COMPLETE"))))))
        world.setGameEnvValue(HwihaMarchReactions.META_KEY,
            HwihaMarchReactions.of(listOf(HwihaReactionOrder("order-2", 2, 2, 2, HwihaPhase(200, 1, 1))),
                emptyList()).toMetaValue())
        fixture.nextPhase(world)
        assertEquals(LandMarchEntry.ENCOUNTER, policy.entryHazard(world, 1, route.first))
        HwihaAssignmentMarchTurn(world, recorder, fixture.topology, fixture.metrics, fixture.cells, reactions = policy)
            .onTurn(1, HwihaCampaignWorldFixture.NO_INPUT)
        assertEquals(route.first, world.positionOf(1))
        assertEquals(route.first, world.positionOf(2))
        assertNotNull(HwihaCorpsEncounter.read(world.getGeneralById(1)!!.meta, fixture.topology))
    }
}

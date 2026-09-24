package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.engine.turn.*
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.input.*
import opensamguk.logic.world.*
import opensamguk.logic.war.hwiha.HwihaS3Provisional

/** One direct assertion for each NPC war rule. These use the real map and in-memory engine services. */
class HwihaNpcWarBranchesTest {
    private val fixture = HwihaCampaignWorldFixture()
    private val route = fixture.route()
    private val selector = HwihaNpcDeploySelector(fixture.topology, fixture.metrics)

    private fun siegeService(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        HwihaSiegeService(world, recorder, fixture.topology, fixture.metrics, fixture.cells)

    @Test fun `convoy dispatch skips an enemy corps province on its route`() {
        val source = route.startCity
        val target = route.destinationCounty
        val world = fixture.world(listOf(
            fixture.person(1, 1, target, userId = null) to route.destination,
            fixture.person(2, 2, target, userId = null) to route.first),
            bugoks = listOf(fixture.unit(7, 1, 100, provisions = 0), fixture.unit(8, 2, 100)),
            cityChanges = { city -> when (city.id) {
                source -> city.copy(nationId = 1, supplyState = 1, meta = city.meta +
                    (HwihaCountyWarehouse.META_KEY to HwihaCountyWarehouse(source, 0,
                        HwihaResources(grain = 1_000_000)).toMetaValue()))
                else -> city.copy(nationId = 2, supplyState = 1)
            } })
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, 1, listOf(7), route.destination)
        fixture.deploy(world, recorder, 2, listOf(8), route.start)
        val rations = HwihaCorpsRations(world, recorder, fixture.topology, fixture.metrics)
        assertEquals(0, rations.dispatch(200, 2))
        assertTrue(rations.convoys().isEmpty())
        assertEquals(1_000_000L, HwihaCountyWarehouse.read(world.getCityById(source)!!.meta, source)!!.stock.grain)
    }

    @Test fun `a besieging NPC lifts for relief and chooses the threatened county on its next turn`() {
        val world = fixture.world(listOf(
            fixture.person(1, 1, route.startCity) to route.first,
            fixture.person(2, 2, route.destinationCounty) to route.start),
            bugoks = listOf(fixture.unit(7, 1, 1000), fixture.unit(8, 2, 500)),
            cityChanges = { city -> city.copy(nationId = if (city.id == route.destinationCounty) 2 else 1) })
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, 1, listOf(7), route.destination)
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(1, HwihaCampaignWorldFixture.NO_INPUT)
        assertEquals(HwihaSiegeService.ACTIVE, world.getHwihaSiege(route.destinationCounty)?.status)
        fixture.deploy(world, recorder, 2, listOf(8), route.start)
        val now = world.getState()
        world.putHwihaSiege(HwihaSiege(route.startCity, HwihaSiegeService.ACTIVE, 2, 2, "order-2", 2, 1,
            route.first.id, now.currentYear, now.currentMonth, now.currentPhase,
            morale = 10_000, garrison = 100))
        assertEquals(route.start, selector.reliefFor(world, 1))
        fixture.nextPhase(world)
        siegeService(world, recorder).npcAct(1)
        assertEquals("RELIEF", world.getHwihaSiege(route.destinationCounty)?.endReason)
        assertNull(HwihaDeploymentState.read(world.getGeneralById(1)!!.meta))
        fixture.nextPhase(world)
        assertEquals(route.start, selector.choose(world, 1)?.destination)
    }

    @Test fun `an NPC expedition that arrived without a siege ends`() {
        val world = fixture.world(listOf(fixture.person(1, 1, route.startCity) to route.start),
            bugoks = listOf(fixture.unit(7, 1, 1000, provisions = 0)),
            cityChanges = { city -> city.copy(nationId = if (city.id == route.destinationCounty) 2 else 1) })
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, 1, listOf(7), route.destination)
        val actor = world.getGeneralById(1)!!
        val path = (StrategicPathResolver.resolveLandMarch(fixture.topology,
            StrategicPathRequest(route.start, route.destination, 1), fixture.passage(), fixture.metrics)
            as LandMarchPathResult.Resolved).path
        val checkpoint = HwihaMarchCheckpoint(path, LandMarchCursor(path.pathHash, path.edgeIds.size, 0),
            world.getState().let { HwihaPhase(it.currentYear, it.currentMonth, it.currentPhase) }, LandMarchStop.ARRIVED)
        val state = HwihaCorpsMarchState("order-1", 1, 1, checkpoint)
        world.applyGeneralDirtyFree(actor.copy(meta = actor.meta + (HwihaCorpsMarchState.META_KEY to state.toMetaValue())))
        recorder.moveGeneral(world, 1, route.destination)
        assertTrue(siegeService(world, recorder).npcEndIfStranded(1))
        assertNull(HwihaDeploymentState.read(world.getGeneralById(1)!!.meta))
    }

    @Test fun `hungry NPC returns to nearest own county or waits when already home`() {
        val world = fixture.world(listOf(fixture.person(1, 1, route.destinationCounty) to route.destination),
            bugoks = listOf(fixture.unit(7, 1, 1000, provisions = 0)),
            cityChanges = { city -> city.copy(nationId = if (city.id == route.startCity) 1 else 2) })
        assertEquals(route.start, selector.choose(world, 1)?.destination)
        ChangeRecorder().moveGeneral(world, 1, route.start)
        assertNull(selector.choose(world, 1), "the unit waits for the monthly refill in its own county")
    }

    @Test fun `capture leaves a proportional garrison while every unit keeps a soldier`() {
        val world = fixture.world(listOf(fixture.person(1, 1, route.startCity) to route.first),
            bugoks = listOf(fixture.unit(7, 1, 500), fixture.unit(8, 1, 300), fixture.unit(9, 1, 1)),
            cityChanges = { city -> if (city.id == route.destinationCounty) city.copy(nationId = 2, defence = 100,
                meta = city.meta + (HwihaCountyWarehouse.META_KEY to HwihaCountyWarehouse(city.id, 0,
                    HwihaResources()).toMetaValue())) else city })
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, 1, listOf(7, 8, 9), route.destination)
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(1, HwihaCampaignWorldFixture.NO_INPUT)
        repeat(4) { fixture.nextPhase(world); HwihaPhaseBoundary(fixture.topology, fixture.metrics, fixture.cells).run(world, recorder) }
        val city = world.getCityById(route.destinationCounty)!!
        val expected = minOf(city.defenceMax * HwihaS3Provisional.CAPTURE_GARRISON_DEFENCE_MAX_PERCENT / 100,
            800 * HwihaS3Provisional.CAPTURE_GARRISON_MAX_CORPS_PERCENT / 100)
        assertEquals(HwihaSiegeService.FALLEN, world.getHwihaSiege(city.id)?.status)
        assertEquals(expected, HwihaCityMilitaryState.read(city.meta, city.defence).troops)
        assertEquals(100, city.defence, "captured troops must not increase the fortification score")
        assertEquals(500 - expected * 500 / 800, world.getBugokById(7)!!.troops)
        assertEquals(300 - expected * 300 / 800, world.getBugokById(8)!!.troops)
        assertEquals(1, world.getBugokById(9)!!.troops)
    }
}

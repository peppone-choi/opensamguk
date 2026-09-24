package opensamguk.engine.hwiha

import opensamguk.logic.war.hwiha.HwihaS3Provisional
import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.input.*

/** Real pinned map, in-memory: an arrived corps besieges an enemy county seat and the phase boundary settles it. */
class HwihaSiegeServiceTest {
    private val fixture = HwihaCampaignWorldFixture()
    private val route = fixture.route()
    private val county = route.destinationCounty

    private fun besieged(troops: Int = 1000, provisions: Int = 100_000, grain: Long = 0, userId: String? = "42",
        trust: Double = 50.0, defence: Int = 100, reverse: Boolean = false,
        defenderCondition: HwihaCityMilitaryState? = null): Pair<InMemoryTurnWorld, ChangeRecorder> {
        val reserveCounty = fixture.bundle.projection.administrativeCountyIds.first { it != county }
        val people = listOf(fixture.person(1, 1, route.startCity, userId = userId) to route.first) +
            if (reverse) listOf(fixture.person(2, 2, route.startCity, userId = "43") to route.first) else emptyList()
        val units = listOf(fixture.unit(7, 1, troops, provisions = provisions)) +
            if (reverse) listOf(fixture.unit(8, 2, 1000)) else emptyList()
        val world = fixture.world(people,
            bugoks = units,
            nations = listOf(opensamguk.engine.turn.Nation(1, "N1", "#111111"),
                opensamguk.engine.turn.Nation(2, "N2", "#222222", capitalCityId = if (reverse) reserveCounty else null, chiefGeneralId = null)),
            cityChanges = { city -> if (reverse && city.id == reserveCounty) city.copy(nationId = 2)
                else if (city.id != county) city else city.copy(nationId = 2, defence = defence,
                meta = city.meta + mapOf("trust" to trust,
                    HwihaCountyWarehouse.META_KEY to HwihaCountyWarehouse(county, 0, HwihaResources(grain = grain)).toMetaValue()) +
                    (defenderCondition?.let { mapOf(HwihaCityMilitaryState.META_KEY to it.toMetaValue()) } ?: emptyMap())) })
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, 1, listOf(7), route.destination)
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(1, HwihaCampaignWorldFixture.NO_INPUT)
        assertEquals(route.destination, world.positionOf(1), "the corps arrived")
        assertEquals(if (defence == 0) HwihaSiegeService.FALLEN else HwihaSiegeService.ACTIVE,
            world.getHwihaSiege(county)?.status, "arrival besieges the county seat")
        return world to recorder
    }

    private val outcomes = HwihaCampaignWorldFixture.RecordingOutcomes()

    private fun boundary(world: InMemoryTurnWorld, recorder: ChangeRecorder, times: Int = 1) = repeat(times) {
        fixture.nextPhase(world)
        HwihaPhaseBoundary(fixture.topology, fixture.metrics, fixture.cells, outcomes = outcomes).run(world, recorder)
    }

    @Test fun `a siege settlement stamp is written all at once`() {
        val (world, recorder) = besieged(grain = 1_000_000)
        val started = world.getHwihaSiege(county)!!
        assertEquals(listOf(null, null, null), listOf(started.settledYear, started.settledMonth, started.settledPhase))
        boundary(world, recorder)
        val settled = world.getHwihaSiege(county)!!
        assertEquals(listOf(world.getState().currentYear, world.getState().currentMonth, world.getState().currentPhase),
            listOf(settled.settledYear, settled.settledMonth, settled.settledPhase))
        val originalTurns = settled.turns
        HwihaPhaseBoundary(fixture.topology, fixture.metrics, fixture.cells).run(world, recorder)
        assertEquals(originalTurns, world.getHwihaSiege(county)!!.turns, "the complete stamp prevents a second settlement")
    }

    @Test fun `a starved county surrenders on the fourth boundary and keeps its warehouse in the county`() {
        val (world, recorder) = besieged(defenderCondition = HwihaCityMilitaryState(100, 0, 100))
        boundary(world, recorder, 3)
        assertEquals(listOf(7500, 5000, 2500), world.getHwihaSiege(county)!!.timeline.filter { it["event"] == "TURN" }.map { it["morale"] })
        assertEquals(2, world.getCityById(county)!!.nationId)
        boundary(world, recorder)
        val siege = world.getHwihaSiege(county)!!
        assertEquals(HwihaSiegeService.FALLEN, siege.status); assertEquals("STARVED", siege.endReason); assertEquals(4, siege.turns)
        val city = world.getCityById(county)!!
        assertEquals(1, city.nationId, "the county transfers to the besieger")
        assertEquals(1100, city.population, "garrison disarmed into civilians")
        // 점령군 수비대: min(방비 상한 × 30%, 포위군 1000 × 20% = 200) 명이 부곡에서 縣 수비로 옮겨 간다.
        val garrison = minOf(city.defenceMax * HwihaS3Provisional.CAPTURE_GARRISON_DEFENCE_MAX_PERCENT / 100,
            1000 * HwihaS3Provisional.CAPTURE_GARRISON_MAX_CORPS_PERCENT / 100)
        assertTrue(garrison > 0)
        assertEquals(garrison, HwihaCityMilitaryState.read(city.meta).troops, "the captor leaves a garrison")
        assertEquals(HwihaCityMilitaryState.INITIAL.copy(troops = garrison), HwihaCityMilitaryState.read(city.meta),
            "the replacement garrison starts with fresh training and morale")
        assertEquals(100, city.defence, "capture leaves the fortification score intact")
        assertEquals(1000 - garrison, world.getBugokById(7)!!.troops, "taken from the besieging unit")
        assertEquals(HwihaResources(), HwihaCountyWarehouse.read(city.meta, county)!!.stock, "warehouse stays in the county")
        assertNull(HwihaDeploymentState.read(world.getGeneralById(1)!!.meta), "the expedition ends at its objective")
        assertEquals(listOf(listOf<Any>(county, 2, 1, listOf(1))), outcomes.captures, "capture reported exactly once")
        boundary(world, recorder)
        assertEquals(4, world.getHwihaSiege(county)!!.turns, "a fallen siege is not settled again")
        assertEquals(1, outcomes.captures.size)
    }

    @Test fun `corrupt city military state lifts a siege without stopping the phase`() {
        val (world, recorder) = besieged()
        val city = world.getCityById(county)!!
        world.applyCityDirtyFree(city.copy(meta = city.meta +
            (HwihaCityMilitaryState.META_KEY to mapOf("version" to 2, "troops" to "bad"))))
        boundary(world, recorder)
        assertEquals("STATE_UNAVAILABLE", world.getHwihaSiege(county)?.endReason)
    }

    @Test fun `a fed garrison eats from its warehouse through the settlement boundary`() {
        val (world, recorder) = besieged(grain = 1_000_000)
        boundary(world, recorder)
        val warehouse = HwihaCountyWarehouse.read(world.getCityById(county)!!.meta, county)!!
        assertEquals(990_000L, warehouse.stock.grain); assertEquals(1, warehouse.revision)
        assertEquals(10_000, world.getHwihaSiege(county)!!.morale)
        // Settling the same phase twice must not eat twice.
        HwihaPhaseBoundary(fixture.topology, fixture.metrics, fixture.cells).run(world, recorder)
        assertEquals(990_000L, HwihaCountyWarehouse.read(world.getCityById(county)!!.meta, county)!!.stock.grain)
    }

    @Test fun `too few besiegers lift the siege and no rations end the expedition`() {
        val (thin, thinRecorder) = besieged()
        thin.updateBugok(thin.getBugokById(7)!!.copy(troops = 150))
        boundary(thin, thinRecorder)
        assertEquals("INSUFFICIENT_RATIO", thin.getHwihaSiege(county)!!.endReason)
        assertNotNull(HwihaDeploymentState.read(thin.getGeneralById(1)!!.meta), "a thin corps keeps its deployment")
        val (hungry, hungryRecorder) = besieged()
        hungry.updateBugok(hungry.getBugokById(7)!!.copy(provisions = 10))
        boundary(hungry, hungryRecorder)
        assertEquals("BESIEGER_UNFED", hungry.getHwihaSiege(county)!!.endReason)
        assertNull(HwihaDeploymentState.read(hungry.getGeneralById(1)!!.meta), "an unfed expedition ends")
        assertEquals(1000, hungry.getBugokById(7)!!.troops, "troops are preserved")
    }

    @Test fun `a siege that could not hold is not started at all`() {
        for ((troops, provisions) in listOf(150 to 100_000, 1000 to 10)) {
            val world = fixture.world(listOf(fixture.person(1, 1, route.startCity, userId = "42") to route.first),
                bugoks = listOf(fixture.unit(7, 1, troops, provisions = provisions)),
                cityChanges = { city -> if (city.id != county) city else city.copy(nationId = 2) })
            val recorder = ChangeRecorder()
            fixture.deploy(world, recorder, 1, listOf(7), route.destination)
            fixture.nextPhase(world)
            fixture.movement(world, recorder).onTurn(1, HwihaCampaignWorldFixture.NO_INPUT)
            assertEquals(route.destination, world.positionOf(1))
            assertNull(world.getHwihaSiege(county), "troops=$troops provisions=$provisions")
        }
    }

    @Test fun `assault through the grid takes the county and surrender demand needs low morale and trust`() {
        val (world, recorder) = besieged(troops = 5000)
        fixture.nextPhase(world)
        val handler = HwihaSiegeHandler(world, recorder, fixture.topology, fixture.metrics, fixture.cells)
        // 강공 준비: 포위가 순 경계를 3번 버티기 전에는 강공할 수 없다.
        assertEquals("ASSAULT_NOT_READY", assertIs<HwihaTurnOutcome.Rejected>(handler.handle(HwihaSiegeHandler.ASSAULT, 1, "{}")).code)
        assertEquals(2, world.getCityById(county)!!.nationId)
        boundary(world, recorder, HwihaS3Provisional.ASSAULT_MIN_SIEGE_TURNS)
        assertIs<HwihaTurnOutcome.Applied>(handler.handle(HwihaSiegeHandler.ASSAULT, 1, "{}"))
        assertEquals(1, world.getCityById(county)!!.nationId)
        assertEquals("ASSAULT", world.getHwihaSiege(county)!!.endReason)
        assertTrue(world.getBugokById(7)!!.troops < 5000)

        val (demand, demandRecorder) = besieged(trust = 40.0)
        val demandHandler = HwihaSiegeHandler(demand, demandRecorder, fixture.topology, fixture.metrics, fixture.cells)
        assertEquals("REFUSED", assertIs<HwihaTurnOutcome.Rejected>(demandHandler.handle(HwihaSiegeHandler.DEMAND_SURRENDER, 1, "{}")).code)
        boundary(demand, demandRecorder, 3)
        assertIs<HwihaTurnOutcome.Applied>(demandHandler.handle(HwihaSiegeHandler.DEMAND_SURRENDER, 1, "{}"))
        assertEquals("SURRENDER_DEMAND", demand.getHwihaSiege(county)!!.endReason)
        assertEquals(1, demand.getCityById(county)!!.nationId)
        assertEquals("NOT_BESIEGING", assertIs<HwihaTurnOutcome.Rejected>(demandHandler.handle(HwihaSiegeHandler.ASSAULT, 1, null)).code)
    }

    @Test fun `an npc commander assaults on its own turn when it outnumbers the garrison three to one once the siege is ready`() {
        // 민심이 높아 항복 권고는 통하지 않는다 — 강공 길만 본다.
        val (world, recorder) = besieged(troops = 5000, userId = null, trust = 80.0)
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(1, HwihaCampaignWorldFixture.NO_INPUT)
        assertEquals(2, world.getCityById(county)!!.nationId, "no assault before the siege has held three boundaries")
        boundary(world, recorder, HwihaS3Provisional.ASSAULT_MIN_SIEGE_TURNS)
        assertEquals(2, world.getCityById(county)!!.nationId)
        fixture.movement(world, recorder).onTurn(1, HwihaCampaignWorldFixture.NO_INPUT)
        assertEquals(1, world.getCityById(county)!!.nationId)
        assertEquals("ASSAULT", world.getHwihaSiege(county)!!.endReason)
    }

    @Test fun `an undefended county falls the moment it is besieged`() {
        val (world, _) = besieged(defence = 0)
        assertEquals(1, world.getCityById(county)!!.nationId)
        assertEquals("UNDEFENDED", world.getHwihaSiege(county)!!.endReason)
    }

    @Test fun `a counter siege after capture faces the occupation garrison instead of retaking at arrival`() {
        val (world, recorder) = besieged(reverse = true)
        boundary(world, recorder, 4)
        assertEquals(1, world.getCityById(county)!!.nationId)
        assertTrue(HwihaCityMilitaryState.read(world.getCityById(county)!!.meta).troops > 0)
        recorder.moveGeneral(world, 1, route.first)
        fixture.deploy(world, recorder, 2, listOf(8), route.destination)
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(2, HwihaCampaignWorldFixture.NO_INPUT)
        assertEquals(route.destination, world.positionOf(2), "counter corps reached the captured county")
        assertEquals(1, world.getCityById(county)!!.nationId, "arrival does not auto recapture a defended county")
        assertEquals(HwihaSiegeService.ACTIVE, world.getHwihaSiege(county)?.status,
            world.listHwihaSieges().map { "${it.countyId}:${it.status}:${it.endReason}" }.joinToString())
    }

    @Test fun `red probe removing the occupation garrison restores immediate recapture`() {
        val (world, recorder) = besieged(reverse = true)
        boundary(world, recorder, 4)
        val occupied = world.getCityById(county)!!
        world.updateCity(occupied.copy(meta = occupied.meta +
            (HwihaCityMilitaryState.META_KEY to HwihaCityMilitaryState.read(occupied.meta).copy(troops = 0).toMetaValue())))
        recorder.moveGeneral(world, 1, route.first)
        fixture.deploy(world, recorder, 2, listOf(8), route.destination)
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(2, HwihaCampaignWorldFixture.NO_INPUT)
        assertEquals(2, world.getCityById(county)!!.nationId)
        assertEquals("UNDEFENDED", world.getHwihaSiege(county)?.endReason)
    }
}

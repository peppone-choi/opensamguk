package opensamguk.engine.campaign

import opensamguk.logic.war.CampaignBalance
import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.*
import opensamguk.logic.world.ProvinceCell
import opensamguk.logic.world.ProvinceCellIndex

/** Real pinned map, in-memory: an arrived corps besieges an enemy county seat and the phase boundary settles it. */
class SiegeServiceTest {
    private val fixture = CampaignWorldFixture()
    private val route = fixture.route()
    private val county = route.destinationCounty

    private fun besieged(troops: Int = 1000, provisions: Int = 100_000, grain: Long = 0, userId: String? = "42",
        trust: Double = 50.0, defence: Int = 100, reverse: Boolean = false,
        defenderCondition: CityMilitaryState? = null): Pair<InMemoryTurnWorld, ChangeRecorder> {
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
                    CountyWarehouse.META_KEY to CountyWarehouse(county, 0, Resources(grain = grain)).toMetaValue()) +
                    (defenderCondition?.let { mapOf(CityMilitaryState.META_KEY to it.toMetaValue()) } ?: emptyMap())) })
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, 1, listOf(7), route.destination)
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(1, CampaignWorldFixture.NO_INPUT)
        assertEquals(route.destination, world.positionOf(1), "the corps arrived")
        assertEquals(if (defence == 0) SiegeService.FALLEN else SiegeService.ACTIVE,
            world.getSiege(county)?.status, "arrival besieges the county seat")
        return world to recorder
    }

    private val outcomes = CampaignWorldFixture.RecordingOutcomes()

    private fun boundary(world: InMemoryTurnWorld, recorder: ChangeRecorder, times: Int = 1) = repeat(times) {
        fixture.nextPhase(world)
        PhaseBoundary(fixture.topology, fixture.metrics, fixture.cells, outcomes = outcomes).run(world, recorder)
    }

    @Test fun `a siege settlement stamp is written all at once`() {
        val (world, recorder) = besieged(grain = 1_000_000)
        val started = world.getSiege(county)!!
        assertEquals(listOf(null, null, null), listOf(started.settledYear, started.settledMonth, started.settledPhase))
        boundary(world, recorder)
        val settled = world.getSiege(county)!!
        assertEquals(listOf(world.getState().currentYear, world.getState().currentMonth, world.getState().currentPhase),
            listOf(settled.settledYear, settled.settledMonth, settled.settledPhase))
        val originalTurns = settled.turns
        PhaseBoundary(fixture.topology, fixture.metrics, fixture.cells).run(world, recorder)
        assertEquals(originalTurns, world.getSiege(county)!!.turns, "the complete stamp prevents a second settlement")
    }

    @Test fun `a starved county surrenders on the fourth boundary and keeps its warehouse in the county`() {
        val (world, recorder) = besieged(defenderCondition = CityMilitaryState(100, 0, 100))
        boundary(world, recorder, 3)
        assertEquals(listOf(7500, 5000, 2500), world.getSiege(county)!!.timeline.filter { it["event"] == "TURN" }.map { it["morale"] })
        assertEquals(2, world.getCityById(county)!!.nationId)
        boundary(world, recorder)
        val siege = world.getSiege(county)!!
        assertEquals(SiegeService.FALLEN, siege.status); assertEquals("STARVED", siege.endReason); assertEquals(4, siege.turns)
        val city = world.getCityById(county)!!
        assertEquals(1, city.nationId, "the county transfers to the besieger")
        assertEquals(1100, city.population, "garrison disarmed into civilians")
        // 점령군 수비대: min(방비 상한 × 30%, 포위군 1000 × 20% = 200) 명이 부곡에서 縣 수비로 옮겨 간다.
        val garrison = minOf(city.defenceMax * CampaignBalance.CAPTURE_GARRISON_DEFENCE_MAX_PERCENT / 100,
            1000 * CampaignBalance.CAPTURE_GARRISON_MAX_CORPS_PERCENT / 100)
        assertTrue(garrison > 0)
        assertEquals(garrison, CityMilitaryState.read(city.meta).troops, "the captor leaves a garrison")
        assertEquals(CityMilitaryState.INITIAL.copy(troops = garrison), CityMilitaryState.read(city.meta),
            "the replacement garrison starts with fresh training and morale")
        assertEquals(100, city.defence, "capture leaves the fortification score intact")
        assertEquals(1000 - garrison, world.getBugokById(7)!!.troops, "taken from the besieging unit")
        assertEquals(Resources(), CountyWarehouse.read(city.meta, county)!!.stock, "warehouse stays in the county")
        assertNull(DeploymentState.read(world.getGeneralById(1)!!.meta), "the expedition ends at its objective")
        assertEquals(listOf(listOf<Any>(county, 2, 1, listOf(1))), outcomes.captures, "capture reported exactly once")
        boundary(world, recorder)
        assertEquals(4, world.getSiege(county)!!.turns, "a fallen siege is not settled again")
        assertEquals(1, outcomes.captures.size)
    }

    @Test fun `corrupt city military state lifts a siege without stopping the phase`() {
        val (world, recorder) = besieged()
        val city = world.getCityById(county)!!
        world.applyCityDirtyFree(city.copy(meta = city.meta +
            (CityMilitaryState.META_KEY to mapOf("version" to 2, "troops" to "bad"))))
        boundary(world, recorder)
        assertEquals("STATE_UNAVAILABLE", world.getSiege(county)?.endReason)
    }

    @Test fun `a fed garrison eats from its warehouse through the settlement boundary`() {
        val (world, recorder) = besieged(grain = 1_000_000)
        boundary(world, recorder)
        val warehouse = CountyWarehouse.read(world.getCityById(county)!!.meta, county)!!
        assertEquals(990_000L, warehouse.stock.grain); assertEquals(1, warehouse.revision)
        assertEquals(10_000, world.getSiege(county)!!.morale)
        // Settling the same phase twice must not eat twice.
        PhaseBoundary(fixture.topology, fixture.metrics, fixture.cells).run(world, recorder)
        assertEquals(990_000L, CountyWarehouse.read(world.getCityById(county)!!.meta, county)!!.stock.grain)
    }

    @Test fun `too few besiegers lift the siege and no rations end the expedition`() {
        val (thin, thinRecorder) = besieged()
        thin.updateBugok(thin.getBugokById(7)!!.copy(troops = 150))
        boundary(thin, thinRecorder)
        assertEquals("INSUFFICIENT_RATIO", thin.getSiege(county)!!.endReason)
        assertNotNull(DeploymentState.read(thin.getGeneralById(1)!!.meta), "a thin corps keeps its deployment")
        val (hungry, hungryRecorder) = besieged()
        hungry.updateBugok(hungry.getBugokById(7)!!.copy(provisions = 10))
        boundary(hungry, hungryRecorder)
        assertEquals("BESIEGER_UNFED", hungry.getSiege(county)!!.endReason)
        assertNull(DeploymentState.read(hungry.getGeneralById(1)!!.meta), "an unfed expedition ends")
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
            fixture.movement(world, recorder).onTurn(1, CampaignWorldFixture.NO_INPUT)
            assertEquals(route.destination, world.positionOf(1))
            assertNull(world.getSiege(county), "troops=$troops provisions=$provisions")
        }
    }

    @Test fun `assault through the grid takes the county and surrender demand needs low morale and trust`() {
        val (world, recorder) = besieged(troops = 5000)
        fixture.nextPhase(world)
        val handler = SiegeHandler(world, recorder, fixture.topology, fixture.metrics, fixture.cells)
        val selected = """{"targetCountyId":$county}"""
        assertEquals("INVALID_INPUT", assertIs<TurnOutcome.Rejected>(handler.handle(SiegeHandler.ASSAULT, 1, "{}", 42)).code)
        assertEquals("FORBIDDEN", assertIs<TurnOutcome.Rejected>(handler.handle(SiegeHandler.ASSAULT, 1, selected, 43)).code)
        assertEquals("TARGET_CHANGED", assertIs<TurnOutcome.Rejected>(handler.handle(SiegeHandler.ASSAULT, 1,
            """{"targetCountyId":${county + 1}}""", 42)).code)
        // 강공 준비: 포위가 순 경계를 3번 버티기 전에는 강공할 수 없다.
        assertEquals("ASSAULT_NOT_READY", assertIs<TurnOutcome.Rejected>(handler.handle(SiegeHandler.ASSAULT, 1, selected, 42)).code)
        assertEquals(2, world.getCityById(county)!!.nationId)
        boundary(world, recorder, CampaignBalance.ASSAULT_MIN_SIEGE_TURNS)
        val liveSiege = world.getSiege(county)!!
        world.putSiege(liveSiege.copy(besiegerOwnerGeneralId = 999))
        assertEquals("STATE_UNAVAILABLE", assertIs<TurnOutcome.Rejected>(handler.handle(SiegeHandler.ASSAULT,
            1, selected, 42)).code, "a queued assault cannot use a replaced corps owner")
        world.putSiege(liveSiege)
        val liveCity = world.getCityById(county)!!
        world.applyCityDirtyFree(liveCity.copy(nationId = 1))
        assertEquals("TARGET_CHANGED", assertIs<TurnOutcome.Rejected>(handler.handle(SiegeHandler.ASSAULT,
            1, selected, 42)).code, "a queued assault cannot attack a county that changed hands")
        world.applyCityDirtyFree(liveCity)
        assertIs<TurnOutcome.Applied>(handler.handle(SiegeHandler.ASSAULT, 1, selected, 42))
        assertEquals(1, world.getCityById(county)!!.nationId)
        assertEquals("ASSAULT", world.getSiege(county)!!.endReason)
        assertTrue(world.getBugokById(7)!!.troops < 5000)

        val (demand, demandRecorder) = besieged(trust = 40.0)
        val demandHandler = SiegeHandler(demand, demandRecorder, fixture.topology, fixture.metrics, fixture.cells)
        assertEquals("REFUSED", assertIs<TurnOutcome.Rejected>(demandHandler.handle(SiegeHandler.DEMAND_SURRENDER, 1, "{}")).code)
        boundary(demand, demandRecorder, 3)
        assertIs<TurnOutcome.Applied>(demandHandler.handle(SiegeHandler.DEMAND_SURRENDER, 1, "{}"))
        assertEquals("SURRENDER_DEMAND", demand.getSiege(county)!!.endReason)
        assertEquals(1, demand.getCityById(county)!!.nationId)
        assertEquals("NOT_BESIEGING", assertIs<TurnOutcome.Rejected>(demandHandler.handle(SiegeHandler.ASSAULT, 1,
            selected, 42)).code)
    }

    @Test fun `an npc commander assaults on its own turn when it outnumbers the garrison three to one once the siege is ready`() {
        // 민심이 높아 항복 권고는 통하지 않는다 — 강공 길만 본다.
        val (world, recorder) = besieged(troops = 5000, userId = null, trust = 80.0)
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(1, CampaignWorldFixture.NO_INPUT)
        assertEquals(2, world.getCityById(county)!!.nationId, "no assault before the siege has held three boundaries")
        boundary(world, recorder, CampaignBalance.ASSAULT_MIN_SIEGE_TURNS)
        assertEquals(2, world.getCityById(county)!!.nationId)
        fixture.movement(world, recorder).onTurn(1, CampaignWorldFixture.NO_INPUT)
        assertEquals(1, world.getCityById(county)!!.nationId)
        assertEquals("ASSAULT", world.getSiege(county)!!.endReason)
    }

    @Test fun `queued assault rechecks impossible approach without mutating siege county or units`() {
        val (world, recorder) = besieged(troops = 3000, grain = 1_000_000,
            defenderCondition = CityMilitaryState(training = 50, morale = 100, troops = 900))
        val siege = world.getSiege(county)!!
        val synthetic = ProvinceCellIndex(fixture.topology.topologyRevision, fixture.topology.contentHash,
            fixture.cells.tilesContentHash, 49, 1, mapOf('1' to "PLAIN"), mapOf(
                siege.approachProvinceId to listOf(ProvinceCell(0, 0, '1')),
                route.destination.id to (1..48).map { ProvinceCell(it, 0, '1') }))
        val handler = SiegeHandler(world, recorder, fixture.topology, fixture.metrics, synthetic)
        val selected = """{"targetCountyId":$county}"""
        for (turns in 0..2) {
            assertEquals(turns, world.getSiege(county)!!.turns)
            assertEquals("ASSAULT_NOT_READY",
                assertIs<TurnOutcome.Rejected>(handler.handle(SiegeHandler.ASSAULT, 1, selected, 42)).code)
            boundary(world, recorder)
        }
        val beforeSiege = world.getSiege(county)
        val beforeCity = world.getCityById(county)
        val beforeUnit = world.getBugokById(7)
        assertEquals("FORBIDDEN",
            assertIs<TurnOutcome.Rejected>(handler.handle(SiegeHandler.ASSAULT, 1, selected, 43)).code)
        repeat(2) {
            assertEquals("ASSAULT_APPROACH_UNREACHABLE",
                assertIs<TurnOutcome.Rejected>(handler.handle(SiegeHandler.ASSAULT, 1, selected, 42)).code)
            assertEquals(beforeSiege, world.getSiege(county))
            assertEquals(beforeCity, world.getCityById(county))
            assertEquals(beforeUnit, world.getBugokById(7))
        }
        // NPC uses the identical service guard without invoking the legacy resolver or recording REPULSED.
        assertEquals(SiegeService.Failure.ASSAULT_APPROACH_UNREACHABLE,
            SiegeService(world, recorder, fixture.topology, fixture.metrics, synthetic).assault(1, county))
        assertEquals(beforeSiege, world.getSiege(county))
        val reserved = opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn(
            SiegeHandler.ASSAULT, selected, requestId = "synthetic-impossible-assault", reservationOwnerUserId = 42)
        val turnHandler = opensamguk.engine.turn.ReservedTurnHandler(world,
            opensamguk.logic.actions.CommandRegistry(opensamguk.logic.stats.GeneralActionPipeline()),
            "synthetic-siege-seed", 200, recorder = recorder,
            deploymentContext = fixture.topology to fixture.metrics, provinceCells = synthetic)
        val now = world.getState()
        val handled = turnHandler.handle(1, reserved, now.currentYear, now.currentMonth, "00:00")
        assertEquals("synthetic-impossible-assault", handled.requestId)
        assertEquals(SiegeHandler.ASSAULT, handled.reservedActionCode)
        assertEquals("ASSAULT_APPROACH_UNREACHABLE", assertIs<TurnOutcome.Rejected>(handled.inputOutcome).code)
        assertFalse(handled.fellBack)
        assertEquals(beforeSiege, world.getSiege(county))
        assertEquals(beforeCity, world.getCityById(county))
        assertEquals(beforeUnit, world.getBugokById(7))
    }

    @Test fun `an undefended county falls the moment it is besieged`() {
        val (world, _) = besieged(defence = 0)
        assertEquals(1, world.getCityById(county)!!.nationId)
        assertEquals("UNDEFENDED", world.getSiege(county)!!.endReason)
    }

    @Test fun `a counter siege after capture faces the occupation garrison instead of retaking at arrival`() {
        val (world, recorder) = besieged(reverse = true)
        boundary(world, recorder, 4)
        assertEquals(1, world.getCityById(county)!!.nationId)
        assertTrue(CityMilitaryState.read(world.getCityById(county)!!.meta).troops > 0)
        recorder.moveGeneral(world, 1, route.first)
        fixture.deploy(world, recorder, 2, listOf(8), route.destination)
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(2, CampaignWorldFixture.NO_INPUT)
        assertEquals(route.destination, world.positionOf(2), "counter corps reached the captured county")
        assertEquals(1, world.getCityById(county)!!.nationId, "arrival does not auto recapture a defended county")
        assertEquals(SiegeService.ACTIVE, world.getSiege(county)?.status,
            world.listSieges().map { "${it.countyId}:${it.status}:${it.endReason}" }.joinToString())
    }

    @Test fun `red probe removing the occupation garrison restores immediate recapture`() {
        val (world, recorder) = besieged(reverse = true)
        boundary(world, recorder, 4)
        val occupied = world.getCityById(county)!!
        world.updateCity(occupied.copy(meta = occupied.meta +
            (CityMilitaryState.META_KEY to CityMilitaryState.read(occupied.meta).copy(troops = 0).toMetaValue())))
        recorder.moveGeneral(world, 1, route.first)
        fixture.deploy(world, recorder, 2, listOf(8), route.destination)
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(2, CampaignWorldFixture.NO_INPUT)
        assertEquals(2, world.getCityById(county)!!.nationId)
        assertEquals("UNDEFENDED", world.getSiege(county)?.endReason)
    }
}

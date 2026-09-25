package opensamguk.engine.invariance

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.engine.hwiha.*
import opensamguk.infra.seed.HanWorldArtifactsResolver
import opensamguk.infra.seed.ScenarioJson
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.input.*
import opensamguk.logic.util.phpRound
import opensamguk.logic.world.*

/**
 * NPC-only smoke run of the 豫州 slice scenario through the production personal-turn lifecycle and the phase
 * boundary, in memory (no database, no flush). It proves the NPC links close on the real map: 출병 → 행군 →
 * (조우) → 공성 → 점령, deterministically. The database-backed chain with 출사·발령·징세·월단평 is
 * `PassChainInvarianceIT`.
 */
class YuzhouCampaignInvarianceTest {
    private val repo: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isDirectory(it.resolve("data/map")) }
    private val mapCities = ScenarioJson.loadMapCities(Files.readString(repo.resolve("infra/src/main/resources/map/han-world-v3.json")))
    private val bundle = HanWorldArtifactsResolver(repo).resolve(mapCities.map { it.id }, emptyList())
    private val topology = bundle.projection.topology
    private val metrics = bundle.landMarchMetrics
    private val cells = bundle.provinceCells

    private class Campaign(val world: InMemoryTurnWorld, val lifecycle: TurnDaemonLifecycle, val boundary: PhaseBoundary,
        val recorder: ChangeRecorder, val outcomes: CampaignWorldFixture.RecordingOutcomes)

    private fun campaign(npcDeploy: Boolean = true, seed: String = "00"): Campaign {
        val scenario = ScenarioJson.loadScenario(Files.readString(repo.resolve("tools/e2e/fixtures/hwiha-yuzhou/scenario_990002.json")))
        val owner = scenario.nations.flatMap { n -> n.cities.map { it.toInt() to n.id } }.toMap()
        val warehouses = requireNotNull(scenario.hwihaWarehouses).warehouses
        val provinceOf = bundle.projection.bindingsByCityId.mapNotNull { (id, b) -> b.landProvinceId?.let { id to it } }.toMap()
        // Same initial stats the importer writes: occupied → 70% of max, neutral → the map's initial values.
        val cities = mapCities.map { c ->
            val occupied = owner[c.id] != null
            fun stat(max: Int, init: Int?) = if (occupied) phpRound(max * 0.7) else (init ?: phpRound(max * 0.7))
            City(c.id, c.name, owner[c.id] ?: 0, c.level, population = stat(c.popMax, c.popInit), populationMax = c.popMax,
                agriculture = stat(c.agriMax, c.agriInit), agricultureMax = c.agriMax, commerce = stat(c.commMax, c.commInit),
                commerceMax = c.commMax, security = stat(c.secuMax, c.secuInit), securityMax = c.secuMax,
                defence = stat(c.defMax, c.defInit), defenceMax = c.defMax, wall = stat(c.wallMax, c.wallInit), wallMax = c.wallMax,
                supplyState = 1, meta = mapOf("trust" to if (occupied) 80.0 else 50.0) + (warehouses[c.id]?.let {
                    mapOf(CountyWarehouse.META_KEY to CountyWarehouse(c.id, 0, it).toMetaValue()) } ?: emptyMap()))
        }
        val lords = scenario.generals.filter { it.hwihaLord == true }
        val generals = lords.mapIndexed { index, g ->
            TurnGeneral(id = index + 1, name = g.name, nationId = g.nationId, cityId = g.locatedCity!!.toInt(), troopId = 0,
                stats = GeneralStats(g.leadership, g.strength, g.intel, g.politics, g.charm), experience = 0, dedication = 0,
                officerLevel = 12, npcState = 2, turnTime = Instant.parse("0190-01-01T00:00:00Z").plusSeconds(index.toLong()),
                meta = mapOf(LordStatus.META_KEY to true, PersonPolicyState.META_KEY to g.hwihaPersonPolicy!!.toMetaValue()))
        }
        val units = scenario.hwihaUnits.mapIndexed { index, u ->
            Bugok(index + 1, lords.indexOfFirst { it.name == u.general } + 1, u.name, u.troops, u.crewTypeId, u.training, u.morale,
                provisions = u.provisions)
        }
        val positions = generals.fold(GeneralPositionSnapshot(topology.topologyRevision, topology.contentHash, topology.landProvinceIds,
            topology.waterZones.map { it.id }.toSet())) { s, g ->
            s.withState(GeneralPositionState(topology.topologyRevision, topology.contentHash, g.id,
                StrategicNodeRef.LandProvince(provinceOf.getValue(g.cityId)), 1))
        }
        val state = TurnWorldState(1, 190, 1, 3600, Instant.parse("0190-01-01T00:00:00Z"), currentPhase = 1,
            config = mapOf("ruleProfile" to "HWIHA", "mapName" to "han-world-v3"), hanWorldVariant = bundle.variant,
            meta = mapOf(LandPassageState.META_KEY to LandPassageState.initialMetaValue(topology),
                MarchReactions.META_KEY to MarchReactions.Empty.toMetaValue(), "startYear" to 190))
        val world = InMemoryTurnWorld(WorldSnapshot(worldId = WorldId(1), state = state, generals = generals, cities = cities,
            nations = scenario.nations.map { Nation(it.id, it.name, it.color, capitalCityId = it.cities.first().toInt(), level = it.scale,
                chiefGeneralId = lords.indexOfFirst { l -> l.nationId == it.id } + 1) },
            bugoks = units, diplomacy = scenario.diplomacy.map { TurnDiplomacy(it.me, it.you, it.state, it.remainMonths) },
            generalPositionSnapshot = positions, cityLandProvinceById = provinceOf,
            administrativeCountyIds = bundle.projection.administrativeCountyIds))
        val recorder = ChangeRecorder()
        val outcomes = CampaignWorldFixture.RecordingOutcomes()
        val handler = ReservedTurnHandler(world, opensamguk.logic.actions.CommandRegistry(opensamguk.logic.stats.GeneralActionPipeline()),
            seed, 190, recorder = recorder, hwihaDeploymentContext = topology to metrics, hwihaProvinceCells = cells,
            hwihaWarOutcomes = outcomes)
        val selector = NpcDeploySelector(topology, metrics)
        val lifecycle = TurnDaemonLifecycle(world, handler,
            hwihaMovementOf = AssignmentMarchTurn(world, recorder, topology, metrics, cells, outcomes,
                reactions = MarchReactionInterpreter(topology, metrics, bundle.commanderyIndex))::onTurn,
            hwihaNpcInputOf = if (npcDeploy) { id, reserved -> selector.select(world, id, reserved) } else { _, reserved -> reserved },
            reservedActionOf = { CampaignWorldFixture.NO_INPUT })
        return Campaign(world, lifecycle, PhaseBoundary(topology, metrics, cells, outcomes = outcomes), recorder, outcomes)
    }

    /** One phase: every general's personal turn, then the world boundary into the next phase. */
    private fun Campaign.phase(index: Int) {
        lifecycle.runTick(Instant.parse("0190-01-01T00:00:00Z").plusSeconds(3600L * (index + 1)))
        val next = world.getState().let { Phase(it.currentYear, it.currentMonth, it.currentPhase) }.plus(1)
        world.setCurrentDate(next.year, next.month, next.phase)
        boundary.run(world, recorder)
    }

    @Test fun `npc lords of the 豫州 slice deploy, march, meet in battle, besiege and take counties on their own`() {
        val run = campaign()
        val owners = run.world.listCities().associate { it.id to it.nationId }
        repeat(36) { run.phase(it) }
        val deployed = run.world.listGenerals().count { DeploymentState.META_KEY in it.meta } +
            run.world.listHwihaSieges().size
        assertTrue(deployed > 0, "NPC lords deployed")
        assertTrue(run.world.listHwihaSieges().isNotEmpty(), "an arrived corps besieged a county")
        assertTrue(run.outcomes.encounters.isNotEmpty(), "a relief corps met a besieging corps and the battle resolved")
        assertTrue(run.world.listGenerals().any { EncounterResolver.BATTLE_RECORD_KEY in it.meta }, "battle record kept")
        val captured = run.world.listHwihaSieges().filter { it.status == SiegeService.FALLEN }
        assertTrue(captured.isNotEmpty(), "a county fell: ${run.world.listHwihaSieges().map { it.countyId to it.status }}")
        // A county can change hands more than once; the row keeps its latest siege, so its besieger holds it now.
        assertTrue(captured.all { run.world.getCityById(it.countyId)!!.nationId == it.besiegerNationId },
            captured.joinToString { "${it.countyId}: was ${owners[it.countyId]} now ${run.world.getCityById(it.countyId)!!.nationId} by ${it.besiegerNationId} ${it.endReason}" })
        assertTrue(run.outcomes.captures.size >= captured.size, "every capture is reported, recaptures included")
        assertTrue(captured.any { owners[it.countyId] != it.besiegerNationId }, "the map changed hands")
        val durations = captured.map { it.turns }.sorted()
        println("yuzhou-simulation encounters=${run.outcomes.encounters.size} sieges=${run.world.listHwihaSieges().size} " +
            "fallen=${captured.size} battles=${run.world.listGenerals().count { EncounterResolver.BATTLE_RECORD_KEY in it.meta }} " +
            "fallTurns=$durations target12to24=${durations.count { it in 12..24 }}/${durations.size}")
        WorldStateBaseline.assertMatches("yuzhou-36-seed-00", run.world)
    }

    @Test fun `seed 01 replay is stable and currently shares seed 00 final state`() {
        fun run() = campaign(seed = "01").also { campaign -> repeat(36) { campaign.phase(it) } }.world
        val first = run()
        assertEquals(WorldStateBaseline.sha256(first), WorldStateBaseline.sha256(run()))
        WorldStateBaseline.assertMatches("yuzhou-36-seed-01", first)
    }

    @Test fun `the same seed has an identical outcome at each of 36 phases`() {
        fun trace(): List<Any> {
            val run = campaign()
            return (0 until 36).map { phase ->
                run.phase(phase)
                listOf(
                    run.world.listCities().sortedBy { it.id }.map { it.id to it.nationId },
                    run.world.listBugoks().sortedBy { it.id }.map { listOf(it.id, it.troops, it.provisions) },
                    run.world.listHwihaSieges().sortedBy { it.countyId }.map { listOf(it.countyId, it.status, it.turns, it.timeline) },
                    run.outcomes.encounters.toList(), run.outcomes.captures.toList(),
                )
            }
        }
        assertEquals(trace(), trace(), "the 36 phase result must not depend on process state or iteration order")
    }

    @Test fun `cutting npc deployment leaves the slice without sieges or captures`() {
        val run = campaign(npcDeploy = false)
        repeat(12) { run.phase(it) }
        assertTrue(run.world.listHwihaSieges().isEmpty())
        assertTrue(run.outcomes.captures.isEmpty() && run.outcomes.encounters.isEmpty())
    }
}

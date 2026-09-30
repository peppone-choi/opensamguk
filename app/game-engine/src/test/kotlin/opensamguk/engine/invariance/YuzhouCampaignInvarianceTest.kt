package opensamguk.engine.invariance

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.engine.campaign.*
import opensamguk.infra.seed.WorldArtifactsResolver
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.infra.seed.ScenarioJson
import opensamguk.infra.seed.UnitProfilesJson
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.input.*
import opensamguk.logic.war.*
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
    private val bundle = WorldArtifactsResolver(repo).resolve(mapCities.map { it.id }, emptyList())
    // The 1428 roster has one release, built on the Map4 grid, so the Map4 bundle is the default one.
    private val map4Bundle get() = bundle

    private class Campaign(val world: InMemoryTurnWorld, val lifecycle: TurnDaemonLifecycle, val boundary: PhaseBoundary,
        val recorder: ChangeRecorder, val outcomes: CampaignWorldFixture.RecordingOutcomes,
        val observations: List<BattleOutcomeObservation>)

    private fun campaign(npcDeploy: Boolean = true, seed: String = "00",
        bundle: ResolvedWorldArtifacts = this.bundle, captureBattle: Boolean = false): Campaign {
        val topology = bundle.projection.topology
        val metrics = bundle.landMarchMetrics
        val cells = bundle.provinceCells
        val scenario = ScenarioJson.loadScenario(Files.readString(repo.resolve("tools/e2e/fixtures/yuzhou/scenario_990002.json")))
        val owner = scenario.nations.flatMap { n -> n.cities.map { it.toInt() to n.id } }.toMap()
        val warehouses = requireNotNull(scenario.warehouses).warehouses
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
        val lords = scenario.generals.filter { it.lord == true }
        val generals = lords.mapIndexed { index, g ->
            TurnGeneral(id = index + 1, name = g.name, nationId = g.nationId, cityId = g.locatedCity!!.toInt(), troopId = 0,
                stats = GeneralStats(g.leadership, g.strength, g.intel, g.politics, g.charm), experience = 0, dedication = 0,
                officerLevel = 12, npcState = 2, turnTime = Instant.parse("0190-01-01T00:00:00Z").plusSeconds(index.toLong()),
                meta = mapOf(LordStatus.META_KEY to true, PersonPolicyState.META_KEY to g.personPolicy!!.toMetaValue()))
        }
        val units = scenario.units.mapIndexed { index, u ->
            Bugok(index + 1, lords.indexOfFirst { it.name == u.general } + 1, u.name, u.troops, u.crewTypeId, u.training, u.morale,
                provisions = u.provisions)
        }
        val positions = generals.fold(GeneralPositionSnapshot(topology.topologyRevision, topology.contentHash, topology.landProvinceIds,
            topology.waterZones.map { it.id }.toSet())) { s, g ->
            s.withState(GeneralPositionState(topology.topologyRevision, topology.contentHash, g.id,
                StrategicNodeRef.LandProvince(provinceOf.getValue(g.cityId)), 1))
        }
        val state = TurnWorldState(1, 190, 1, 3600, Instant.parse("0190-01-01T00:00:00Z"), currentPhase = 1,
            config = mapOf("ruleProfile" to "HWIHA", "mapName" to "han-world-v3"), worldMapVariant = bundle.variant,
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
        val observations = mutableListOf<BattleOutcomeObservation>()
        val handler = ReservedTurnHandler(world, opensamguk.logic.actions.CommandRegistry(opensamguk.logic.stats.GeneralActionPipeline()),
            seed, 190, recorder = recorder, deploymentContext = topology to metrics, provinceCells = cells,
            warOutcomes = outcomes)
        val selector = NpcDeploySelector(topology, metrics)
        val lifecycle = TurnDaemonLifecycle(world, handler,
            movementOf = AssignmentMarchTurn(world, recorder, topology, metrics, cells, outcomes,
                observations = if (captureBattle) BattleOutcomeObserver { observations.add(it) } else BattleOutcomeObserver.NONE,
                reactions = MarchReactionInterpreter(topology, metrics, bundle.commanderyIndex))::onTurn,
            npcInputOf = if (npcDeploy) { id, reserved -> selector.select(world, id, reserved) } else { _, reserved -> reserved },
            reservedActionOf = { CampaignWorldFixture.NO_INPUT })
        return Campaign(world, lifecycle, PhaseBoundary(topology, metrics, cells, outcomes = outcomes), recorder,
            outcomes, observations)
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
            run.world.listSieges().size
        assertTrue(deployed > 0, "NPC lords deployed")
        assertTrue(run.world.listSieges().isNotEmpty(), "an arrived corps besieged a county")
        assertTrue(run.outcomes.encounters.isNotEmpty(), "a relief corps met a besieging corps and the battle resolved")
        assertTrue(run.world.listGenerals().any { EncounterResolver.BATTLE_RECORD_KEY in it.meta }, "battle record kept")
        val captured = run.world.listSieges().filter { it.status == SiegeService.FALLEN }
        assertTrue(captured.isNotEmpty(), "a county fell: ${run.world.listSieges().map { it.countyId to it.status }}")
        // A county can change hands more than once; the row keeps its latest siege, so its besieger holds it now.
        assertTrue(captured.all { run.world.getCityById(it.countyId)!!.nationId == it.besiegerNationId },
            captured.joinToString { "${it.countyId}: was ${owners[it.countyId]} now ${run.world.getCityById(it.countyId)!!.nationId} by ${it.besiegerNationId} ${it.endReason}" })
        assertTrue(run.outcomes.captures.size >= captured.size, "every capture is reported, recaptures included")
        assertTrue(captured.any { owners[it.countyId] != it.besiegerNationId }, "the map changed hands")
        val durations = captured.map { it.turns }.sorted()
        println("yuzhou-simulation encounters=${run.outcomes.encounters.size} sieges=${run.world.listSieges().size} " +
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
                    run.world.listSieges().sortedBy { it.countyId }.map { listOf(it.countyId, it.status, it.turns, it.timeline) },
                    run.outcomes.encounters.toList(), run.outcomes.captures.toList(),
                )
            }
        }
        assertEquals(trace(), trace(), "the 36 phase result must not depend on process state or iteration order")
    }

    @Test fun `cutting npc deployment leaves the slice without sieges or captures`() {
        val run = campaign(npcDeploy = false)
        repeat(12) { run.phase(it) }
        assertTrue(run.world.listSieges().isEmpty())
        assertTrue(run.outcomes.captures.isEmpty() && run.outcomes.encounters.isEmpty())
    }

    @Test fun `Map4 first sealed encounter reaches a winner before the round cap`() {
        val run = campaign(bundle = map4Bundle, captureBattle = true)
        repeat(3) { run.phase(it) }
        val encounter = run.world.listGenerals().mapNotNull {
            CorpsEncounter.read(it.meta, map4Bundle.projection.topology)
        }.distinctBy { it.encounterId }.single()
        val meta = run.world.getGeneralById(encounter.attacker.commanderGeneralId)!!.meta
        val deployment = assertIs<EncounterDeployment.Result.Ready>(
            EncounterDeployment.read(meta, encounter, map4Bundle.provinceCells)).deployment
        val attacker = deployment.tokens.filter { it.commanderGeneralId == encounter.attacker.commanderGeneralId }
            .mapNotNull { it.position }
        val defenders = deployment.tokens.filter { it.commanderGeneralId != encounter.attacker.commanderGeneralId }
            .mapNotNull { it.position }
        val gap = attacker.minOf { a -> defenders.minOf { d ->
            kotlin.math.abs(a.col - d.col) + kotlin.math.abs(a.row - d.row)
        } }
        assertEquals(3, gap, "Map4 opening deployment must keep its measured front gap")
        val forces = assertNotNull(EncounterForces.read(meta, encounter))
        val relations = assertNotNull(EncounterRelations.read(meta, encounter))
        val rules = UnitProfilesJson.loadDefault()
        val combat = assertNotNull(EncounterCombatProfiles.read(meta, forces, rules))
        val plans = assertNotNull(BattlePlans.read(meta, encounter))
        val journal = assertNotNull(BattleJournal.read(meta))
        val replay = EncounterResolution.resolve(encounter, forces, relations, combat, plans, deployment, journal)
        assertEquals(replay.replayHash,
            EncounterResolution.resolve(encounter, forces, relations, combat, plans, deployment, journal).replayHash)

        val swapped = encounter.copy(attacker = encounter.defenders.single(), defenders = listOf(encounter.attacker))
        val swappedForces = EncounterForces(swapped.encounterId, forces.units, forces.commanders)
        val projection = assertNotNull(DeploymentExecutor(run.world, run.recorder, map4Bundle.projection.topology,
            map4Bundle.landMarchMetrics).projection())
        val activeWars = run.world.listDiplomacy().filter { it.state == 0 }
            .mapTo(linkedSetOf()) { it.fromNationId to it.toNationId }
        val swappedRelations = EncounterRelations.capture(swapped, projection, activeWars)
        val swappedCombat = EncounterCombatProfiles.capture(swappedForces, rules)
        val swappedPlans = BattlePlans.defaultFor(swapped)
        val swappedDeployment = assertIs<EncounterDeployment.Result.Ready>(
            EncounterDeployment.prepareDefault(swapped, map4Bundle.provinceCells,
                EncounterDeployment.RULE_VERSION)).deployment
        val swappedJournal = BattlePlayback(swapped, swappedForces, swappedRelations, swappedCombat,
            swappedPlans, swappedDeployment).initialJournal()
        val swappedReplay = EncounterResolution.resolve(swapped, swappedForces, swappedRelations, swappedCombat,
            swappedPlans, swappedDeployment, swappedJournal)
        assertEquals(swappedReplay.replayHash,
            EncounterResolution.resolve(swapped, swappedForces, swappedRelations, swappedCombat,
                swappedPlans, swappedDeployment, swappedJournal).replayHash)

        assertEquals(20, swappedReplay.rounds)
        assertEquals(listOf(swapped.attacker.commanderGeneralId), swappedReplay.winners)

        run.phase(3)
        val record = run.world.getGeneralById(encounter.attacker.commanderGeneralId)!!.meta[EncounterResolver.BATTLE_RECORD_KEY]
            as? Map<*, *>
        assertEquals(replay.replayHash, record?.get("replayHash"))
        assertTrue(run.outcomes.encounters.isNotEmpty(),
            "Map4 opening gap=$gap outcome=${record?.get("outcome")} statuses=${record?.get("statuses")} " +
                "barrier=${record?.get("barrier")} rounds=${record?.get("rounds")} produced no winner callback")
        val observation = run.observations.single { it.encounterId == encounter.encounterId }
        assertTrue(observation.winners.isNotEmpty())
        assertTrue(observation.callbackInvoked)
        assertEquals(20, observation.rounds)
        assertEquals(listOf(encounter.attacker.commanderGeneralId), observation.winners)
        assertEquals(EncounterDeployment.RULE_VERSION, observation.deploymentRuleVersion)
        assertEquals(BattlefieldLayout.FRONTLINE_RULE_VERSION, observation.layoutRuleVersion)
        val repeatedHashes = listOf("00", "01").map { seed ->
            val repeated = campaign(seed = seed, bundle = map4Bundle, captureBattle = true)
            repeat(4) { repeated.phase(it) }
            repeated.observations.single { it.encounterId == encounter.encounterId }.replayHash
        }
        assertEquals(listOf(observation.replayHash, observation.replayHash), repeatedHashes)
        println("map4-opening gap=$gap rounds=${observation.rounds} winners=${observation.winners} " +
            "callback=${observation.callbackInvoked} replayHash=${observation.replayHash} " +
            "swappedRounds=${swappedReplay.rounds} swappedWinners=${swappedReplay.winners} " +
            "swappedReplayHash=${swappedReplay.replayHash} seedHashes=$repeatedHashes")
    }
}

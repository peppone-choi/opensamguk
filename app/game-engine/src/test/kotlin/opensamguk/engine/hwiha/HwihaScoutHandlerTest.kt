package opensamguk.engine.hwiha

import java.nio.file.Path
import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.*
import opensamguk.infra.seed.HanWorldArtifactsResolver
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.input.*
import opensamguk.logic.world.*

/** `action.scout` on the pinned 1133 map, in memory (the DB round trip is HwihaScoutPersistenceIT). */
class HwihaScoutHandlerTest {
    private val bundle by lazy { HanWorldArtifactsResolver(Path.of("../..")).artifacts(HanWorldVariant.V3_1133) }
    private val index by lazy { bundle.commanderyIndex }
    private val context by lazy { HwihaVisionContext(bundle.projection.topology, bundle.landMarchMetrics, index) }

    /** A commandery with a neighbour and a non-neighbour, each with a land province. */
    private data class Geo(val home: String, val next: String, val far: String, val nextId: String, val farId: String)
    private val geo by lazy {
        val provinces = index.provinceIds.sorted().groupBy { index.commanderyOf(it)!! }
        val home = provinces.keys.sorted().first { index.neighbours(it).isNotEmpty() }
        val next = index.neighbours(home).first()
        val far = provinces.keys.sorted().first { it != home && it != next && !index.adjacent(home, it) }
        Geo(provinces.getValue(home).first(), provinces.getValue(next).first(), provinces.getValue(far).first(),
            index.commanderies[next].id, index.commanderies[far].id)
    }

    private fun general(id: Int, nation: Int, city: Int, user: String?) = TurnGeneral(id = id, name = "G$id", nationId = nation,
        cityId = city, userId = user, npcState = if (user == null) 2 else 0, troopId = 0, stats = GeneralStats(70, 70, 70),
        experience = 0, dedication = 0, officerLevel = 0, gold = 0, rice = 0, crew = 0, turnTime = Instant.EPOCH)

    private fun world(actorAt: String = geo.home, extraActorMeta: Map<String, Any?> = emptyMap()): InMemoryTurnWorld {
        val topology = bundle.projection.topology
        val at = mapOf(1 to actorAt, 2 to geo.next, 3 to geo.far)
        val positions = GeneralPositionSnapshot.fromTopology(topology,
            at.map { (id, province) -> GeneralPositionState(topology.topologyRevision, topology.contentHash, id, StrategicNodeRef.LandProvince(province), 1) })
        val deployed = { owner: Int, order: String, unit: Int ->
            mapOf(HwihaDeploymentState.META_KEY to HwihaDeploymentState(listOf(
                HwihaDeployedCorps(order, owner, owner, null, 2, listOf(unit), HwihaPhase(190, 1, 1)))).toMetaValue())
        }
        return InMemoryTurnWorld(WorldSnapshot(worldId = WorldId(1),
            state = TurnWorldState(1, 190, 3, 3600, Instant.EPOCH, currentPhase = 2,
                config = mapOf("ruleProfile" to "HWIHA", "mapName" to "han-world-v3")),
            generals = listOf(general(1, 1, 10, "42").copy(meta = extraActorMeta),
                general(2, 2, 20, "43").copy(meta = deployed(2, "req-secret-next", 7)),
                general(3, 2, 30, "44").copy(meta = deployed(3, "req-secret-far", 8))),
            nations = listOf(Nation(1, "N1", "#111111"), Nation(2, "N2", "#222222")),
            cities = listOf(City(10, "집", 1, 1), City(20, "옆", 2, 1,
                meta = mapOf(HwihaCountyWarehouse.META_KEY to HwihaCountyWarehouse(20, 0, HwihaResources(5, 5, 5, 5, 5)).toMetaValue())),
                City(30, "먼곳", 2, 1)),
            bugoks = listOf(Bugok(7, 2, "옆 부곡", 6400, 1, 50, 50), Bugok(8, 3, "먼 부곡", 900, 1, 50, 50)),
            generalPositionSnapshot = positions,
            cityLandProvinceById = mapOf(10 to geo.home, 20 to geo.next, 30 to geo.far)))
    }

    private fun args(id: String) = """{"commanderyId":"$id"}"""

    @Test fun `scouting a neighbour stores a private banded snapshot through the recorder`() {
        val world = world(); val recorder = ChangeRecorder()
        val outcome = HwihaScoutHandler(world, recorder, context).handle(1, args(geo.nextId), 42)
        assertEquals(HwihaTurnOutcome.Applied(HwihaScoutInput.INPUT_ID), outcome)
        val notebook = assertNotNull(HwihaScoutReports.read(world.getGeneralById(1)!!.meta))
        assertEquals(index.tilesContentHash, notebook.tilesContentHash)
        val report = notebook.reports.single()
        assertEquals(geo.nextId, report.commanderyId); assertEquals(HwihaPhase(190, 3, 2), report.seenAt)
        assertEquals(listOf(ScoutedCity(20, 2, true)), report.cities)
        val corps = report.corps.single()
        assertEquals(HwihaScoutCapture.corpsKey("req-secret-next"), corps.corpsKey)
        assertEquals("B3", corps.troopsBand); assertEquals(geo.next, corps.provinceId)
        // Only the actor row changes, and it flushes through the recorder like any personal-turn write.
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        assertEquals(listOf(1), payload.updatedGenerals.map { it.id })
        val stored = notebook.toMetaValue().toString()
        assertFalse("req-secret" in stored || "6400" in stored, stored)
    }

    @Test fun `the step-7 re-check rejects without writing when the target is not next to the actor`() {
        val world = world(); val before = world.getGeneralById(1)
        val outcome = assertIs<HwihaTurnOutcome.Rejected>(HwihaScoutHandler(world, ChangeRecorder(), context).handle(1, args(geo.farId), 42))
        assertEquals(ScoutFailure.NOT_ADJACENT.name, outcome.code)
        assertEquals(before, world.getGeneralById(1))
        assertEquals("FORBIDDEN", assertIs<HwihaTurnOutcome.Rejected>(
            HwihaScoutHandler(world, ChangeRecorder(), context).handle(1, args(geo.nextId), 43)).code)
        assertEquals(ScoutFailure.INVALID_INPUT.name, assertIs<HwihaTurnOutcome.Rejected>(
            HwihaScoutHandler(world, ChangeRecorder(), context).handle(1, """{"commanderyId":1}""", 42)).code)
        assertEquals(ScoutFailure.STATE_UNAVAILABLE.name, assertIs<HwihaTurnOutcome.Rejected>(
            HwihaScoutHandler(world, ChangeRecorder(), null).handle(1, args(geo.nextId), 42)).code)
        assertEquals(before, world.getGeneralById(1))
    }

    @Test fun `a corrupt notebook is never overwritten and a notebook from other tiles is replaced`() {
        val corrupt = world(extraActorMeta = mapOf(HwihaScoutReports.META_KEY to mapOf("version" to 9)))
        assertEquals(ScoutFailure.STATE_UNAVAILABLE.name, assertIs<HwihaTurnOutcome.Rejected>(
            HwihaScoutHandler(corrupt, ChangeRecorder(), context).handle(1, args(geo.nextId), 42)).code)
        val stale = HwihaScoutReports("0".repeat(64), listOf(HwihaScoutReport("PARENT-OLD", HwihaPhase(189, 1, 1), emptyList(), emptyList())))
        val world = world(extraActorMeta = mapOf(HwihaScoutReports.META_KEY to stale.toMetaValue()))
        assertIs<HwihaTurnOutcome.Applied>(HwihaScoutHandler(world, ChangeRecorder(), context).handle(1, args(geo.nextId), 42))
        assertEquals(listOf(geo.nextId), HwihaScoutReports.read(world.getGeneralById(1)!!.meta)!!.reports.map { it.commanderyId })
    }

    @Test fun `scouting again replaces the old snapshot of that commandery only`() {
        val world = world(); val handler = HwihaScoutHandler(world, ChangeRecorder(), context)
        assertIs<HwihaTurnOutcome.Applied>(handler.handle(1, args(geo.nextId), 42))
        world.setCurrentDate(190, 4, 1)
        assertIs<HwihaTurnOutcome.Applied>(handler.handle(1, args(geo.nextId), 42))
        val report = HwihaScoutReports.read(world.getGeneralById(1)!!.meta)!!.reports.single()
        assertEquals(190, report.seenAt.year); assertEquals(4, report.seenAt.month)
    }

    @Test fun `the personal turn routes action_scout through the registry instead of rest`() {
        val world = world()
        val handler = ReservedTurnHandler(world = world, registry = opensamguk.logic.actions.CommandRegistry(
            opensamguk.logic.stats.GeneralActionPipeline()), hiddenSeed = "seed", startYear = 184,
            hwihaVisionContext = context)
        val turn = handler.handle(1, opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn(
            actionCode = HwihaScoutInput.INPUT_ID, argJson = args(geo.nextId), requestId = "r-1", reservationOwnerUserId = 42),
            190, 3, "00:00")
        assertEquals(HwihaTurnOutcome.Applied(HwihaScoutInput.INPUT_ID), turn.hwihaOutcome)
        assertNotNull(HwihaScoutReports.read(world.getGeneralById(1)!!.meta))
    }
}

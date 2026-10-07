package opensamguk.engine.world

import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.campaign.PhaseBoundary
import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.*

class WorldActionContextSupplyPassageTest {
    private val tilesHash = "a".repeat(64)
    private val topology = StrategicTopologySnapshot("supply-test", setOf("a", "b"), emptyList(), listOf(
        TraversalEdge("ab", StrategicNodeRef.LandProvince("a"), StrategicNodeRef.LandProvince("b"),
            TraversalMode.LAND, false, 1, 1, RiskBand.LOW, SeasonalAvailability.ALWAYS, true,
            listOf("test:source"), EvidenceConfidence.REVIEWED, initiallyOpen = false)
    ), emptyList(), mapOf(LandMarchMetricSnapshot.TILES_PATH to tilesHash))
    private val metrics = LandMarchMetricSnapshot(topology, tilesHash, listOf(LandMarchEdgeMetric("ab", 1, 1)))
    private val cells = ProvinceCellIndex(topology.topologyRevision, topology.contentHash, tilesHash, 2, 1,
        mapOf('0' to "plain"), mapOf("a" to listOf(ProvinceCell(0, 0, '0')), "b" to listOf(ProvinceCell(1, 0, '0'))))


    private fun world(open: Boolean = true, fortOwner: Int? = null, missing: Boolean = false): InMemoryTurnWorld {
        val passage = mapOf(LandPassageState.META_KEY to LandPassageState.initialMetaValue(topology))
        val meta = if (missing) emptyMap() else if (open) passage +
            (LandPassageState.META_KEY to LandPassageState.activate(passage, topology, "ab")) else passage
        val forts = fortOwner?.let { listOf(RoadFort("ab@0,0", "ab", "a", 0, 0, it, 100, 1)) }.orEmpty()
        val state = TurnWorldState(1, 200, 1, 3600, Instant.EPOCH,
            config = mapOf("mapName" to "han-world-v3", "ruleProfile" to "HWIHA"),
            worldMapVariant = WorldMapVariant.PROVINCE_WORLD, meta = meta + (RoadFortState.META_KEY to RoadFortState.toMetaValue(forts)))
        return InMemoryTurnWorld(WorldSnapshot(worldId = WorldId(1), state = state,
            nations = listOf(Nation(1, "N1", "#000", level = 1, capitalCityId = 1)),
            cities = (1..2).map { City(it, "C$it", 1, 1, population = 1000, defence = 10, supplyState = 0, meta = mapOf("trust" to 20.0)) },
            diplomacy = listOf(TurnDiplomacy(1, 2, 0, 0)),
            generalPositionSnapshot = GeneralPositionSnapshot(topology.topologyRevision, topology.contentHash, topology.landProvinceIds, emptySet())))
    }
    private fun network(blocked: Boolean = false) = SpatialSupplyNetwork(intArrayOf(1, 1),
        listOf(intArrayOf(1), intArrayOf(0)), mapOf(1 to 0, 2 to 1),
        strategicSupply = StrategicSupplyNetwork(topology, listOf("a", "b"), null,
            militaryBlocksByNation = if (blocked) mapOf(1 to setOf("b")) else emptyMap()))
    private fun context(world: InMemoryTurnWorld, recorder: ChangeRecorder, blocked: Boolean = false) = WorldActionContext(
        linkedMapOf("year" to 200, "month" to 1), world, recorder, GeneralActionPipeline(emptyList()),
        spatialSupplyNetworkProvider = { network(blocked) })
    private fun monthly(world: InMemoryTurnWorld, recorder: ChangeRecorder = ChangeRecorder(), blocked: Boolean = false) {
        val ctx = context(world, recorder, blocked)
        UpdateCitySupplyAction().run(ctx)
    }
    @Test fun `monthly supply uses a newly completed road before any destructive settlement`() {
        val world = world(); monthly(world)
        val city = world.getCityById(2)!!
        assertEquals(1, city.supplyState); assertEquals(1000, city.population)
        assertEquals(20.0, city.meta["trust"]); assertEquals(1, city.nationId)
        assertNull(city.meta["supplyAssessment"])
    }
    @Test fun `monthly and phase use the same closed passage and hostile fort decision`() {
        for (world in listOf(world(open = false), world(fortOwner = 2))) {
            val recorder = ChangeRecorder(); monthly(world, recorder)
            val before = world.getCityById(2)!!
            assertEquals(0, before.supplyState)
            assertEquals("PASSAGE_CUT", (before.meta["supplyAssessment"] as Map<*, *>)["code"])
            assertTrue(PhaseBoundary(topology, metrics, cells, { network() }).recomputeSupply(world, recorder, emptySet()) >= 0)
            assertEquals(before.supplyState, world.getCityById(2)!!.supplyState)
            assertTrue(recorder.cityPatches().any { it.id == 2 && "supplyAssessment" in it.meta })
        }
    }
    @Test fun `friendly fort cannot cut supply and military block survives live road overlay`() {
        val friendly = world(fortOwner = 1); monthly(friendly)
        assertEquals(1, friendly.getCityById(2)!!.supplyState)
        val blocked = world(); monthly(blocked, blocked = true)
        assertEquals("MILITARY_CUT", (blocked.getCityById(2)!!.meta["supplyAssessment"] as Map<*, *>)["code"])
    }
    @Test fun `missing passage rejects monthly settlement without changing flags or population`() {
        val world = world(missing = true)
        assertFailsWith<IllegalArgumentException> { monthly(world) }
        assertEquals(1000, world.getCityById(2)!!.population)
        assertTrue(world.listCities().all { it.supplyState == 0 })
    }
    @Test fun `phase records a siege cause and clears it after supply recovers`() {
        val world = world(); val recorder = ChangeRecorder()
        val boundary = PhaseBoundary(topology, metrics, cells, { network() })
        boundary.recomputeSupply(world, recorder, setOf(2))
        assertEquals("SIEGE", (world.getCityById(2)!!.meta["supplyAssessment"] as Map<*, *>)["code"])
        boundary.recomputeSupply(world, recorder, emptySet())
        assertEquals(1, world.getCityById(2)!!.supplyState); assertNull(world.getCityById(2)!!.meta["supplyAssessment"])
    }
    @Test fun `legitimate isolation neutralization cannot retain the former owner's cut reason`() {
        val world = world(open = false)
        world.updateCity(world.getCityById(2)!!.copy(defence = 0))
        monthly(world)
        assertEquals(0, world.getCityById(2)!!.nationId)
        assertNull(world.getCityById(2)!!.meta["supplyAssessment"])
    }

}

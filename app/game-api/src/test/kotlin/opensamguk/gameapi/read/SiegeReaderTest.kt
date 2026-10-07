package opensamguk.gameapi.read

import opensamguk.gameapi.web.SiegeController
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.DeployedCorps
import opensamguk.logic.input.DeploymentState
import opensamguk.logic.input.Phase
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.world.*
import org.mockito.Mockito.*
import org.springframework.http.HttpStatus
import java.util.Optional
import kotlin.test.*

class SiegeReaderTest {
    private val generals = mock(GeneralReadRepository::class.java)
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val retainers = mock(RetainerReadRepository::class.java)
    private val sieges = mock(SiegeReadRepository::class.java)
    private val artifacts = mock(ActiveWorldArtifactResolver::class.java)
    private val spatial = mock(SpatialStateReadRepository::class.java)
    private val diplomacy = mock(DiplomacyReadRepository::class.java)
    private val reader = SiegeReader(generals, worlds, nations, cities, retainers, sieges, artifacts, spatial, diplomacy)
    private val controller = SiegeController(reader)
    private val world = WorldStateReadEntity(id = 1, config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN"))

    private val corps = DeployedCorps("order-1", 1, 1, null, 1, listOf(21), Phase(190, 1, 1))
    private val besieger = GeneralReadEntity(id = 1, worldId = 1, name = "공격", nationId = 1, userId = "41",
        meta = mapOf(DeploymentState.META_KEY to DeploymentState(listOf(corps)).toMetaValue()))
    private val defender = GeneralReadEntity(id = 2, worldId = 1, name = "수비", nationId = 2, userId = "42")
    private val stranger = GeneralReadEntity(id = 3, worldId = 1, name = "제삼", nationId = 3, userId = "43")
    private val row = SiegeReadRow(77, "ACTIVE", 1, 1, "order-1", "A", 1, 2, 190, 1, 1, turns = 3, morale = 2500,
        garrison = 840, endReason = null, timeline = listOf(mapOf("event" to "START")))
    private val topology = StrategicTopologySnapshot("test", setOf("A", "B"), emptyList(), emptyList(), emptyList(),
        mapOf(LandMarchMetricSnapshot.TILES_PATH to "a".repeat(64)))
    private val cells = ProvinceCellIndex("test", topology.contentHash, "a".repeat(64), 4, 1,
        mapOf('1' to "PLAIN"), mapOf("A" to listOf(ProvinceCell(0, 0, '1')),
            "B" to (1..3).map { ProvinceCell(it, 0, '1') }))

    private fun setup(profile: String = "GENERAL_RETAINER_CAMPAIGN") {
        world.config = mapOf("worldFormat" to profile)
        `when`(worlds.findProcessWorld()).thenReturn(world)
        listOf(besieger, defender, stranger).forEach { `when`(generals.findById(it.id)).thenReturn(Optional.of(it)) }
        `when`(nations.findAll()).thenReturn(listOf(NationReadEntity(id = 1, worldId = 1, name = "양"),
            NationReadEntity(id = 2, worldId = 1, name = "여남")))
        `when`(cities.findById(77)).thenReturn(Optional.of(CityReadEntity(id = 77, worldId = 1, name = "익양현", nationId = 2,
            trust = 40.0, supplyState = 0, meta = mapOf(CountyWarehouse.META_KEY to
                CountyWarehouse(77, 3, Resources(grain = 12_345)).toMetaValue()))))
        `when`(retainers.bugoksOf(1)).thenReturn(listOf(GeneralBugokReadEntity(worldId = 1, id = 21, masterGeneralId = 1,
            name = "부곡", troops = 4000, provisions = 24_000, crewTypeId = 1100)))
        val bundle = mock(ResolvedWorldArtifacts::class.java)
        `when`(bundle.projection).thenReturn(StrategicRouteProjection(topology,
            listOf(StrategicRouteBinding(77, "r77", "county77", "B", true))))
        `when`(bundle.provinceCells).thenReturn(cells)
        `when`(bundle.landMarchMetrics).thenReturn(LandMarchMetricSnapshot(topology, "a".repeat(64), emptyList()))
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, emptyList(), bundle))
        val node = StrategicNodeRef.LandProvince("B")
        `when`(spatial.readSnapshot(1, topology)).thenReturn(SpatialStateReadSnapshot(
            ProvinceControlSnapshot.fromTopology(topology), GeneralPositionSnapshot.fromTopology(topology,
                listOf(GeneralPositionState("test", topology.contentHash, 1, node, 1)))))
        `when`(diplomacy.findAll()).thenReturn(listOf(DiplomacyReadEntity(worldId = 1, srcNationId = 1,
            destNationId = 2, stateCode = 0)))
        `when`(sieges.involving(1, 1)).thenReturn(listOf(row))
        `when`(sieges.involving(2, 2)).thenReturn(listOf(row))
        `when`(sieges.involving(3, 3)).thenReturn(emptyList())
    }

    @Test fun `the besieging commander sees the siege it can act on`() {
        setup()
        val body = reader.sieges(1, 41)
        assertEquals("READY", body.status)
        val siege = body.sieges.single()
        assertEquals("익양현", siege.countyName); assertEquals(12_345L, siege.grain); assertEquals(2500, siege.morale)
        assertEquals(3, siege.turns); assertEquals("양", siege.besieger.nationName); assertEquals("여남", siege.defenderNationName)
        assertEquals(4000, siege.besiegerTroops); assertEquals(true, siege.besiegerFed); assertFalse(siege.countySupplied)
        assertTrue(siege.canAct); assertTrue(siege.surrenderDemandAccepted, "morale 25% and trust 40 meet the threshold")
        assertTrue(siege.canAssault); assertNull(siege.assaultCode)
        assertEquals(listOf(mapOf<String, Any?>("event" to "START")), siege.timeline)
    }

    @Test fun `the defending side sees it without acting and outsiders see nothing`() {
        setup()
        val seen = reader.sieges(2, 42).sieges.single()
        assertFalse(seen.canAct)
        assertFalse(seen.canAssault)
        assertTrue(reader.sieges(3, 43).sieges.isEmpty())
    }

    @Test fun `assault option closes when turn or selected corps changes`() {
        setup()
        `when`(sieges.involving(1, 1)).thenReturn(listOf(row.copy(turns = 2)))
        val early = reader.sieges(1, 41).sieges.single()
        assertTrue(early.canAct); assertFalse(early.canAssault)
        assertEquals("ASSAULT_NOT_READY", early.assaultCode)
        `when`(sieges.involving(1, 1)).thenReturn(listOf(row.copy(besiegerOrderId = "replaced")))
        val changed = reader.sieges(1, 41).sieges.single()
        assertFalse(changed.canAssault); assertEquals("STATE_UNAVAILABLE", changed.assaultCode)
    }

    @Test fun `assault option closes when battle or county ownership changes`() {
        setup()
        `when`(spatial.readSnapshot(1, topology)).thenReturn(SpatialStateReadSnapshot(
            ProvinceControlSnapshot.fromTopology(topology), GeneralPositionSnapshot.fromTopology(topology,
                listOf(GeneralPositionState("test", topology.contentHash, 1, StrategicNodeRef.LandProvince("B"), 1,
                    BattlefieldPresence("field", "b".repeat(64), 77))))))
        val battling = reader.sieges(1, 41).sieges.single()
        assertFalse(battling.canAssault); assertEquals("BATTLE_PENDING", battling.assaultCode)
        `when`(spatial.readSnapshot(1, topology)).thenReturn(SpatialStateReadSnapshot(
            ProvinceControlSnapshot.fromTopology(topology), GeneralPositionSnapshot.fromTopology(topology,
                listOf(GeneralPositionState("test", topology.contentHash, 1, StrategicNodeRef.LandProvince("B"), 1)))))
        `when`(cities.findById(77)).thenReturn(Optional.of(CityReadEntity(id = 77, worldId = 1,
            name = "익양현", nationId = 1)))
        val changed = reader.sieges(1, 41).sieges.single()
        assertFalse(changed.canAssault); assertEquals("TARGET_CHANGED", changed.assaultCode)
    }

    @Test fun `assault option closes when the persisted approach has no battlefield`() {
        setup()
        `when`(sieges.involving(1, 1)).thenReturn(listOf(row.copy(approachProvinceId = "X")))
        val blocked = reader.sieges(1, 41).sieges.single()
        assertFalse(blocked.canAssault)
        assertEquals("BATTLEFIELD_UNAVAILABLE", blocked.assaultCode)
    }

    @Test fun `auth follows the camp endpoints and non HWIHA worlds report a soft status`() {
        setup()
        assertEquals(HttpStatus.UNAUTHORIZED, controller.sieges(null, 1).statusCode)
        assertEquals(HttpStatus.FORBIDDEN, controller.sieges(42, 1).statusCode)
        assertEquals(HttpStatus.OK, controller.sieges(41, 1).statusCode)
        setup("SAMMO")
        assertEquals("UNSUPPORTED_WORLD_FORMAT", reader.sieges(1, 41).status)
    }
}

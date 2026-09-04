package opensamguk.gameapi.v2

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.precheck.PrecheckStateViewFactory
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.DiplomacyReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.v2.command.V2CityTransportArgs
import opensamguk.logic.v2.command.V2CommandAvailability
import opensamguk.logic.v2.command.V2CommandRegistry
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import opensamguk.logic.world.*
import opensamguk.infra.seed.HanStrategicTopologyJson
import java.nio.file.Path

class V2CommandPrecheckServiceTest {
    private val transportArgs = V2CityTransportArgs(1, 2, 100, 0, 0, null)

    @Test
    fun `V3 preview produces exact stable route then precheck validates both pins`() {
        val service = service("han-world-v3", 2000, ::projection)
        val preview = service.previewTransport(10, transportArgs)
        assertEquals("AVAILABLE", preview.status)
        val route = assertNotNull(preview.route)
        assertEquals(listOf("land:45098", "land:45022"), route.nodeKeys)
        assertEquals(listOf("lu-li"), route.edgeIds)
        assertEquals(listOf("LAND"), route.modes)
        assertEquals(1L, route.totalCost)
        assertEquals(1000, route.capacity)
        val pinned = transportArgs.copy(topologyRevision = route.topologyRevision, routePathHash = route.pathHash)
        val available = V2CommandAvailability.Available(V2CommandRegistry.cityTransportSchema, pinned)
        assertEquals(available, service.precheck(10, available))
        listOf(
            transportArgs to "TOPOLOGY_REVISION_REQUIRED",
            pinned.copy(topologyRevision = "old") to "TOPOLOGY_REVISION_STALE",
            pinned.copy(routePathHash = "different") to "ROUTE_PATH_HASH_STALE",
        ).forEach { (args, code) ->
            assertEquals(code, assertIs<V2CommandAvailability.Blocked>(service.precheck(10, available.copy(args = args))).code)
        }
    }

    @Test
    fun `V3 preview checks crew balances and missing artifacts without offering a route`() {
        listOf(
            service("han-world-v3", 1999, ::projection) to transportArgs to "ESCORT_INSUFFICIENT",
            service("han-world-v3", 2000, ::projection) to transportArgs.copy(gold = 101) to "CITY_GOLD_INSUFFICIENT",
            service("han-world-v3", 2000, loadTopology = { error("missing artifacts") }) to transportArgs to "TOPOLOGY_STATE_INVALID",
            service("han-world-v3", 2000, loadTopology = { projection().let { HanStrategicRouteProjection(it.topology, it.bindingsByCityId.values.filter { binding -> binding.runtimeCityId == 1 }) } }) to transportArgs to "UNKNOWN_NODE",
        ).forEach { (case, code) ->
            val preview = case.first.previewTransport(10, case.second)
            assertEquals("BLOCKED", preview.status)
            assertEquals(code, preview.code)
            assertNull(preview.route)
        }
    }

    @Test
    fun `legacy preview explicitly has no strategic route and never loads V3 artifacts`() {
        listOf("han", "han-780-v1", "han-world-v2").forEach { mapName ->
            val preview = service(mapName, 2000).previewTransport(10, transportArgs)
            assertEquals("AVAILABLE", preview.status)
            assertNull(preview.route)
        }
    }

    @Test
    fun `real Lu Licheng topology preview is available with reviewed dry land only`() {
        val loader = { HanStrategicTopologyJson.loadFromDirectory(Path.of("../.."), "han-world-v3") }
        val service = service("han-world-v3", 2000, loader, fromCityId = 273, toCityId = 781)
        val preview = service.previewTransport(10, transportArgs.copy(fromCityId = 273, toCityId = 781))
        assertEquals("AVAILABLE", preview.status, preview.reason)
        assertEquals(listOf("land:45098", "land:45022"), preview.route?.nodeKeys)
        assertEquals(listOf("LAND"), preview.route?.modes)
    }

    private fun projection(): HanStrategicRouteProjection = HanStrategicRouteProjection(
        StrategicTopologySnapshot("test-v3", setOf("45098", "45022"), emptyList(), listOf(
            TraversalEdge("lu-li", StrategicNodeRef.LandProvince("45098"), StrategicNodeRef.LandProvince("45022"),
                TraversalMode.LAND, false, 1, 1000, RiskBand.LOW, SeasonalAvailability.ALWAYS, true,
                listOf("reviewed:test"), EvidenceConfidence.EXACT),
        ), emptyList(), mapOf("fixture" to "abc")),
        listOf(HanStrategicRouteBinding(1, "route:lu", "physical:lu", "45098"),
            HanStrategicRouteBinding(2, "route:li", "physical:li", "45022")),
    )

    @Test
    fun `compatibility map preserves current Han transport allow and deny prechecks`() {
        val args = V2CityTransportArgs(
            fromCityId = 1,
            toCityId = 2,
            gold = 100,
            rice = 0,
            garrison = 0,
            routeRevision = null,
        )
        val available = V2CommandAvailability.Available(V2CommandRegistry.cityTransportSchema, args)
        val escortDenied = V2CommandAvailability.Blocked(
            code = "ESCORT_INSUFFICIENT",
            reason = "수송에는 병사 2000명이 필요합니다.",
        )

        assertEquals(available, service("han", crew = 2_000).precheck(10, available))
        assertEquals(available, service("han-780-v1", crew = 2_000).precheck(10, available))
        assertEquals(escortDenied, service("han", crew = 1_999).precheck(10, available))
        assertEquals(escortDenied, service("han-780-v1", crew = 1_999).precheck(10, available))
    }

    private fun service(
        mapName: String, crew: Int,
        loadTopology: () -> HanStrategicRouteProjection = { error("legacy must not load V3 topology") },
        fromCityId: Int = 1, toCityId: Int = 2,
    ): V2CommandPrecheckService {
        val generals = mock(GeneralReadRepository::class.java)
        val cities = mock(CityReadRepository::class.java)
        val nations = mock(NationReadRepository::class.java)
        val diplomacies = mock(DiplomacyReadRepository::class.java)
        val worlds = mock(WorldStateReadRepository::class.java)
        val jdbc = mock(NamedParameterJdbcTemplate::class.java)

        `when`(generals.findById(10)).thenReturn(
            Optional.of(
                GeneralReadEntity(
                    id = 10,
                    nationId = 1,
                    cityId = fromCityId,
                    leadership = 70,
                    strength = 50,
                    intel = 50,
                    crew = crew,
                ),
            ),
        )
        `when`(cities.findById(fromCityId)).thenReturn(Optional.of(CityReadEntity(id = fromCityId, nationId = 1, level = 5)))
        `when`(cities.findById(toCityId)).thenReturn(Optional.of(CityReadEntity(id = toCityId, nationId = 1, level = 5)))
        `when`(cities.findByNationIdOrderByIdAsc(1)).thenReturn(emptyList())
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, level = 7)))
        `when`(diplomacies.findBySrcNationId(1)).thenReturn(emptyList())
        `when`(worlds.findAll()).thenReturn(
            listOf(
                WorldStateReadEntity(
                    id = 1,
                    scenarioCode = "scenario_1010",
                    currentYear = 184,
                    currentMonth = 1,
                    config = mapOf("startYear" to 184, "mapName" to mapName),
                ),
            ),
        )

        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val mapper = invocation.getArgument<RowMapper<Any>>(2)
            val resultSet = mock(ResultSet::class.java)
            `when`(resultSet.getLong("gold")).thenReturn(100L)
            `when`(resultSet.getLong("rice")).thenReturn(0L)
            `when`(resultSet.getInt("garrison")).thenReturn(0)
            listOf(mapper.mapRow(resultSet, 0))
        }.`when`(jdbc).query(
            anyString(),
            any(MapSqlParameterSource::class.java),
            any<RowMapper<Any>>(),
        )

        val states = PrecheckStateViewFactory(generals, cities, nations, diplomacies, worlds)
        return V2CommandPrecheckService(states, jdbc, GameApiProcessWorld(1), loadTopology)
    }
}

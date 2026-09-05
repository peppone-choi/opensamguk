package opensamguk.gameapi.controller

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.read.*
import opensamguk.infra.seed.HanStrategicTopologyJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Path
import java.sql.ResultSet
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapStrategicTopologyControllerTest {
    private val world = mock(WorldStateReadRepository::class.java)
    private val source = StrategicTopologyReadSource { loaded }
    private val jdbc = ReadJdbc()

    @AfterEach
    fun clearIdentity() = SecurityContextHolder.clearContext()

    private fun mvc(mapName: String = "han-world-v3") = MockMvcBuilders.standaloneSetup(
        MapStrategicTopologyController(world, WaterControlReadRepository(jdbc, GameApiProcessWorld(7)), source),
    ).build().also {
        `when`(world.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 7, scenarioCode = "scenario_1050",
            config = mapOf("mapName" to mapName)))
    }

    private fun admin() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            42L, null, listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
        )
    }

    @Test
    fun `public topology preserves exact geometry but does not query private control SQL`() {
        jdbc.rejectReads = true
        mvc().perform(get("/api/map/strategic-topology"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.binding.worldId").value(7))
            .andExpect(jsonPath("$.binding.mapCode").value("han-world-v3"))
            .andExpect(jsonPath("$.binding.cols").value(768))
            .andExpect(jsonPath("$.topology.landProvinceIds.length()").value(1524))
            .andExpect(jsonPath("$.topology.geometries[0].cellCount").value(47))
            .andExpect(jsonPath("$.topology.geometries[1].cellCount").value(83))
            .andExpect(jsonPath("$.topology.waterZones.length()").value(2))
            .andExpect(jsonPath("$.topology.ports.length()").value(0))
            .andExpect(jsonPath("$.topology.riverBarriers.length()").value(0))
            .andExpect(jsonPath("$.controlVisibility").value("REDACTED"))
            .andExpect(jsonPath("$.controls[0].status").value("UNKNOWN"))
            .andExpect(jsonPath("$.controls[0].controllingNationId").value(org.hamcrest.Matchers.nullValue()))
        assertEquals(0, jdbc.queries)
    }

    @Test
    fun `ordinary authenticated players do not acquire water visibility from their identity`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            42L, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        jdbc.rejectReads = true
        mvc().perform(get("/api/map/strategic-topology"))
            .andExpect(status().isOk).andExpect(jsonPath("$.controlVisibility").value("REDACTED"))
        assertEquals(0, jdbc.queries)
    }

    @Test
    fun `administrator sees process-world control and exact bigint identities`() {
        admin()
        jdbc.rows = listOf(row(controller = 9007199254740993L, contests = "[9007199254740995]", revision = 9007199254740997L))
        mvc().perform(get("/api/map/strategic-topology"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.controlVisibility").value("VISIBLE"))
            .andExpect(jsonPath("$.controls[0].status").value("BLOCKED"))
            .andExpect(jsonPath("$.controls[0].controllingNationId").value("9007199254740993"))
            .andExpect(jsonPath("$.controls[0].contestingNationIds[0]").value("9007199254740995"))
            .andExpect(jsonPath("$.controls[0].revision").value("9007199254740997"))
            .andExpect(jsonPath("$.controls[1].status").value("UNKNOWN"))
        assertEquals(7, jdbc.parameters["world_id"])
        assertTrue(jdbc.sql.contains("WHERE world_id = :world_id") && jdbc.sql.contains("ORDER BY water_zone_id"))
    }

    @Test
    fun `absent administrator control is unknown and matching static hash avoids duplicate topology payload`() {
        admin()
        mvc().perform(get("/api/map/strategic-topology").queryParam("knownTopologyHash", loaded.topology.contentHash))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.topology").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.controls.length()").value(2))
            .andExpect(jsonPath("$.controls[0].status").value("UNKNOWN"))
    }

    @Test
    fun `legacy maps are unsupported and never read the water control table`() {
        jdbc.rejectReads = true
        for (map in listOf("han", "han-world-v2", "han-780-v1", "che")) {
            mvc(map).perform(get("/api/map/strategic-topology")).andExpect(status().isNotFound)
        }
        assertEquals(0, jdbc.queries)
    }

    @Test
    fun `stale malformed duplicate and foreign zone control fail closed`() {
        admin()
        for (rows in listOf(
            listOf(row(hash = "f".repeat(64))),
            listOf(row(zone = "water-zone:other-world")),
            listOf(row(contests = "[2,1]")),
            listOf(row(), row()),
        )) {
            jdbc.rows = rows
            mvc().perform(get("/api/map/strategic-topology"))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("STRATEGIC_STATE_INVALID"))
                .andExpect(jsonPath("$.controls").doesNotExist())
        }
    }

    @Test
    fun `an unseeded world does not default to the new topology`() {
        val api = mvc()
        `when`(world.findProcessWorld()).thenReturn(null)
        api.perform(get("/api/map/strategic-topology")).andExpect(status().isNotFound)
        assertEquals(0, jdbc.queries)
    }

    private fun row(zone: String = "water-zone:coastal-qiongzhou-strait", hash: String = loaded.topology.contentHash,
        controller: Long? = 1L, contests: String = "[]", revision: Long = 1L): ResultSet = mock(ResultSet::class.java).also {
        `when`(it.getString("water_zone_id")).thenReturn(zone)
        `when`(it.getString("topology_revision")).thenReturn(loaded.topology.topologyRevision)
        `when`(it.getString("topology_hash")).thenReturn(hash)
        `when`(it.getObject("controlling_nation_id")).thenReturn(controller)
        `when`(it.getString("contesting_nation_ids")).thenReturn(contests)
        `when`(it.getString("blockade_state")).thenReturn("BLOCKED")
        `when`(it.getLong("revision")).thenReturn(revision)
    }

    private class ReadJdbc : NamedParameterJdbcTemplate(mock(JdbcOperations::class.java)) {
        var rows = emptyList<ResultSet>()
        var rejectReads = false
        var queries = 0
        var sql = ""
        var parameters: Map<String, Any?> = emptyMap()
        override fun <T> query(sql: String, parameters: SqlParameterSource, mapper: RowMapper<T>): List<T> {
            check(!rejectReads) { "Public request attempted private SQL" }
            this.sql = sql
            this.parameters = (parameters as MapSqlParameterSource).values
            queries++
            return rows.mapIndexed { index, row -> mapper.mapRow(row, index)!! }
        }
    }

    companion object {
        private val loaded by lazy { HanStrategicTopologyJson.loadFromDirectory(Path.of("../.."), "han-world-v3") }
    }
}

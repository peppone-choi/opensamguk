package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.logic.world.*
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import kotlin.test.*

/** Exercises the actual boot entry point; JDBC is scripted, not a replacement rehydrator. */
class WorldSnapshotLoaderWaterControlTest {
    private val topology = StrategicTopologySnapshot("r1", emptySet(), listOf(
        WaterZoneRecord("lake", WaterZoneKind.LAKE_BASIN, "geometry", listOf("evidence"),
            EvidenceConfidence.REVIEWED, seasonalAvailability = SeasonalAvailability.ALWAYS),
    ), emptyList(), emptyList(), mapOf("fixture" to "sha"))

    private fun row(overrides: Map<String, Any?> = emptyMap()) = mapOf(
        "water_zone_id" to "lake", "topology_revision" to "r1", "topology_hash" to topology.contentHash,
        "controlling_nation_id" to 3L, "contesting_nation_ids" to "[5,9]",
        "blockade_state" to "CONTESTED", "revision" to 7L,
    ) + overrides

    private fun load(map: String, rows: List<Map<String, Any?>> = emptyList()): Pair<opensamguk.engine.turn.WorldSnapshot, List<Pair<String, List<Any?>>>> {
        val queries = mutableListOf<Pair<String, List<Any?>>>()
        val world = mapOf("id" to 8, "current_year" to 200, "current_month" to 1, "current_phase" to 1,
            "tick_seconds" to 60, "status" to "OPEN", "config" to "{\"mapName\":\"$map\"}", "meta" to "{}")
        val jdbc = Mockito.mock(JdbcTemplate::class.java) { invocation ->
            when (invocation.method.name) {
                "query" -> {
                    val sql = invocation.getArgument<String>(0)
                    val args = invocation.arguments.drop(2).flatMap { if (it is Array<*>) it.toList() else listOf(it) }
                    queries += sql to args
                    val mapper = invocation.arguments.filterIsInstance<RowMapper<*>>().singleOrNull()
                    val source = when {
                        "FROM world_state" in sql -> listOf(world)
                        "FROM water_zone_control" in sql -> rows
                        else -> emptyList()
                    }
                    mapper?.let { source.mapIndexed { i, values -> it.mapRow(resultSet(values), i) } }
                }
                "queryForObject" -> 0
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val snapshot = WorldSnapshotLoader(jdbc, SeedBootstrap(seedEnabled = false, worldId = WorldId(8)),
            WorldId(8), snapshotValidator = {}, waterTopologyLoader = { topology }).buildSnapshot()
        return snapshot to queries
    }

    private fun resultSet(values: Map<String, Any?>): ResultSet = Mockito.mock(ResultSet::class.java) { invocation ->
        val value = values[invocation.arguments.firstOrNull()]
        when (invocation.method.name) {
            "getString" -> value as? String
            "getInt" -> (value as? Number)?.toInt() ?: 0
            "getLong" -> (value as? Number)?.toLong() ?: 0L
            "getObject" -> value
            else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
    }

    @Test fun `V3 cold boot restores control through real loader with canonical world scope`() {
        val (snapshot, queries) = load("han-world-v3", listOf(row()))
        val state = assertNotNull(snapshot.waterControlSnapshot).stateFor("lake")!!
        assertEquals(7L, state.revision)
        assertEquals(listOf(5L, 9L), state.contestingNationIds)
        assertEquals(WaterBlockadeState.CONTESTED, state.blockadeState)
        val (sql, args) = queries.single { "FROM water_zone_control" in it.first }
        assertContains(sql, "WHERE world_id = ?")
        assertEquals(listOf(8), args)
    }

    @Test fun `V3 fresh boot leaves water unknown without seeding ownership`() {
        assertTrue(load("han-world-v3").first.waterControlSnapshot!!.statesByZoneId.isEmpty())
    }

    @Test fun `legacy cold boots never query or acquire V3 water control`() {
        for (map in listOf("che", "han", "han-world-v2")) {
            val (snapshot, queries) = load(map, listOf(row()))
            assertNull(snapshot.waterControlSnapshot)
            assertFalse(queries.any { "water_zone_control" in it.first })
        }
    }

    @Test fun `cold boot rejects unknown zone stale pin and malformed persisted IDs even with map validator disabled`() {
        for (patch in listOf(mapOf("water_zone_id" to "unknown"), mapOf("topology_revision" to "old"),
            mapOf("topology_hash" to "b".repeat(64)), mapOf("contesting_nation_ids" to "[9,5]"))) {
            assertFailsWith<IllegalArgumentException> { load("han-world-v3", listOf(row(patch))) }
        }
    }
}

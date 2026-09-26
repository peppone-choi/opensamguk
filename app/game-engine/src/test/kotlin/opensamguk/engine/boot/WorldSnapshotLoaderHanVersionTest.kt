package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.infra.seed.WorldArtifactsResolver
import opensamguk.infra.seed.WorldTopologyPin
import opensamguk.logic.world.WorldMapVariant
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.nio.file.Path
import java.sql.ResultSet
import kotlin.test.*

class WorldSnapshotLoaderHanVersionTest {
    private val artifacts = WorldArtifactsResolver(Path.of("../.."))

    private fun load(ids: List<Int>, pins: List<WorldTopologyPin> = emptyList()): Pair<opensamguk.engine.turn.WorldSnapshot, List<Pair<String,List<Any?>>>> {
        val queries = mutableListOf<Pair<String,List<Any?>>>()
        val jdbc = Mockito.mock(JdbcTemplate::class.java) { call ->
            when (call.method.name) {
                "query" -> {
                    val sql = call.getArgument<String>(0)
                    val args = call.arguments.drop(2).flatMap { if (it is Array<*>) it.toList() else listOf(it) }
                    queries += sql to args
                    val rows: List<Map<String,Any?>> = when {
                        " AS channel" in sql -> pins.map { mapOf("channel" to it.channel, "topology_revision" to it.revision, "topology_hash" to it.hash) }
                        "FROM world_state" in sql -> listOf(mapOf("id" to 8, "current_year" to 200, "current_month" to 1, "current_phase" to 1,
                            "tick_seconds" to 60, "status" to "OPEN", "config" to "{\"mapName\":\"han-world-v3\",\"worldFormat\":\"GENERAL_RETAINER_CAMPAIGN\"}", "meta" to "{}"))
                        "FROM city WHERE" in sql -> ids.map { mapOf("id" to it, "name" to "renamed-$it", "meta" to "{}") }
                        else -> emptyList()
                    }
                    val mapper = call.arguments.filterIsInstance<RowMapper<*>>().singleOrNull()
                    mapper?.let { rows.mapIndexed { index, row -> it.mapRow(resultSet(row), index) } }
                }
                "queryForObject" -> 0
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            }
        }
        val snapshot = WorldSnapshotLoader(jdbc, SeedBootstrap(seedEnabled = false, worldId = WorldId(8)), WorldId(8),
            waterTopologyLoader = { variant -> artifacts.artifacts(variant).projection.topology },
            mapVariantSelector = { allIds, allPins -> artifacts.resolve(allIds, allPins).variant },
        ).buildSnapshot()
        return snapshot to queries
    }

    private fun resultSet(row: Map<String,Any?>): ResultSet = Mockito.mock(ResultSet::class.java) { call ->
        val value = row[call.arguments.firstOrNull()]
        when (call.method.name) {
            "getString" -> value as? String
            "getInt" -> (value as? Number)?.toInt() ?: 0
            "getLong" -> (value as? Number)?.toLong() ?: 0L
            "getDouble" -> (value as? Number)?.toDouble() ?: 0.0
            "getObject" -> value
            else -> Mockito.RETURNS_DEFAULTS.answer(call)
        }
    }

    @Test fun `boot selects each historical roster and preserves renamed city labels`() {
        for (variant in WorldMapVariant.entries) {
            val ids = artifacts.artifacts(variant).cityConst.all().keys.toList()
            val pins = if (variant == WorldMapVariant.V3_1447_MAP4) {
                val topology = artifacts.artifacts(variant).projection.topology
                listOf(WorldTopologyPin("province_control", topology.topologyRevision, topology.contentHash))
            } else emptyList()
            val (snapshot, queries) = load(ids, pins)
            assertEquals(variant, snapshot.state.worldMapVariant)
            assertEquals("renamed-1", snapshot.cities.first().name)
            assertEquals(artifacts.artifacts(variant).projection.topology.contentHash, snapshot.waterControlSnapshot!!.topologyHash)
            val pinQuery = queries.single { " AS channel" in it.first }
            assertEquals(3, Regex("WHERE world_id = \\?").findAll(pinQuery.first).count())
            assertEquals(listOf(8,8,8), pinQuery.second)
        }
    }

    @Test fun `boot rejects a mismatched pin from any spatial table`() {
        val old = artifacts.artifacts(WorldMapVariant.V3_832)
        val current = artifacts.artifacts(WorldMapVariant.V3_835)
        for (channel in listOf("water_zone_control", "province_control", "general_spatial_position")) {
            assertFailsWith<IllegalArgumentException> {
                load(old.cityConst.all().keys.toList(), listOf(WorldTopologyPin(channel,
                    current.projection.topology.topologyRevision, current.projection.topology.contentHash)))
            }
        }
    }
}

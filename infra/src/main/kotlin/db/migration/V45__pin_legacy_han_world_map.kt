package db.migration

import opensamguk.infra.persistence.MetaJson
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Connection

class V45__pin_legacy_han_world_map : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = context.connection
        loadWorlds(connection).forEach { shape ->
            if (activeMapName(shape.config, shape.meta) != LEGACY_HAN_MAP) return@forEach

            when {
                shape.cityCount == LEGACY_HAN_CITY_COUNT && shape.minCityId == 1 && shape.maxCityId == LEGACY_HAN_CITY_COUNT -> Unit
                shape.cityCount == COMPATIBILITY_HAN_CITY_COUNT && shape.minCityId == 1 && shape.maxCityId == COMPATIBILITY_HAN_CITY_COUNT -> pin(connection, shape)
                else -> throw FlywayException(
                    "V45 cannot classify Han worldId=${shape.id}: " +
                        "cityCount=${shape.cityCount} min=${shape.minCityId} max=${shape.maxCityId}",
                )
            }
        }
    }

    private fun loadWorlds(connection: Connection): List<WorldShape> = connection.prepareStatement(
        """
        SELECT w.id,
               w.config::text AS config,
               w.meta::text AS meta,
               count(c.id) AS city_count,
               min(c.id) AS min_city_id,
               max(c.id) AS max_city_id
          FROM world_state w
          LEFT JOIN city c ON c.world_id = w.id
         GROUP BY w.id, w.config, w.meta
         ORDER BY w.id
        """.trimIndent(),
    ).use { statement ->
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        WorldShape(
                            id = rs.getInt("id"),
                            config = MetaJson.decode(rs.getString("config")),
                            meta = MetaJson.decode(rs.getString("meta")),
                            cityCount = rs.getInt("city_count"),
                            minCityId = rs.getInt("min_city_id").takeUnless { rs.wasNull() },
                            maxCityId = rs.getInt("max_city_id").takeUnless { rs.wasNull() },
                        ),
                    )
                }
            }
        }
    }

    private fun pin(connection: Connection, shape: WorldShape) {
        shape.config["mapName"] = COMPATIBILITY_HAN_MAP
        shape.config["map"] = rewriteMapField(shape.config["map"], COMPATIBILITY_HAN_MAP)
        shape.meta["mapName"] = COMPATIBILITY_HAN_MAP
        shape.meta["map"] = rewriteMapField(shape.meta["map"], COMPATIBILITY_HAN_MAP)
        connection.prepareStatement(
            "UPDATE world_state SET config = ?::jsonb, meta = ?::jsonb WHERE id = ?",
        ).use { statement ->
            statement.setString(1, MetaJson.encode(shape.config))
            statement.setString(2, MetaJson.encode(shape.meta))
            statement.setInt(3, shape.id)
            check(statement.executeUpdate() == 1) { "V45 did not update worldId=${shape.id}" }
        }
    }

    private fun activeMapName(config: Map<String, Any?>, meta: Map<String, Any?>): String? = sequenceOf(
        config["mapName"],
        (config["map"] as? Map<*, *>)?.get("mapName"),
        config["map"],
        meta["mapName"],
        (meta["map"] as? Map<*, *>)?.get("mapName"),
        meta["map"],
    ).filterIsInstance<String>().firstOrNull { it.isNotBlank() }

    private fun rewriteMapField(value: Any?, mapName: String): Any = when (value) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().apply {
            value.forEach { (key, nestedValue) -> put(key.toString(), nestedValue) }
            this["mapName"] = mapName
        }
        else -> mapName
    }

    private data class WorldShape(
        val id: Int,
        val config: MutableMap<String, Any?>,
        val meta: MutableMap<String, Any?>,
        val cityCount: Int,
        val minCityId: Int?,
        val maxCityId: Int?,
    )

    private companion object {
        const val LEGACY_HAN_MAP = "han"
        const val COMPATIBILITY_HAN_MAP = "han-780-v1"
        const val LEGACY_HAN_CITY_COUNT = 774
        const val COMPATIBILITY_HAN_CITY_COUNT = 780
    }
}

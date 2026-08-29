package db.migration

import opensamguk.infra.persistence.MetaJson
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Connection

class V47__activate_han_world_v2 : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = context.connection
        loadWorlds(connection).forEach { world ->
            if (activeMapName(world.config, world.meta) != LEGACY_ALIAS) return@forEach
            if (world.cityCount != CURRENT_CITY_COUNT || world.minCityId != 1 || world.maxCityId != CURRENT_CITY_COUNT) {
                throw FlywayException(
                    "V47 cannot activate Han worldId=${world.id}: " +
                        "cityCount=${world.cityCount} min=${world.minCityId} max=${world.maxCityId}",
                )
            }
            rewrite(world.config)
            rewrite(world.meta)
            connection.prepareStatement(
                "UPDATE world_state SET config = ?::jsonb, meta = ?::jsonb WHERE id = ?",
            ).use { statement ->
                statement.setString(1, MetaJson.encode(world.config))
                statement.setString(2, MetaJson.encode(world.meta))
                statement.setInt(3, world.id)
                check(statement.executeUpdate() == 1) { "V47 did not update worldId=${world.id}" }
            }
        }
    }

    private fun loadWorlds(connection: Connection): List<WorldShape> = connection.prepareStatement(
        """
        SELECT w.id, w.config::text AS config, w.meta::text AS meta,
               count(c.id) AS city_count, min(c.id) AS min_city_id, max(c.id) AS max_city_id
          FROM world_state w LEFT JOIN city c ON c.world_id = w.id
         GROUP BY w.id, w.config, w.meta ORDER BY w.id
        """.trimIndent(),
    ).use { statement ->
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(WorldShape(
                    rs.getInt("id"), MetaJson.decode(rs.getString("config")),
                    MetaJson.decode(rs.getString("meta")), rs.getInt("city_count"),
                    rs.getInt("min_city_id").takeUnless { rs.wasNull() },
                    rs.getInt("max_city_id").takeUnless { rs.wasNull() },
                ))
            }
        }
    }

    private fun rewrite(document: MutableMap<String, Any?>) {
        if (document["mapName"] == LEGACY_ALIAS) document["mapName"] = ACTIVE_MAP
        when (val value = document["map"]) {
            LEGACY_ALIAS -> document["map"] = ACTIVE_MAP
            is Map<*, *> -> if (value["mapName"] == LEGACY_ALIAS) {
                document["map"] = LinkedHashMap<String, Any?>().apply {
                    value.forEach { (key, nested) -> put(key.toString(), nested) }
                    this["mapName"] = ACTIVE_MAP
                }
            }
        }
    }

    private fun activeMapName(config: Map<String, Any?>, meta: Map<String, Any?>): String? = sequenceOf(
        config["mapName"], (config["map"] as? Map<*, *>)?.get("mapName"), config["map"],
        meta["mapName"], (meta["map"] as? Map<*, *>)?.get("mapName"), meta["map"],
    ).filterIsInstance<String>().firstOrNull { it.isNotBlank() }

    private data class WorldShape(
        val id: Int, val config: MutableMap<String, Any?>, val meta: MutableMap<String, Any?>,
        val cityCount: Int, val minCityId: Int?, val maxCityId: Int?,
    )

    private companion object {
        const val LEGACY_ALIAS = "han"
        const val ACTIVE_MAP = "han-world-v2"
        const val CURRENT_CITY_COUNT = 774
    }
}

package opensamguk.infra.read

import opensamguk.common.world.WorldId
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class StatisticSnapshotReader(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun snapshotRows(worldId: WorldId): List<Map<String, Any?>> = jdbc.query(
        """
        WITH picked AS (
            SELECT id FROM (
                SELECT id
                  FROM statistic
                 WHERE world_id = :world_id
                 ORDER BY nation_count DESC, id ASC
                 LIMIT 1
            ) max_nation
            UNION
            SELECT id FROM (
                SELECT id
                  FROM statistic
                 WHERE world_id = :world_id
                 ORDER BY gen_count DESC NULLS LAST, id ASC
                 LIMIT 1
            ) max_general
            UNION
            SELECT id FROM (
                SELECT id
                  FROM statistic
                 WHERE world_id = :world_id
                 ORDER BY id DESC
                 LIMIT 1
            ) latest
        )
        SELECT id, nation_count, nation_name, nation_hist, gen_count,
               personal_hist, special_hist, CAST(aux AS VARCHAR) AS aux
          FROM statistic
         WHERE world_id = :world_id
           AND id IN (SELECT id FROM picked)
         ORDER BY id ASC
        """.trimIndent(),
        MapSqlParameterSource().addValue("world_id", worldId.value),
    ) { rs, _ ->
        linkedMapOf(
            "id" to rs.getInt("id"),
            "nation_count" to rs.getInt("nation_count"),
            "nation_name" to rs.getString("nation_name"),
            "nation_hist" to rs.getString("nation_hist"),
            "gen_count" to rs.getString("gen_count"),
            "personal_hist" to rs.getString("personal_hist"),
            "special_hist" to rs.getString("special_hist"),
            "aux" to (rs.getString("aux") ?: "{}"),
        )
    }
}

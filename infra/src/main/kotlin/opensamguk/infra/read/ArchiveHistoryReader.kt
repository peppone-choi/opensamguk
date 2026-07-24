package opensamguk.infra.read

import opensamguk.common.world.WorldId
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class ArchiveHistoryReader(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun nationHistory(worldId: WorldId, nationId: Int): List<String> = jdbc.query(
        """
        SELECT text
          FROM log_entry
         WHERE world_id = :world_id
           AND scope = 'NATION'
           AND category = 'HISTORY'
           AND nation_id = :nation_id
         ORDER BY id DESC
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("world_id", worldId.value)
            .addValue("nation_id", nationId),
    ) { rs, _ -> rs.getString("text") }

    fun generalHistory(worldId: WorldId, generalId: Int): List<String> = jdbc.query(
        """
        SELECT text
          FROM log_entry
         WHERE world_id = :world_id
           AND scope = 'GENERAL'
           AND category = 'HISTORY'
           AND general_id = :general_id
         ORDER BY id DESC
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("world_id", worldId.value)
            .addValue("general_id", generalId),
    ) { rs, _ -> rs.getString("text") }

    fun globalLogs(worldId: WorldId, category: String, year: Int, month: Int): List<String> = jdbc.query(
        """
        SELECT text
          FROM log_entry
         WHERE world_id = :world_id
           AND scope = 'SYSTEM'
           AND category = CAST(:category AS log_category)
           AND year = :year
           AND month = :month
         ORDER BY id DESC
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("world_id", worldId.value)
            .addValue("category", category.uppercase())
            .addValue("year", year)
            .addValue("month", month),
    ) { rs, _ -> rs.getString("text") }
}

package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.infra.persistence.ReservedTurnRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

internal class FullRehydratePersistenceSignatures(
    private val jdbc: JdbcTemplate,
    private val namedJdbc: NamedParameterJdbcTemplate,
    private val config: FullRehydrateFixtureConfig,
    private val poisonWorldId: WorldId,
) {
    fun persistedClock(worldId: WorldId): String = jdbc.queryForObject(
        "SELECT meta ->> 'lastTurnTime' FROM world_state WHERE id = ?",
        String::class.java,
        worldId.value,
    )!!

    fun persistedState(worldId: WorldId): List<String> = jdbc.query(
        """
        SELECT current_year, current_month, current_phase, world_version, writer_epoch,
               meta ->> 'lastTurnTime' AS last_turn_time
          FROM world_state
         WHERE id = ?
        """.trimIndent(),
        { rs, _ ->
            listOf(
                rs.getInt("current_year").toString(),
                rs.getInt("current_month").toString(),
                rs.getInt("current_phase").toString(),
                rs.getLong("world_version").toString(),
                rs.getLong("writer_epoch").toString(),
                rs.getString("last_turn_time"),
            )
        },
        worldId.value,
    ).single()

    fun rank(worldId: WorldId): List<String> = jdbc.query(
        """
        SELECT general_id, nation_id, type, value
          FROM rank_data
         WHERE world_id = ?
         ORDER BY general_id, type, id
        """.trimIndent(),
        { rs, _ -> "${rs.getInt("general_id")}:${rs.getInt("nation_id")}:${rs.getString("type")}:${rs.getInt("value")}" },
        worldId.value,
    )

    fun reservedTurns(worldId: WorldId): List<String> {
        val turns = ReservedTurnRepository(namedJdbc)
        return (0..2).map { index ->
            turns.readReserved(worldId, config.generalId, index).let { turn ->
                "$index:${turn.actionCode}:${turn.argJson}:${turn.brief}"
            }
        }
    }

    fun queuedRequestId(worldId: WorldId): String? =
        ReservedTurnRepository(namedJdbc).readReserved(worldId, config.generalId, 0).requestId

    fun commandResults(worldId: WorldId): List<String> = jdbc.query(
        """
        SELECT result_seq, terminal_status, result_type, ok, committed_world_version,
               (result_payload - 'requestId' - 'sentAt')::text AS normalized_payload
          FROM command_result
         WHERE world_id = ?
         ORDER BY request_id, result_seq
        """.trimIndent(),
        { rs, _ ->
            "${rs.getInt("result_seq")}:${rs.getString("terminal_status")}:${rs.getString("result_type")}:" +
                "${rs.getBoolean("ok")}:${rs.getLong("committed_world_version")}:${rs.getString("normalized_payload")}"
        },
        worldId.value,
    )

    fun commandOutbox(worldId: WorldId): List<String> = jdbc.query(
        """
        SELECT event_type, payload_schema_version, (payload - 'requestId' - 'sentAt')::text AS normalized_payload
          FROM command_outbox
         WHERE world_id = ?
         ORDER BY request_id, event_id
        """.trimIndent(),
        { rs, _ -> "${rs.getString("event_type")}:${rs.getInt("payload_schema_version")}:${rs.getString("normalized_payload")}" },
        worldId.value,
    )

    fun commandResultRequestIds(worldId: WorldId): List<String> = jdbc.queryForList(
        "SELECT request_id FROM command_result WHERE world_id = ? ORDER BY request_id",
        String::class.java,
        worldId.value,
    )

    fun commandOutboxRequestIds(worldId: WorldId): List<String> = jdbc.queryForList(
        "SELECT request_id FROM command_outbox WHERE world_id = ? ORDER BY request_id",
        String::class.java,
        worldId.value,
    )

    fun koreanLogHex(worldId: WorldId): List<String> = jdbc.query(
        """
        SELECT encode(convert_to(text, 'UTF8'), 'hex')
          FROM log_entry
         WHERE world_id = ?
         ORDER BY id
        """.trimIndent(),
        { rs, _ -> rs.getString(1) },
        worldId.value,
    )

    fun poisonWorld(): List<String> = listOf(
        jdbc.queryForObject(
            "SELECT meta ->> 'lastTurnTime' FROM world_state WHERE id = ?",
            String::class.java,
            poisonWorldId.value,
        ) ?: "<no-clock>",
        jdbc.queryForObject(
            """
            SELECT concat_ws('|', name, gold::text, rice::text, experience::text, dedication::text,
                             turn_time::text, meta::text)
              FROM general
             WHERE world_id = ? AND id = ?
            """.trimIndent(),
            String::class.java,
            poisonWorldId.value,
            config.generalId,
        )!!,
        jdbc.queryForObject(
            """
            SELECT concat_ws('|', name, agri::text, comm::text, trust::text, meta::text)
              FROM city
             WHERE world_id = ? AND id = ?
            """.trimIndent(),
            String::class.java,
            poisonWorldId.value,
            config.cityId,
        )!!,
        jdbc.queryForObject(
            """
            SELECT concat_ws('|', name, gold::text, rice::text, tech::text, power::text, meta::text)
              FROM nation
             WHERE world_id = ? AND id = ?
            """.trimIndent(),
            String::class.java,
            poisonWorldId.value,
            config.nationId,
        )!!,
        jdbc.queryForObject(
            "SELECT name FROM troop WHERE world_id = ? AND troop_leader = ?",
            String::class.java,
            poisonWorldId.value,
            config.generalId,
        )!!,
        jdbc.queryForObject(
            "SELECT value FROM rank_data WHERE world_id = ? AND general_id = ? AND type = 'kill'",
            Int::class.java,
            poisonWorldId.value,
            config.generalId,
        )!!.toString(),
        jdbc.queryForObject(
            "SELECT state_code::text || ':' || term::text FROM diplomacy WHERE world_id = ? AND src_nation_id = ? AND dest_nation_id = ?",
            String::class.java,
            poisonWorldId.value,
            config.nationId,
            config.secondNationId,
        )!!,
        jdbc.queryForObject(
            "SELECT refresh_score_total FROM general_access_log WHERE world_id = ? AND general_id = ?",
            Int::class.java,
            poisonWorldId.value,
            config.generalId,
        )!!.toString(),
        count("SELECT count(*) FROM general_turn WHERE world_id = ?"),
        count("SELECT count(*) FROM command_result WHERE world_id = ?"),
        count("SELECT count(*) FROM command_outbox WHERE world_id = ?"),
        count("SELECT count(*) FROM log_entry WHERE world_id = ?"),
    )

    private fun count(query: String): String = jdbc.queryForObject(
        query,
        Int::class.java,
        poisonWorldId.value,
    )!!.toString()
}

package opensamguk.infra.read

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** 계정이 가진 플레이어 장수 한 명(ADR-LITE-049 13 대표 장수 후보). scenarioCode 는 world_state 에서 읽는다(없으면 null). */
data class OwnedGeneralRow(
    val id: Int,
    val name: String,
    val worldId: Int,
    val scenarioCode: String?,
)

/**
 * `general.user_id`(계정 id 문자열) 로 계정 소유 장수를 읽는다 — JDBC 전용(엔티티 없음: game-api 의 general 매핑과 겹치지 않게).
 * NPC(npc_state >= 2)는 제외한다.
 */
class OwnedGeneralReader(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun findByUserId(userId: Long): List<OwnedGeneralRow> = jdbc.query(
        """
        SELECT g.id, g.name, g.world_id, w.scenario_code
          FROM general g
          LEFT JOIN world_state w ON w.id = g.world_id
         WHERE g.user_id = :userId AND g.npc_state < 2
         ORDER BY g.world_id DESC, g.id ASC
        """.trimIndent(),
        MapSqlParameterSource().addValue("userId", userId.toString()),
    ) { rs, _ ->
        OwnedGeneralRow(
            id = rs.getInt("id"),
            name = rs.getString("name"),
            worldId = rs.getInt("world_id"),
            scenarioCode = rs.getString("scenario_code"),
        )
    }
}

package opensamguk.gameapi.read

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.persistence.MetaJson
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/** V61 `hwiha_siege` 한 행(읽기 전용). 쓰기는 엔진 flush 뿐이다. */
data class HwihaSiegeReadRow(
    val countyId: Int,
    val status: String,
    val besiegerGeneralId: Int,
    val besiegerOwnerGeneralId: Int,
    val besiegerOrderId: String,
    val besiegerNationId: Int,
    val defenderNationId: Int,
    val startedYear: Int,
    val startedMonth: Int,
    val startedPhase: Int,
    val turns: Int,
    val morale: Int,
    val garrison: Int,
    val endReason: String?,
    val timeline: List<Map<String, Any?>>,
)

/** process-world 범위. 관여한 포위만 돌려준다 — 포위 장수 본인, 또는 포위·수비 세력이 [nationId] 인 행. */
@Repository
class HwihaSiegeReadRepository(private val jdbc: NamedParameterJdbcTemplate, processWorld: GameApiProcessWorld) {
    private val worldId = processWorld.worldId.value

    fun involving(generalId: Int, nationId: Int): List<HwihaSiegeReadRow> = jdbc.query(
        """
        SELECT county_id, status, besieger_general_id, besieger_owner_general_id, besieger_order_id, besieger_nation_id,
               defender_nation_id, started_year, started_month, started_phase, turns, morale, garrison, end_reason,
               timeline::text AS timeline
          FROM hwiha_siege
         WHERE world_id = :world
           AND (besieger_general_id = :general OR (:nation > 0 AND (besieger_nation_id = :nation OR defender_nation_id = :nation)))
         ORDER BY (status = 'ACTIVE') DESC, county_id
        """.trimIndent(),
        mapOf("world" to worldId, "general" to generalId, "nation" to nationId),
    ) { rs, _ ->
        @Suppress("UNCHECKED_CAST")
        val timeline = MetaJson.decode("{\"timeline\":${rs.getString("timeline")}}")["timeline"] as? List<Map<String, Any?>> ?: emptyList()
        HwihaSiegeReadRow(rs.getInt("county_id"), rs.getString("status"), rs.getInt("besieger_general_id"),
            rs.getInt("besieger_owner_general_id"), rs.getString("besieger_order_id"), rs.getInt("besieger_nation_id"),
            rs.getInt("defender_nation_id"), rs.getInt("started_year"), rs.getInt("started_month"), rs.getInt("started_phase"),
            rs.getInt("turns"), rs.getInt("morale"), rs.getInt("garrison"), rs.getString("end_reason"), timeline)
    }
}

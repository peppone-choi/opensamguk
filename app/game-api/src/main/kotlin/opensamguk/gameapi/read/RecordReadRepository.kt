package opensamguk.gameapi.read

import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/** 휘하 기록 한 줄(`log_entry` 중 `event_kind` 가 있는 줄). [refsJson] 은 `meta.refs` 의 JSON 원문이다. */
data class RecordRow(
    val id: Long,
    val year: Int,
    val month: Int,
    val phase: Int,
    val kind: String,
    val text: String,
    val refsJson: String?,
)

/** 연·월·순 한 칸. 1년 36순. */
data class TurnStamp(val year: Int, val month: Int, val phase: Int) {
    init { require(month in 1..12 && phase in 1..3) { "invalid turn $year-$month-$phase" } }
    val ordinal: Int get() = year * 36 + (month - 1) * 3 + phase - 1

    companion object {
        fun ofOrdinal(ordinal: Int): TurnStamp = TurnStamp(ordinal / 36, (ordinal % 36) / 3 + 1, ordinal % 3 + 1)
    }
}

/**
 * 「지난 순」 읽기 — 처리 월드 범위(OPENSAM-127), 읽기만 한다(쓰기는 데몬 ChangeRecorder 경로뿐).
 *
 * 두 질의 모두 `event_kind IS NOT NULL` 부분 인덱스(V60)를 탄다. 기존 로그(종류 없음)는 여기 나오지 않는다 —
 * 기존 개인 기록 피드가 그대로 보여 준다.
 */
@Repository
class RecordReadRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    processWorld: GameApiProcessWorld,
) {
    private val worldId = processWorld.worldId.value

    /** 본인 앞 개인 기록(`scope=GENERAL`, `general_id` = 본인)만. 연·월·순, 같은 순 안은 기록 순. */
    fun personal(generalId: Int, from: TurnStamp, to: TurnStamp): List<RecordRow> = jdbc.query(
        """
        SELECT id, year, month, phase, event_kind, text, meta -> 'refs' AS refs FROM log_entry
        WHERE world_id = :world AND general_id = :general AND scope = 'GENERAL' AND event_kind IS NOT NULL
          AND (year, month, phase) >= (:fy, :fm, :fp) AND (year, month, phase) <= (:ty, :tm, :tp)
        ORDER BY year, month, phase, id
        """.trimIndent(),
        window(from, to) + mapOf("general" to generalId),
        ROW,
    )

    /**
     * 세력 요약 — 본인 세력 앞 공개 사건([nationKinds], `scope=NATION`)과 세계 공개 사건([worldKinds],
     * `scope=SYSTEM`). [nationId] 가 0(재야)이면 세계 공개 사건만.
     */
    fun summary(
        nationId: Int,
        nationKinds: Collection<String>,
        worldKinds: Collection<String>,
        from: TurnStamp,
        to: TurnStamp,
    ): List<RecordRow> = jdbc.query(
        """
        SELECT id, year, month, phase, event_kind, text, meta -> 'refs' AS refs FROM log_entry
        WHERE world_id = :world AND event_kind IS NOT NULL
          AND ((scope = 'NATION' AND nation_id = :nation AND :nation <> 0 AND event_kind IN (:nationKinds))
            OR (scope = 'SYSTEM' AND nation_id IS NULL AND event_kind IN (:worldKinds)))
          AND (year, month, phase) >= (:fy, :fm, :fp) AND (year, month, phase) <= (:ty, :tm, :tp)
        ORDER BY year, month, phase, id
        """.trimIndent(),
        window(from, to) + mapOf("nation" to nationId, "nationKinds" to nationKinds.toList(),
            "worldKinds" to worldKinds.toList()),
        ROW,
    )

    private fun window(from: TurnStamp, to: TurnStamp): Map<String, Any> = mapOf(
        "world" to worldId,
        "fy" to from.year, "fm" to from.month, "fp" to from.phase,
        "ty" to to.year, "tm" to to.month, "tp" to to.phase,
    )

    private companion object {
        val ROW = RowMapper { rs, _ ->
            RecordRow(rs.getLong("id"), rs.getInt("year"), rs.getInt("month"), rs.getInt("phase"),
                rs.getString("event_kind"), rs.getString("text"), rs.getString("refs"))
        }
    }
}

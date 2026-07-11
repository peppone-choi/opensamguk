package opensamguk.infra.read

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime

/**
 * 장수 선택 풀 테이블용 JDBC read seam (W6f 장수 선택 풀, RNG-BEARING).
 *
 * 데몬 [opensamguk.engine.intake.SelectPoolHandler]의 pick/update owner+expiry read와
 * game-api 후보 카드 조회만 담당하는 plain JDBC read seam. 모든 write는
 * `ChangeRecorder -> JdbcFlushExecutor`로만 흐른다.
 */
open class SelectPoolRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {

    /**
     * uniqueName으로 선택 풀 항목 한 개를 read 한다 (PHP 풀 항목 존재/소유 가드).
     *
     * @param uniqueName 풀 항목 고유명.
     * @return 풀 항목 DTO, 또는 없으면 null ('유효한 장수 목록이 없습니다.').
     */
    open fun findPoolEntry(uniqueName: String, ownerUserId: Int, now: Instant): SelectPoolReadRow? =
        jdbc.query(
            """
            SELECT unique_name, owner, reserved_until, info, general_id,
                   COALESCE(
                       (SELECT config -> 'map' -> 'generalPoolAllowOption'
                          FROM world_state WHERE id = 1) @> '["stat"]'::jsonb,
                       TRUE
                   ) AS stat_editable
              FROM select_pool
             WHERE unique_name = :unique_name
               AND owner = :owner
               AND reserved_until >= :now
             LIMIT 1
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("unique_name", uniqueName)
                .addValue("owner", ownerUserId)
                .addValue("now", Timestamp.from(now)),
        ) { rs, _ ->
            SelectPoolReadRow(
                uniqueName = rs.getString("unique_name"),
                ownerUserId = rs.getInt("owner"),
                statEditable = rs.getBoolean("stat_editable"),
                generalId = rs.getInt("general_id").let { if (rs.wasNull()) null else it },
                reservedUntil = rs.getObject("reserved_until", OffsetDateTime::class.java)?.toInstant(),
                info = opensamguk.infra.persistence.MetaJson.decode(rs.getString("info")),
            )
        }.firstOrNull()

    /**
     * 한 유저(소유자)가 가진 선택 풀 항목 목록을 read 한다 (등록 한도/중복 가드용).
     *
     * @param ownerUserId 소유자 user id.
     * @return 풀 항목 DTO 목록, 비어 있으면 emptyList.
     */
    open fun listForUser(ownerUserId: Int, now: Instant): List<SelectPoolReadRow> =
        jdbc.query(
            """
            SELECT unique_name, owner, reserved_until, info, general_id,
                   COALESCE(
                       (SELECT config -> 'map' -> 'generalPoolAllowOption'
                          FROM world_state WHERE id = 1) @> '["stat"]'::jsonb,
                       TRUE
                   ) AS stat_editable
              FROM select_pool
             WHERE owner = :owner
               AND reserved_until >= :now
             ORDER BY unique_name ASC
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("owner", ownerUserId)
                .addValue("now", Timestamp.from(now)),
        ) { rs, _ ->
            SelectPoolReadRow(
                uniqueName = rs.getString("unique_name"),
                ownerUserId = rs.getInt("owner"),
                statEditable = rs.getBoolean("stat_editable"),
                generalId = rs.getInt("general_id").let { if (rs.wasNull()) null else it },
                reservedUntil = rs.getObject("reserved_until", OffsetDateTime::class.java)?.toInstant(),
                info = opensamguk.infra.persistence.MetaJson.decode(rs.getString("info")),
            )
        }

    open fun listUniqueNames(): Set<String> =
        jdbc.queryForList("SELECT unique_name FROM select_pool", emptyMap<String, Any?>(), String::class.java).toSet()

    open fun targetGeneralPool(): String =
        jdbc.queryForObject(
            "SELECT COALESCE(config -> 'map' ->> 'targetGeneralPool', 'RandomNameGeneral') FROM world_state WHERE id = 1",
            emptyMap<String, Any?>(),
            String::class.java,
        ) ?: "RandomNameGeneral"
}

/**
 * 선택 풀 한 항목의 게이트 read 서브셋 (W6f). 엔진 핸들러가 pick/update 게이트를 충족시킨다.
 * infra-local DTO — 엔진 의존 사이클 회피를 위해 엔진 측 모델과 독립 미러링 ([VotePollReadRow] 패턴).
 * 필드는 W6f 슬라이스 구현 시 확장될 수 있다.
 */
data class SelectPoolReadRow(
    /** 풀 항목 고유명 (uniqueName). */
    val uniqueName: String,
    /** 소유자 user id. */
    val ownerUserId: Int,
    /** 스탯 편집 가능 여부 (stat-editable). */
    val statEditable: Boolean,
    /** 이미 생성된 general_id (음수 마킹 포함). 미생성이면 null. */
    val generalId: Int? = null,
    val reservedUntil: Instant? = null,
    val info: Map<String, Any?> = emptyMap(),
)

package opensamguk.infra.read

import opensamguk.common.world.WorldId
import opensamguk.logic.util.jsonDecode
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * `diplomacy_letter`(외교 서신) 테이블용 JDBC read seam (W5d 외교 서신).
 *
 * 데몬 [opensamguk.engine.intake.DiplomacyLetterHandler]의 send/rollback/destroy 게이트는 PHP
 * `j_diplomacy_send_letter.php` / `_rollback_letter.php` / `_destroy_letter.php` 의 가드를 충실히
 * 재현하려면 서신 행을 read 해야 한다 (`SELECT ng_diplomacy WHERE no = …`, prev_no newer-count 조회).
 *
 * **주의 — 명칭.** 기존 [DiplomacyRepository]는 `diplomacy`(국가 외교 상태) 테이블용 JPA read 빈으로
 * P7 read API가 쓴다(다른 테이블·다른 관심사). 이 seam은 `diplomacy_letter` **서신** 테이블 전용이라
 * 충돌 회피를 위해 `DiplomacyLetterRepository`로 분리한다. [VotePollRepository]와 동일한 plain JDBC
 * 클래스(스프링 빈 아님) — 데몬 디스패처가 nullable-주입하며, null이면 핸들러가 stub-empty로 동작한다.
 *
 * **state 표기.** PHP `ng_diplomacy.state`는 소문자 'proposed/activated/cancelled/replaced'를 쓰지만
 * opensamguk `diplomacy_letter.state`는 enum 대문자 'PROPOSED/ACTIVATED/CANCELLED/REPLACED'다. read seam은
 * 핸들러가 PHP 그대로 게이트하도록 **소문자**로 변환해 반환한다(핸들러는 PHP 문자열로만 비교, write 시점에
 * executor가 다시 대문자 enum 캐스트). 이 변환이 PHP↔opensamguk enum 케이싱 차이를 read 경계에서 흡수한다.
 *
 * READ 전용 — write 경로(diplomacy_letter INSERT/UPDATE)는
 * [opensamguk.infra.persistence.JdbcFlushExecutor] step-8f를 거치며 절대 여기서 쓰지 않는다(one-daemon-write 규칙).
 */
open class DiplomacyLetterRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val worldId: WorldId,
) {

    /**
     * 서신 번호로 한 행을 read 한다 (PHP `SELECT ng_diplomacy WHERE no = letterNo`). 나라-멤버십/상태
     * 게이트는 핸들러가 반환된 src/dest/state로 판정한다(repo는 행만 충실히 돌려준다). `aux`는 raw json
     * String 그대로 — 핸들러가 디코드해 `reason`/`state_opt`를 병합한 뒤 다시 인코드해 UPDATE에 싣는다
     * (LinkedHashMap 삽입 순서 보존). `state`/`stateOpt`는 소문자 PHP 표기로 정규화된다.
     *
     * @param letterNo  diplomacy_letter 행 번호.
     * @return 서신 행 DTO, 또는 없으면 null ('서신이 없습니다.').
     */
    open fun findLetter(letterNo: Int): DiplomacyLetterReadRow? {
        val rows = jdbc.query(
            """
            SELECT id, src_nation_id, dest_nation_id, prev_id, state, src_signer, aux::text AS aux_text
              FROM diplomacy_letter
             WHERE world_id = :world_id
               AND id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("id", letterNo),
        ) { rs, _ ->
            val auxText = rs.getString("aux_text") ?: "{}"
            DiplomacyLetterReadRow(
                letterNo = rs.getInt("id"),
                srcNationId = rs.getInt("src_nation_id"),
                destNationId = rs.getInt("dest_nation_id"),
                prevNo = rs.getObject("prev_id") as? Int,
                // enum 대문자 → PHP 소문자. 핸들러는 'proposed'/'activated' 등 PHP 문자열로 게이트한다.
                state = rs.getString("state")?.lowercase() ?: "",
                stateOpt = extractStateOpt(auxText),
                auxJson = auxText,
            )
        }
        return rows.firstOrNull()
    }

    /**
     * `prev_id = prevNo AND state != 'cancelled'` 인 서신 수 (PHP send_letter `$newerLetter` 게이트
     * — `SELECT count(no) FROM ng_diplomacy WHERE prev_no = %i AND state != 'cancelled'`). >0이면
     * '해당 문서에 대한 새로운 문서가 이미 있습니다.'.
     *
     * @param prevNo  직전 문서 번호.
     * @return cancelled가 아닌 후속 문서 수.
     */
    open fun countNewerLetters(prevNo: Int): Int {
        val cnt = jdbc.queryForObject(
            """
            SELECT count(id) FROM diplomacy_letter
             WHERE world_id = :world_id
               AND prev_id = :prev_no
               AND state != 'CANCELLED'
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("prev_no", prevNo),
            Int::class.java,
        )
        return cnt ?: 0
    }

    /** aux json에서 `state_opt`만 추출(없으면 null) — byte-faithful [jsonDecode]로 파싱(삽입 순서 무관). */
    private fun extractStateOpt(auxJson: String): String? =
        jsonDecode(auxJson)["state_opt"] as? String
}

/**
 * `diplomacy_letter` 한 행의 게이트 read 서브셋 (W5d). 엔진 핸들러가 send/rollback/destroy 게이트를
 * 충족시킨다. infra-local DTO — 엔진 의존 사이클을 피하기 위해 엔진 측 모델과 독립 미러링
 * ([VotePollReadRow] 패턴). `state`/`stateOpt`는 소문자 PHP 표기로 정규화된 값이다.
 */
data class DiplomacyLetterReadRow(
    /** diplomacy_letter.id (서신 번호). */
    val letterNo: Int,
    /** 발신 국가 id (src_nation_id). */
    val srcNationId: Int,
    /** 수신 국가 id (dest_nation_id). */
    val destNationId: Int,
    /** 직전 문서 번호(체인, prev_id). 없으면 null. */
    val prevNo: Int? = null,
    /** 서신 상태 (소문자 PHP 표기: proposed/activated/cancelled/replaced). */
    val state: String,
    /** 파기 2단계 상태 옵션 (try_destroy_src / try_destroy_dest). 없으면 null. */
    val stateOpt: String? = null,
    /** aux jsonb의 raw json String — 핸들러가 reason/state_opt 병합 후 재인코드해 UPDATE에 싣는다. */
    val auxJson: String = "{}",
)

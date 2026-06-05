package opensamguk.infra.read

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 장수 선택 풀 테이블용 JDBC read seam (W6f 장수 선택 풀, RNG-BEARING).
 *
 * 데몬 [opensamguk.engine.intake.SelectPoolHandler]의 pick/update 게이트는 PHP `j_pick_general.php` /
 * `j_update_picked_general.php` 가드를 충실히 재현하려면 풀 항목을 read 해야 한다 (풀 항목 존재/소유,
 * stat-editable 여부, next_change 쿨다운). 가중 추첨(`allStat^1.5`)은 RNG-BEARING이라 골든 게이트는
 * /parity-wave로 이관 — 이 seam은 deny-게이트 read만 담당한다.
 *
 * [VotePollRepository]와 동일한 plain JDBC 클래스(스프링 빈 아님) — 데몬 디스패처가 nullable-주입하며,
 * null이면 핸들러가 stub-empty로 동작한다. READ 전용 — write 경로는
 * [opensamguk.infra.persistence.JdbcFlushExecutor]를 거치며 절대 여기서 쓰지 않는다(one-daemon-write 규칙).
 *
 * NOTE: 본문은 W6f 슬라이스에서 채운다(현 단계는 read-seam 시그니처 + DTO만 고정). 미구현 기본 구현은
 * 빈 결과(null/empty)를 반환한다 — 핸들러 deny-only 스텁과 정합.
 */
class SelectPoolRepository(
    @Suppress("unused") private val jdbc: NamedParameterJdbcTemplate,
) {

    /**
     * uniqueName으로 선택 풀 항목 한 개를 read 한다 (PHP 풀 항목 존재/소유 가드).
     *
     * @param uniqueName 풀 항목 고유명.
     * @return 풀 항목 DTO, 또는 없으면 null ('유효한 장수 목록이 없습니다.').
     */
    fun findPoolEntry(uniqueName: String): SelectPoolReadRow? {
        // W6f 슬라이스에서 실제 SELECT를 채운다. 현 단계 스텁은 '항목 없음'.
        return null
    }

    /**
     * 한 유저(소유자)가 가진 선택 풀 항목 목록을 read 한다 (등록 한도/중복 가드용).
     *
     * @param ownerUserId 소유자 user id.
     * @return 풀 항목 DTO 목록, 비어 있으면 emptyList.
     */
    fun listForUser(ownerUserId: Int): List<SelectPoolReadRow> {
        // W6f 슬라이스에서 실제 SELECT를 채운다. 현 단계 스텁은 빈 목록.
        return emptyList()
    }
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
)

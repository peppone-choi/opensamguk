package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 전황(제 전황) 피드용 READ-only JPA 매핑 — `log_entry`의 월드 스코프 history/summary 행.
 *
 * devsam 입구 '제 전황' = 월드 전체에 방송되는 글로벌 이력(정복/멸망/건국/작위/봉록 등). 엔진이
 * `pushGlobalHistoryLog`(scope SYSTEM, category HISTORY) · `pushGlobalActionLog`(SYSTEM/SUMMARY)로
 * 기록한 행이 그 원천. 신선 시드면 0행 → 빈 목록(절대 500, 무fabricate). game-api ONLY(§7), 미기록.
 *
 * `scope`/`category`는 Postgres enum 타입 → 네이티브 쿼리. 비교는 enum-네이티브(`scope = 'SYSTEM'`,
 * 미지정 리터럴이 enum으로 강제됨)여야 `(scope, category, id)` 인덱스를 탄다 — 컬럼을 `::text`로
 * 캐스트하면 인덱스가 무력화되어 log_entry 전체 seq scan이 된다 (OPENSAM-14 실측).
 * `text`는 패러티 로그 원문(devsam 색/태그 마크업 포함) 그대로 — 표시용 가공은 프론트 소관.
 */
@Entity
@Table(name = "log_entry")
class WorldLogReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "year")
    var year: Int = 0,

    @Column(name = "month")
    var month: Int = 0,

    @Column(name = "phase")
    var phase: Int = 1,

    @Column(name = "text")
    var text: String = "",
)

interface WorldLogReadRepository : JpaRepository<WorldLogReadEntity, Int> {
    /** 월드 전황 최신 N건(newest first) — SYSTEM 스코프의 history/summary. */
    @Query(
        value = """
            SELECT id, year, month, phase, text FROM log_entry
            WHERE scope = 'SYSTEM' AND category IN ('HISTORY', 'SUMMARY')
            ORDER BY id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findRecentWorldLog(@Param("limit") limit: Int): List<WorldLogReadEntity>
}

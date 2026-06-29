package opensamguk.gameapi.read

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * W0-5 — `log_entry` 공용 카테고리-피드 READ 파운데이션 (글로벌 + 개인 증분 피드).
 *
 * PHP 정본 readers (func_history.php / GetFrontInfo.php) ↔ 메서드 매핑:
 *  - `getGlobalHistoryLogRecent`  (func_history.php:322-326) → [findRecentGlobalHistory]
 *  - `getGlobalHistoryLogWithDate`(func_history.php:328-341) → [findGlobalHistoryByMonth]
 *  - `getGlobalActionLogRecent`   (func_history.php:360-367) → [findRecentGlobalAction]
 *  - `getGlobalActionLogWithDate` (func_history.php:369-382) → [findGlobalActionByMonth]
 *  - `GetFrontInfo::getHistory`       (GetFrontInfo.php:92-103) → [findGlobalHistorySince]
 *  - `GetFrontInfo::getGlobalRecord`  (GetFrontInfo.php:65-76)  → [findGlobalActionSince]
 *  - `GetFrontInfo::getGeneralRecord` (GetFrontInfo.php:78-90)  → [findGeneralActionSince]
 *  - `getAuctionLogRecent`        (func_history.php:93-95)   → [findRecentByScopeAndCategory] (범용)
 *
 * scope/category 매핑 정본은 writer 2계열(둘 다 실재 — 합집합 read가 행 손실을 막는다):
 *  - common `ActionLogger.kt:16-17`: pushGlobalHistoryLog → SYSTEM/HISTORY,
 *    pushGlobalActionLog → SYSTEM/SUMMARY (PHP world_history nation_id=0 /
 *    general_record general_id=0 log_type='history'의 각각의 대응).
 *  - engine `WorldActionContext.kt:571-573`: pushGlobalActionLog → scope "global"/category "action"
 *    → `DatabaseHooks.scopeLiteral`이 SYSTEM/ACTION으로 영속화. 같은 논리 스트림(글로벌 액션)이
 *    SUMMARY/ACTION 두 카테고리로 갈라져 있으므로 글로벌 액션 read는 IN ('SUMMARY','ACTION').
 *
 * 소비처(전부 W1 소관 — 이 파운데이션은 쿼리만 제공):
 *  - P0-03 메인 RecordZone 3피드: `id >= lastID ORDER BY id DESC LIMIT (ROW_LIMIT+1=16)`
 *    (GetFrontInfo.php:44 `ROW_LIMIT=15`; `+1` 행으로 flush 플래그/pop 판정 — 그 로직은 소비자 몫).
 *    ※ PHP는 `id >= %i`(경계 포함, GT 아님 GTE) — Since 메서드들이 그대로 따른다.
 *  - P0-21 history 페이지: WithDate 변형(연/월 필터, LIMIT 없음). 0행일 때 "<C>●</>{y}년 {m}월: 기록 없음"
 *    폴백 문자열 합성은 소비자 소관(무fabricate).
 *  - P1-009 경매 recentLogs: legacy는 파일 테일(`getAuctionLogRecent(20)` = 최신 20건을 읽어
 *    `array_reverse`로 오래된순 표시). opensamguk 엔진은 log_entry로 기록하나 현재 auction/betting
 *    핸들러가 scope "action"/category "auction"이라는 enum에 없는 리터럴을 쓰는 P6 flush 버그가
 *    미해결(DatabaseHooks.kt scopeLiteral NOTE) → 정본 리터럴이 확정되면 호출부가
 *    [findRecentByScopeAndCategory]에 그 값을 넘긴다(레포는 ::text 비교라 값-불가지론적).
 *  - P1-059 map 히스토리 10건: `GetCachedMap.php:84` `getGlobalHistoryLogRecent(10)` →
 *    [findRecentGlobalHistory] (HISTORY 단독 — [WorldLogReadRepository]의 HISTORY+SUMMARY 혼합과 다름).
 *
 * `scope`/`category`는 Postgres enum 타입이라 비교 시 `::text` 캐스트가 필요 → 네이티브 쿼리
 * ([WorldLogReadRepository]/[AdminGeneralLogReadRepository] 선례 동형). 결과 컬럼(id/year/month/phase/text)이
 * [WorldLogReadEntity]와 1:1이라 그 엔티티를 read-only 프로젝션으로 재사용한다. id는 RecordZone 증분
 * lastID 북키핑에 필수라 함께 반환한다(GetFrontInfo도 `SELECT id, text`).
 *
 * game-api ONLY(§7); 절대 write하지 않는다(데몬 write는 ChangeRecorder→JdbcFlushExecutor 전용).
 */
interface LogFeedReadRepository : JpaRepository<WorldLogReadEntity, Int> {

    // ── 글로벌 history (중원 정세 — PHP world_history nation_id=0) ──────────────────────────────

    /**
     * 글로벌 history 최신 N건(newest-first). PHP `getGlobalHistoryLogRecent($count)` 등가
     * (func_history.php:322-326 — `WHERE nation_id = 0 order by id desc limit %i`).
     * P1-059 map 히스토리 10건(`GetCachedMap.php:84`)도 이 메서드.
     */
    @Query(
        value = """
            SELECT id, year, month, phase, text FROM log_entry
            WHERE scope::text = 'SYSTEM' AND category::text = 'HISTORY'
            ORDER BY id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findRecentGlobalHistory(@Param("limit") limit: Int): List<WorldLogReadEntity>

    /**
     * 글로벌 history 증분 피드 — `id >= :sinceId`(경계 포함) newest-first LIMIT.
     * `GetFrontInfo::getHistory`(GetFrontInfo.php:92-103) 등가; 호출부는 ROW_LIMIT+1(=16)을 넘겨
     * flush 플래그/초과분 pop을 판정한다(그 판정은 소비자 소관).
     */
    @Query(
        value = """
            SELECT id, year, month, phase, text FROM log_entry
            WHERE scope::text = 'SYSTEM' AND category::text = 'HISTORY' AND id >= :sinceId
            ORDER BY id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findGlobalHistorySince(
        @Param("sinceId") sinceId: Int,
        @Param("limit") limit: Int,
    ): List<WorldLogReadEntity>

    /**
     * 글로벌 history 연월 조회(newest-first, LIMIT 없음). PHP `getGlobalHistoryLogWithDate($y,$m)`
     * 등가(func_history.php:328-341). 0행 폴백 문자열 합성은 소비자 소관.
     */
    @Query(
        value = """
            SELECT id, year, month, phase, text FROM log_entry
            WHERE scope::text = 'SYSTEM' AND category::text = 'HISTORY'
              AND year = :year AND month = :month
            ORDER BY id DESC
        """,
        nativeQuery = true,
    )
    fun findGlobalHistoryByMonth(
        @Param("year") year: Int,
        @Param("month") month: Int,
    ): List<WorldLogReadEntity>

    // ── 글로벌 action (장수 동향 — PHP general_record general_id=0 log_type='history') ──────────
    // SUMMARY(common ActionLogger) + ACTION(engine WorldActionContext) 합집합 — 클래스 KDoc 참조.

    /**
     * 글로벌 action 최신 N건(newest-first). PHP `getGlobalActionLogRecent($count)` 등가
     * (func_history.php:360-367).
     */
    @Query(
        value = """
            SELECT id, year, month, phase, text FROM log_entry
            WHERE scope::text = 'SYSTEM' AND category::text IN ('SUMMARY', 'ACTION')
            ORDER BY id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findRecentGlobalAction(@Param("limit") limit: Int): List<WorldLogReadEntity>

    /**
     * 글로벌 action 증분 피드 — `id >= :sinceId`(경계 포함) newest-first LIMIT.
     * `GetFrontInfo::getGlobalRecord`(GetFrontInfo.php:65-76) 등가.
     */
    @Query(
        value = """
            SELECT id, year, month, phase, text FROM log_entry
            WHERE scope::text = 'SYSTEM' AND category::text IN ('SUMMARY', 'ACTION') AND id >= :sinceId
            ORDER BY id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findGlobalActionSince(
        @Param("sinceId") sinceId: Int,
        @Param("limit") limit: Int,
    ): List<WorldLogReadEntity>

    /**
     * 글로벌 action 연월 조회(newest-first, LIMIT 없음). PHP `getGlobalActionLogWithDate($y,$m)`
     * 등가(func_history.php:369-382). 0행 폴백 문자열 합성은 소비자 소관.
     */
    @Query(
        value = """
            SELECT id, year, month, phase, text FROM log_entry
            WHERE scope::text = 'SYSTEM' AND category::text IN ('SUMMARY', 'ACTION')
              AND year = :year AND month = :month
            ORDER BY id DESC
        """,
        nativeQuery = true,
    )
    fun findGlobalActionByMonth(
        @Param("year") year: Int,
        @Param("month") month: Int,
    ): List<WorldLogReadEntity>

    // ── 개인 기록 증분 피드 (PHP general_record log_type='action') ──────────────────────────────

    /**
     * 한 장수의 개인 기록 증분 피드 — `id >= :sinceId`(경계 포함) newest-first LIMIT.
     * `GetFrontInfo::getGeneralRecord`(GetFrontInfo.php:78-90) 등가. category는 ACTION 단독
     * (common `ActionLogger.pushGeneralActionLog` → GENERAL/ACTION + general_id — PHP
     * `log_type = 'action'` 대응; 열전 HISTORY는 [AdminGeneralLogReadRepository] 소관).
     */
    @Query(
        value = """
            SELECT id, year, month, phase, text FROM log_entry
            WHERE scope::text = 'GENERAL' AND general_id = :generalId
              AND category::text = 'ACTION' AND id >= :sinceId
            ORDER BY id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findGeneralActionSince(
        @Param("generalId") generalId: Int,
        @Param("sinceId") sinceId: Int,
        @Param("limit") limit: Int,
    ): List<WorldLogReadEntity>

    // ── 범용 scope+category 피드 ─────────────────────────────────────────────────────────────────

    /**
     * 임의 scope+category 최신 N건(newest-first) — P1-009 경매 recentLogs 대비 범용 피드.
     * legacy `getAuctionLogRecent($count)`(func_history.php:93-95)는 파일 테일 최신 N건을
     * `array_reverse`로 오래된순 표시 — 그 뒤집기는 소비자 소관(레포는 newest-first 반환).
     * 현재 엔진 auction 핸들러의 scope "action"/category "auction"은 PG enum에 없는 P6 flush 버그
     * (DatabaseHooks.kt scopeLiteral NOTE) — 정본 리터럴 확정 후 호출부가 그 값을 넘긴다.
     * `::text` 비교라 enum에 없는 값을 넘겨도 0행일 뿐 에러가 아니다.
     */
    @Query(
        value = """
            SELECT id, year, month, phase, text FROM log_entry
            WHERE scope::text = :scope AND category::text = :category
            ORDER BY id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findRecentByScopeAndCategory(
        @Param("scope") scope: String,
        @Param("category") category: String,
        @Param("limit") limit: Int,
    ): List<WorldLogReadEntity>
}

package opensamguk.gameapi.read

import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.data.repository.Repository as SpringDataRepository

/**
 * log_entry feed READ — process-world scoped (OPENSAM-127).
 * Reuses [WorldLogReadEntity] projection. Daemon write via ChangeRecorder only.
 */
interface LogFeedReadRawRepository : SpringDataRepository<WorldLogReadEntity, Int> {


    // ── 글로벌 history (중원 정세 — PHP world_history nation_id=0) ──────────────────────────────

    /**
     * 글로벌 history 최신 N건(newest-first). PHP `getGlobalHistoryLogRecent($count)` 등가
     * (func_history.php:322-326 — `WHERE world_id = :worldId AND nation_id = 0 order by id desc limit %i`).
     * P1-059 map 히스토리 10건(`GetCachedMap.php:84`)도 이 메서드.
     */
    @Query(
        value = """
            SELECT id, year, month, phase, text FROM log_entry
            WHERE world_id = :worldId AND scope = 'SYSTEM' AND category = 'HISTORY'
            ORDER BY id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findRecentGlobalHistory(@Param("worldId") worldId: Int, @Param("limit") limit: Int): List<WorldLogReadEntity>

    /**
     * 글로벌 history 증분 피드 — `id >= :sinceId`(경계 포함) newest-first LIMIT.
     * `GetFrontInfo::getHistory`(GetFrontInfo.php:92-103) 등가; 호출부는 ROW_LIMIT+1(=16)을 넘겨
     * flush 플래그/초과분 pop을 판정한다(그 판정은 소비자 소관).
     */
    @Query(
        value = """
            SELECT id, year, month, phase, text FROM log_entry
            WHERE world_id = :worldId AND scope = 'SYSTEM' AND category = 'HISTORY' AND id >= :sinceId
            ORDER BY id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findGlobalHistorySince(@Param("worldId") worldId: Int, 
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
            WHERE world_id = :worldId AND scope = 'SYSTEM' AND category = 'HISTORY'
              AND year = :year AND month = :month
            ORDER BY id DESC
        """,
        nativeQuery = true,
    )
    fun findGlobalHistoryByMonth(@Param("worldId") worldId: Int, 
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
            WHERE world_id = :worldId AND scope = 'SYSTEM' AND category IN ('SUMMARY', 'ACTION')
            ORDER BY id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findRecentGlobalAction(@Param("worldId") worldId: Int, @Param("limit") limit: Int): List<WorldLogReadEntity>

    /**
     * 글로벌 action 증분 피드 — `id >= :sinceId`(경계 포함) newest-first LIMIT.
     * `GetFrontInfo::getGlobalRecord`(GetFrontInfo.php:65-76) 등가.
     */
    @Query(
        value = """
            SELECT id, year, month, phase, text FROM log_entry
            WHERE world_id = :worldId AND scope = 'SYSTEM' AND category IN ('SUMMARY', 'ACTION') AND id >= :sinceId
            ORDER BY id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findGlobalActionSince(@Param("worldId") worldId: Int, 
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
            WHERE world_id = :worldId AND scope = 'SYSTEM' AND category IN ('SUMMARY', 'ACTION')
              AND year = :year AND month = :month
            ORDER BY id DESC
        """,
        nativeQuery = true,
    )
    fun findGlobalActionByMonth(@Param("worldId") worldId: Int, 
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
            WHERE world_id = :worldId AND scope = 'GENERAL' AND general_id = :generalId
              AND category = 'ACTION' AND id >= :sinceId
            ORDER BY id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findGeneralActionSince(@Param("worldId") worldId: Int, 
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
     *
     * ※ 이 메서드만 의도적으로 `::text` 컬럼 비교를 유지한다(인덱스 비친화 — OPENSAM-14 예외 항목):
     * 라이브 호출부(AuctionController)가 위 P6 버그 리터럴("action"/"auction")을 그대로 넘기는데,
     * 파라미터를 enum으로 CAST하면 '없는 값 → 0행' 계약이 '없는 값 → SQL 에러'로 바뀌어 경매 페이지가
     * 500이 된다. P6 리터럴 정정 시 함께 enum-네이티브로 전환한다.
     */
    @Query(
        value = """
            SELECT id, year, month, phase, text FROM log_entry
            WHERE world_id = :worldId AND scope::text = :scope AND category::text = :category
            ORDER BY id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findRecentByScopeAndCategory(@Param("worldId") worldId: Int, 
        @Param("scope") scope: String,
        @Param("category") category: String,
        @Param("limit") limit: Int,
    ): List<WorldLogReadEntity>
}

@Repository
class LogFeedReadRepository(
    private val raw: LogFeedReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findRecentGlobalHistory(limit: Int): List<WorldLogReadEntity> =
        raw.findRecentGlobalHistory(worldId.value, limit)

    fun findGlobalHistorySince(sinceId: Int, limit: Int): List<WorldLogReadEntity> =
        raw.findGlobalHistorySince(worldId.value, sinceId, limit)

    fun findGlobalHistoryByMonth(year: Int, month: Int): List<WorldLogReadEntity> =
        raw.findGlobalHistoryByMonth(worldId.value, year, month)

    fun findRecentGlobalAction(limit: Int): List<WorldLogReadEntity> =
        raw.findRecentGlobalAction(worldId.value, limit)

    fun findGlobalActionSince(sinceId: Int, limit: Int): List<WorldLogReadEntity> =
        raw.findGlobalActionSince(worldId.value, sinceId, limit)

    fun findGlobalActionByMonth(year: Int, month: Int): List<WorldLogReadEntity> =
        raw.findGlobalActionByMonth(worldId.value, year, month)

    fun findGeneralActionSince(generalId: Int, sinceId: Int, limit: Int): List<WorldLogReadEntity> =
        raw.findGeneralActionSince(worldId.value, generalId, sinceId, limit)

    fun findRecentByScopeAndCategory(scope: String, category: String, limit: Int): List<WorldLogReadEntity> =
        raw.findRecentByScopeAndCategory(worldId.value, scope, category, limit)

}

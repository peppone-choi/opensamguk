package opensamguk.gameapi.read

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * B4a(`_admin7.php`) 장수별 로그 READ-only 매핑 — `log_entry`(scope=GENERAL)의 카테고리별 행.
 *
 * legacy `_admin7`은 `general_record` 테이블의 `log_type` 분기(action/battle/battle_brief/history)로
 * 4개 패널을 채운다(func_history.php:136-265). opensamguk은 별도 `general_record` 테이블이 없고
 * 동일 의미를 `log_entry`(scope=GENERAL) category 4종으로 저장한다(엔진 ActionLogger 매핑 정본):
 *   action       → category ACTION       (개인 기록)
 *   battle       → category BATTLE_DETAIL (전투 기록)
 *   battle_brief → category BATTLE_BRIEF  (전투 결과)
 *   history      → category HISTORY       (장수 열전)
 *
 * 정렬은 PHP `order by id desc`(newest-first) 그대로. `scope`/`category`는 Postgres enum 타입이라
 * 파라미터 비교 시 `::text` 캐스트가 필요 → 네이티브 쿼리(WorldLogReadRepository 선례와 동형).
 * 결과 컬럼(id/year/month/text)이 [WorldLogReadEntity] 필드와 1:1이라 그 엔티티를 재사용해 매핑한다
 * (동일 `log_entry` 테이블의 read-only 프로젝션 — 별도 @Entity 중복 선언 회피).
 *
 * game-api ONLY(§7); 절대 write하지 않는다(데몬 write는 ChangeRecorder→JdbcFlushExecutor 전용).
 */
interface AdminGeneralLogReadRepository : JpaRepository<WorldLogReadEntity, Int> {

    /**
     * 한 장수의 특정 category 로그 최신 N건(newest-first). PHP `*Recent($gen, $count)` 등가.
     * category는 호출부가 'ACTION'/'BATTLE_DETAIL'/'BATTLE_BRIEF' 등 enum backing 문자열을 넘긴다.
     */
    @Query(
        value = """
            SELECT id, year, month, text FROM log_entry
            WHERE scope::text = 'GENERAL' AND general_id = :generalId AND category::text = :category
            ORDER BY id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findRecentByGeneral(
        @Param("generalId") generalId: Int,
        @Param("category") category: String,
        @Param("limit") limit: Int,
    ): List<WorldLogReadEntity>

    /**
     * 한 장수의 HISTORY(장수 열전) 전체(newest-first). PHP `getGeneralHistoryLogAll($gen)`은 LIMIT 없이
     * `order by id desc` 전량을 반환하므로 LIMIT를 두지 않는다.
     */
    @Query(
        value = """
            SELECT id, year, month, text FROM log_entry
            WHERE scope::text = 'GENERAL' AND general_id = :generalId AND category::text = 'HISTORY'
            ORDER BY id DESC
        """,
        nativeQuery = true,
    )
    fun findAllHistoryByGeneral(@Param("generalId") generalId: Int): List<WorldLogReadEntity>
}

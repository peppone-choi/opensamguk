package opensamguk.gameapi.read

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * W0-5 — 국가 스코프 로그 READ-only 매핑: `log_entry`(scope=NATION)의 국가열전(國家列傳) 행.
 *
 * legacy는 국가 이력을 `world_history` 테이블의 `nation_id != 0` 행으로 저장하고
 * `getNationHistoryLogAll()`(func_history.php:296-303)로 읽는다:
 *   `SELECT text FROM world_history WHERE nation_id = %i ORDER BY id DESC`  (LIMIT 없음 — 전량)
 * opensamguk writer 정본은 common `ActionLogger.pushNationHistoryLog`(ActionLogger.kt:12-15) —
 * scope=NATION, category=HISTORY, nation_id 세팅(0/null이면 push 자체를 생략).
 *
 * 소비처(W1 소관 — 이 파운데이션은 쿼리만 제공):
 *  - P0-49/P1-079 my-nation 국가열전(`b_myKingdomInfo.php` 국가열전 행)
 *
 * 정렬은 PHP `order by id desc`(newest-first) 그대로. `scope`/`category`는 Postgres enum 타입이라
 * 파라미터·리터럴 비교 시 `::text` 캐스트가 필요 → 네이티브 쿼리([WorldLogReadRepository] 선례 동형).
 * 결과 컬럼(id/year/month/text)이 [WorldLogReadEntity]와 1:1이라 그 엔티티를 read-only 프로젝션으로
 * 재사용한다([AdminGeneralLogReadRepository] 선례 — 동일 테이블 중복 @Entity 선언 회피).
 *
 * game-api ONLY(§7); 절대 write하지 않는다(데몬 write는 ChangeRecorder→JdbcFlushExecutor 전용).
 */
interface NationLogReadRepository : JpaRepository<WorldLogReadEntity, Int> {

    /**
     * 한 국가의 국가열전 전량(newest-first). PHP `getNationHistoryLogAll($nationID)` 등가
     * (func_history.php:296-303). LIMIT을 두지 않는 것까지 패러티 — 페이지네이션 More 변형도
     * legacy에 없다. 기록 없는 국가는 빈 목록(폴백 '-' 등 표기는 소비자 소관, 무fabricate).
     */
    @Query(
        value = """
            SELECT id, year, month, text FROM log_entry
            WHERE scope::text = 'NATION' AND nation_id = :nationId AND category::text = 'HISTORY'
            ORDER BY id DESC
        """,
        nativeQuery = true,
    )
    fun findAllNationHistory(@Param("nationId") nationId: Int): List<WorldLogReadEntity>
}

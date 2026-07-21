package opensamguk.gameapi.read
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
/**
 * W0-5 — `log_entry` read 파운데이션 IT ([NationLogReadRepository] + [LogFeedReadRepository]).
 *
 * [ReadRepositoryIT] 선례 그대로: Testcontainers `postgres:16-alpine` + `:infra` Flyway 베이스라인
 * (테스트 클래스패스의 `:infra` 의존성으로 적용) + `@DataJpaTest`(테스트별 롤백).
 *
 * PHP 정본(쿼리 시멘틱의 근거 — 각 메서드 KDoc에 라인 명시):
 *  - `func_history.php:296-303` getNationHistoryLogAll — nation_id 필터, `order by id desc`, LIMIT 없음
 *  - `func_history.php:322-326` getGlobalHistoryLogRecent — nation_id=0, `order by id desc limit %i`
 *  - `func_history.php:328-341/369-382` get*LogWithDate — year/month 필터, `order by id desc`
 *  - `GetFrontInfo.php:65-103` 3피드 — `id >= %i ORDER BY id DESC LIMIT %i` (경계 포함!)
 *
 * scope/category 매핑 정본은 엔진 writer 2계열:
 *  - common `ActionLogger.kt`: globalHistory→SYSTEM/HISTORY, globalAction→SYSTEM/SUMMARY,
 *    nationHistory→NATION/HISTORY(+nation_id), generalAction→GENERAL/ACTION(+general_id)
 *  - engine `WorldActionContext.kt:571-573` pushGlobalActionLog → scope "global"/category "action"
 *    → `DatabaseHooks.scopeLiteral` 경유 SYSTEM/ACTION — 같은 논리 스트림(PHP general_record
 *    general_id=0 log_type='history')이 SUMMARY/ACTION 두 카테고리로 갈라져 영속화되므로
 *    글로벌 액션 read는 IN ('SUMMARY','ACTION') 합집합이어야 행 손실이 없다.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LogEntryReadRepositoryIT {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var nationLogs: NationLogReadRepository
    @Autowired lateinit var logFeeds: LogFeedReadRepository
    /** log_entry 1행 INSERT — scope/category는 PG enum 캐스트, id는 명시 지정(정렬·경계 검증용). */
    private fun insertLog(
        id: Int,
        scope: String,
        category: String,
        year: Int,
        month: Int,
        text: String,
        generalId: Int? = null,
        nationId: Int? = null,
    ) {
        jdbc.update(
            """
            INSERT INTO log_entry (world_id, id, scope, category, year, month, text, general_id, nation_id, meta)
            VALUES (1, ?, CAST(? AS log_scope), CAST(? AS log_category), ?, ?, ?, ?, ?, '{}'::jsonb)
            """.trimIndent(),
            id, scope, category, year, month, text, generalId, nationId,
        )
    }
    /**
     * 시드 13행 — 카테고리/스코프를 id 순서대로 섞어 DESC 정렬·필터 누수를 동시에 증명한다.
     * (id를 띄엄띄엄 두지 않고 1..13 연속으로 두되 스코프를 교차 배치)
     */
    @BeforeEach
    fun seed() {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) " +
                "VALUES (1, 'test', 1, 1, 60)",
        )
        insertLog(1, "SYSTEM", "HISTORY", 189, 1, "h1")
        insertLog(2, "SYSTEM", "SUMMARY", 189, 1, "s1")
        insertLog(3, "SYSTEM", "ACTION", 189, 1, "a1") // 엔진 WorldActionContext 계열 글로벌 액션
        insertLog(4, "NATION", "HISTORY", 189, 2, "n5-1", nationId = 5)
        insertLog(5, "SYSTEM", "HISTORY", 189, 2, "h2")
        insertLog(6, "GENERAL", "ACTION", 189, 2, "g10-1", generalId = 10)
        insertLog(7, "NATION", "HISTORY", 189, 2, "n7-1", nationId = 7)
        insertLog(8, "GENERAL", "ACTION", 189, 2, "g11-1", generalId = 11)
        insertLog(9, "SYSTEM", "HISTORY", 189, 2, "h3")
        insertLog(10, "SYSTEM", "SUMMARY", 189, 2, "s2")
        insertLog(11, "NATION", "HISTORY", 190, 3, "n5-2", nationId = 5)
        insertLog(12, "GENERAL", "ACTION", 190, 3, "g10-2", generalId = 10)
        insertLog(13, "GENERAL", "HISTORY", 190, 3, "g10-hist", generalId = 10)
    }
    // ── NationLogReadRepository ──────────────────────────────────────────────────────────────────
    @Test
    fun `국가열전 - 해당 국가의 NATION HISTORY 전량을 id DESC로, 타국·타스코프 누수 없이`() {
        // getNationHistoryLogAll(5): n5-2(id=11) → n5-1(id=4). LIMIT 없음(전량).
        val rows = nationLogs.findAllNationHistory(5)
        assertEquals(listOf(11, 4), rows.map { it.id })
        assertEquals(listOf("n5-2", "n5-1"), rows.map { it.text })
        // 타국(nation 7)은 자기 행만
        assertEquals(listOf("n7-1"), nationLogs.findAllNationHistory(7).map { it.text })
        // 기록 없는 국가는 빈 목록(절대 fabricate 없음)
        assertEquals(emptyList(), nationLogs.findAllNationHistory(99).map { it.id })
    }
    // ── LogFeedReadRepository: 글로벌 history ────────────────────────────────────────────────────
    @Test
    fun `글로벌 history 최신 N건 - SYSTEM HISTORY만, id DESC, LIMIT 준수`() {
        // getGlobalHistoryLogRecent(2): h3(9) → h2(5). SUMMARY/ACTION/NATION/GENERAL 누수 금지.
        val rows = logFeeds.findRecentGlobalHistory(2)
        assertEquals(listOf(9, 5), rows.map { it.id })
        assertEquals(listOf("h3", "h2"), rows.map { it.text })
        // 충분히 큰 limit이면 전 3행
        assertEquals(listOf(9, 5, 1), logFeeds.findRecentGlobalHistory(10).map { it.id })
    }
    @Test
    fun `글로벌 history 증분 피드 - id 경계 포함(GTE), id DESC, LIMIT 준수`() {
        // GetFrontInfo getHistory: id >= 5 → h3(9), h2(5). 경계 5 자신이 포함되어야 한다.
        assertEquals(listOf(9, 5), logFeeds.findGlobalHistorySince(5, 16).map { it.id })
        // LIMIT 1이면 최신 1건만
        assertEquals(listOf(9), logFeeds.findGlobalHistorySince(5, 1).map { it.id })
        // lastID=0(첫 로드)이면 전량 상한까지
        assertEquals(listOf(9, 5, 1), logFeeds.findGlobalHistorySince(0, 16).map { it.id })
    }
    @Test
    fun `글로벌 history 연월 조회 - year+month 필터, id DESC, LIMIT 없음`() {
        // getGlobalHistoryLogWithDate(189, 2): h3(9) → h2(5). 1월 행(h1) 제외.
        assertEquals(listOf(9, 5), logFeeds.findGlobalHistoryByMonth(189, 2).map { it.id })
        // 기록 없는 연월은 빈 목록("기록 없음" 폴백 문자열은 소비자 소관)
        assertEquals(emptyList(), logFeeds.findGlobalHistoryByMonth(200, 1).map { it.id })
    }
    // ── LogFeedReadRepository: 글로벌 action(SUMMARY+ACTION 합집합) ─────────────────────────────
    @Test
    fun `글로벌 action 최신 N건 - SYSTEM의 SUMMARY와 ACTION 합집합, id DESC`() {
        // getGlobalActionLogRecent: s2(10) → a1(3) → s1(2). HISTORY(h*)와 GENERAL/ACTION(g*) 제외.
        val rows = logFeeds.findRecentGlobalAction(10)
        assertEquals(listOf(10, 3, 2), rows.map { it.id })
        assertEquals(listOf("s2", "a1", "s1"), rows.map { it.text })
        assertEquals(listOf(10, 3), logFeeds.findRecentGlobalAction(2).map { it.id })
    }
    @Test
    fun `글로벌 action 증분 피드 - id 경계 포함, LIMIT 준수`() {
        // GetFrontInfo getGlobalRecord: id >= 3 → s2(10), a1(3)
        assertEquals(listOf(10, 3), logFeeds.findGlobalActionSince(3, 16).map { it.id })
        assertEquals(listOf(10), logFeeds.findGlobalActionSince(3, 1).map { it.id })
    }
    @Test
    fun `글로벌 action 연월 조회 - year+month 필터, id DESC`() {
        // getGlobalActionLogWithDate(189, 1): a1(3) → s1(2)
        assertEquals(listOf(3, 2), logFeeds.findGlobalActionByMonth(189, 1).map { it.id })
        assertEquals(emptyList(), logFeeds.findGlobalActionByMonth(200, 1).map { it.id })
    }
    // ── LogFeedReadRepository: 개인 기록 증분 피드 ───────────────────────────────────────────────
    @Test
    fun `개인 기록 증분 피드 - 해당 장수의 GENERAL ACTION만, 타장수·HISTORY 누수 없이`() {
        // GetFrontInfo getGeneralRecord(10, 0): g10-2(12) → g10-1(6).
        // g11-1(8, 타장수)·g10-hist(13, GENERAL/HISTORY 열전) 제외.
        assertEquals(listOf(12, 6), logFeeds.findGeneralActionSince(10, 0, 16).map { it.id })
        // id 경계 포함 + LIMIT
        assertEquals(listOf(12), logFeeds.findGeneralActionSince(10, 12, 16).map { it.id })
        assertEquals(listOf(12), logFeeds.findGeneralActionSince(10, 0, 1).map { it.id })
    }
    // ── LogFeedReadRepository: 범용 scope+category 피드 ─────────────────────────────────────────
    @Test
    fun `범용 scope+category 최신 N건 - 호출부가 정본 리터럴을 넘긴다`() {
        // (P1-009 경매 recentLogs 대비) — GENERAL/HISTORY로 동작 자체를 증명
        assertEquals(listOf(13), logFeeds.findRecentByScopeAndCategory("GENERAL", "HISTORY", 5).map { it.id })
        // 존재하지 않는 카테고리 값이어도 ::text 비교라 0행(에러 아님)
        assertEquals(
            emptyList(),
            logFeeds.findRecentByScopeAndCategory("SYSTEM", "NO_SUCH", 5).map { it.id },
        )
    }
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}

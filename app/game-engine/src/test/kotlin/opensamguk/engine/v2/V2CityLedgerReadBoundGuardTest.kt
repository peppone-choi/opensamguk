package opensamguk.engine.v2

import opensamguk.logic.memory.HotColdCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * OPENSAM-150 (R1) 적대적 리뷰 산출물 — `V2CityLedgerStore`의 DB 읽기에 대한 **보완 통제**.
 *
 * **왜 필요한가.** S5의 `HotColdWorldCatalogGuardTest`는 `HotColdCatalog.runtimeSourceDirectories`
 * (`logic/src/main/kotlin/opensamguk/logic/memory/HotColdCatalog.kt`)에 열거된 8개 디렉터리와
 * `runtimeDirectSqlBoundaries`가 지목한 파일만 스캔한다. `app/game-engine/src/main/kotlin/opensamguk/engine/v2`는
 * **그 목록에 없다.** 게다가 `V2CityLedgerStore`는 `*Repository`/`*Reader` 접미사를 의도적으로 피해
 * 수신자-이름 탐지에도 걸리지 않는다. 결과적으로 이 클래스의 `jdbc.query`는 **어떤 카탈로그 단언에도
 * 묶여 있지 않다** — 즉 무제한 스캔이나 쓰기가 들어와도 기존 가드는 침묵한다.
 *
 * R1에서는 이 클래스가 빈도 아니고 턴 루프에 배선돼 있지도 않아 실해가 없다. 그러나 R2가 데몬 루프에
 * 물리는 순간 카탈로그 밖 런타임 읽기가 되므로, 그때까지의 대체 통제로 **이 파일의 SQL 형태 자체**를
 * 고정한다. 카탈로그 등재(= 항구적 해법)는 R2의 선행 조건이며, 아래 마지막 테스트가 그 미등재 사실을
 * 실측으로 못박아 "등재됐다고 착각하는" 상태를 막는다.
 *
 * 소스 텍스트 스캔이라는 한계는 `V2NamingConventionGuardTest`와 동일하다(문자열/주석 구분 불가).
 */
class V2CityLedgerReadBoundGuardTest {

    private val storePath = "app/game-engine/src/main/kotlin/opensamguk/engine/v2/V2CityLedgerStore.kt"

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        fail("repo root (settings.gradle.kts) not found from ${File("").absolutePath}")
    }

    private fun storeSource(): String {
        val file = File(repoRoot(), storePath)
        assertTrue(file.isFile, "$storePath not found from ${repoRoot().absolutePath}")
        return file.readText()
    }

    /** 소스에서 큰따옴표 문자열 리터럴만 뽑는다. SQL은 전부 여기 들어 있다. */
    private fun stringLiterals(source: String): List<String> =
        Regex("\"([^\"\\\\\\n]|\\\\.)*\"").findAll(source).map { it.value.trim('"') }.toList()

    private fun sqlLiterals(source: String): List<String> =
        stringLiterals(source).filter { Regex("""(?i)\b(select|insert|update|delete|merge)\b""").containsMatchIn(it) }

    @Test
    fun `store의 유일한 SQL은 world-scoped 결정적 정렬 SELECT다`() {
        val sql = sqlLiterals(storeSource())

        assertEquals(1, sql.size, "v2 도시 원장 store는 SQL 리터럴을 하나만 가져야 한다: $sql")
        val select = sql.single()
        assertTrue(select.startsWith("SELECT "), select)
        assertTrue("FROM v2_city_ledger" in select, "다른 릴레이션을 읽으면 안 된다: $select")
        assertTrue("WHERE world_id = :world_id" in select, "월드 스코프 술어가 필수다: $select")
        assertTrue("ORDER BY city_id" in select, "결정적 정렬이 필수다: $select")
        assertTrue("*" !in select, "투영은 명시 컬럼이어야 한다(SELECT * 금지): $select")
    }

    @Test
    fun `store는 절대 직접 쓰지 않는다 -- 쓰기는 ChangeRecorder 경유`() {
        val source = storeSource()

        for (write in listOf("jdbc.update", "jdbc.batchUpdate", "jdbc.execute")) {
            assertTrue(write !in source, "$storePath must not write directly: $write")
        }
        assertTrue(
            "recorder.recordCityLedgerV2Upsert(" in source,
            "쓰기 의도는 ChangeRecorder 채널로만 기록돼야 한다",
        )
    }

    /**
     * 미등재 사실의 실측 고정. **이 테스트가 빨개지면 등재가 끝났다는 뜻이므로, 그때 이 테스트를 지우고
     * 카탈로그 단언에 맡겨라** — 실패가 곧 좋은 소식인 유일한 케이스다.
     */
    @Test
    fun `engine v2는 아직 S5 hot-cold 카탈로그 밖이다 -- R2 등재 전까지 이 가드가 대체 통제다`() {
        val cataloged = HotColdCatalog.runtimeSourceDirectories
            .any { it.endsWith("/engine/v2") } || storePath in HotColdCatalog.runtimeDirectSqlBoundarySources

        assertTrue(
            !cataloged,
            "engine/v2가 S5 카탈로그에 등재됐다. 이제 HotColdWorldCatalogGuardTest가 직접 검사하므로 " +
                "이 보완 통제 테스트를 삭제하라 (OPENSAM-150 R2 선행 조건 완료).",
        )
    }
}

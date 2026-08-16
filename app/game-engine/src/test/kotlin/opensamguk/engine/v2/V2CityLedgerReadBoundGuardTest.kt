package opensamguk.engine.v2

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * OPENSAM-150 (R1) 적대적 리뷰 산출물 — `V2CityLedgerStore`의 DB 읽기 **형태**에 대한 통제.
 *
 * **OPENSAM-189으로 달라진 것.** 원래 이 파일에는 "engine/v2가 아직 S5 카탈로그 밖"이라는 사실을 고정하는
 * 세 번째 케이스가 있었고, 그 미등재가 이 파일 전체의 존재 이유였다. 이제
 * `app/game-engine/src/main/kotlin/opensamguk/engine/v2`가 `HotColdCatalog.runtimeSourceDirectories`에,
 * `V2CityLedgerStore.kt`가 `runtimeDirectSqlBoundaries`에 등재됐으므로 그 케이스는 삭제했고,
 * "이 파일의 JDBC 호출이 카탈로그에 있는가"는 이제 `HotColdWorldCatalogGuardTest`의
 * `direct SQL calls stay in cataloged cold boundaries`가 `assertEquals`로 직접 판정한다.
 *
 * **그래도 아래 두 케이스는 남긴다 — 중복이 아니다.** 카탈로그 가드는 "이 파일이 직접 SQL을 쓴다"는
 * *사실*만 등재와 대조할 뿐, SQL **본문**은 보지 않는다. 즉 `SELECT *`로 바꾸거나 `WHERE world_id`를
 * 빼도, `jdbc.update`로 직접 쓰기를 넣어도(`isDirectSqlMethod`는 `update`도 통과시킨다) 카탈로그 가드는
 * 여전히 green이다. 무제한 스캔 금지와 읽기 전용(쓰기는 `ChangeRecorder` 경유)은 여기서만 단언된다.
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

}

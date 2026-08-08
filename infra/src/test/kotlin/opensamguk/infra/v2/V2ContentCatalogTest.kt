package opensamguk.infra.v2

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.CommandLineRunner

/**
 * OPENSAM-35 0A-d — [V2ContentCatalog]의 스코프·빈 카탈로그 동작, 그리고 **"scan·seed 없음"의 실측**.
 *
 * 픽스처는 `infra/src/test/resources/v2-catalog-fixture/content/` 아래에 있다:
 * `content/v2/alpha.json`·`beta.json`(잡혀야 함) / `content/v2/nested/deep.json`(재귀 금지) /
 * `content/v2/ignored.txt`(비-JSON) / `content/v2-decoy/decoy.json`(형제 디렉터리).
 */
class V2ContentCatalogTest {

    private val fixture = V2ContentCatalog("v2-catalog-fixture/content/v2")

    @Test
    fun `empty catalog returns an empty list instead of throwing`() {
        // 운영 기본 위치. 실제 v2 콘텐츠 파일은 아직 0개(README만)이므로 빈 목록이 정상 동작이다.
        assertEquals(emptyList(), V2ContentCatalog().names())
    }

    @Test
    fun `lists only the direct json entries of its own location`() {
        assertEquals(listOf("alpha.json", "beta.json"), fixture.names())
    }

    @Test
    fun `does not recurse into subdirectories`() {
        assertTrue("deep.json" !in fixture.names())
        assertNull(fixture.read("deep.json"))
    }

    @Test
    fun `does not read a sibling directory outside its scope`() {
        assertTrue("decoy.json" !in fixture.names())
        assertNull(fixture.read("decoy.json"))
    }

    @Test
    fun `reads the content of a listed entry and nothing else`() {
        assertEquals("""{ "fixture": "alpha" }""", fixture.read("alpha.json")?.trim())
        assertNull(fixture.read("ignored.txt"))
        assertNull(fixture.read("../v2-decoy/decoy.json"))
    }

    /**
     * "startup seed 없음"의 1차 실측: 스프링이 부팅 시 자동 호출하는 두 콜백 인터페이스를 이 타입이
     * 구현하지 않는다. 구현하면 게이트가 열린 컨텍스트에서 부팅과 동시에 실행돼버린다.
     */
    @Test
    fun `is not a startup runner`() {
        assertTrue(!ApplicationRunner::class.java.isAssignableFrom(V2ContentCatalog::class.java))
        assertTrue(!CommandLineRunner::class.java.isAssignableFrom(V2ContentCatalog::class.java))
    }

    /**
     * "DB 쓰기 없음"의 실측: `DaemonNoEntityManagerTest`와 같은 **클래스파일 상수풀 스캔**이다.
     * 어떤 타입을 참조하면 슬래시 형식 내부 이름이 상수풀에 남으므로, 선언(주석·규약)이 아니라
     * 컴파일 산출물로 판정한다. 나중에 누가 이 로더에 `JdbcTemplate`을 붙이면 이 테스트가 깨진다.
     */
    @Test
    fun `references no persistence write type and no startup runner type`() {
        val forbidden = listOf(
            "javax/sql/DataSource",
            "org/springframework/jdbc",
            "org/springframework/transaction",
            "jakarta/persistence",
            "org/springframework/data/repository",
            "org/springframework/boot/ApplicationRunner",
            "org/springframework/boot/CommandLineRunner",
            "opensamguk/engine/turn/ChangeRecorder",
            "opensamguk/infra/flush",
            "opensamguk/infra/seed",
        )
        val classFile = listOf(
            File("build/classes/kotlin/main/opensamguk/infra/v2/V2ContentCatalog.class"),
            File("infra/build/classes/kotlin/main/opensamguk/infra/v2/V2ContentCatalog.class"),
        ).firstOrNull { it.isFile } ?: fail("compiled V2ContentCatalog.class not found")

        val text = String(classFile.readBytes(), Charsets.ISO_8859_1)
        for (needle in forbidden) {
            assertTrue(needle !in text, "${classFile.path} constant pool references forbidden type $needle")
        }
        // 비공허성: 스캔이 실제로 클래스 내용을 보고 있음을 고정한다.
        assertTrue("org/springframework/core/io/support/PathMatchingResourcePatternResolver" in text)
        assertTrue("content/v2" in text)
    }
}

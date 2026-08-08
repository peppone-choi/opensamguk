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
 * OPENSAM-35 0A-d — [V2ContentCatalog] scope and empty-catalog behavior, plus executable proof of no scan or seed.
 *
 * Fixtures live under `infra/src/test/resources/v2-catalog-fixture/content/`:
 * `content/v2/alpha.json` and `beta.json` must be found; `content/v2/nested/deep.json` must not recurse;
 * `content/v2/ignored.txt` is non-JSON; and `content/v2-decoy/decoy.json` is a sibling directory.
 */
class V2ContentCatalogTest {

    private val fixture = V2ContentCatalog("v2-catalog-fixture/content/v2")

    @Test
    fun `empty catalog returns an empty list instead of throwing`() {
        // The production default location contains no v2 content files yet (only a README), so empty is correct.
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
     * First executable proof of no startup seed: this type does not implement Spring's two boot-invoked callback
     * interfaces. Implementing either would execute it immediately when a gated context starts.
     */
    @Test
    fun `is not a startup runner`() {
        assertTrue(!ApplicationRunner::class.java.isAssignableFrom(V2ContentCatalog::class.java))
        assertTrue(!CommandLineRunner::class.java.isAssignableFrom(V2ContentCatalog::class.java))
    }

    /**
     * Executable proof of no database write: the same class-file constant-pool scan used by
     * `DaemonNoEntityManagerTest`. Referenced types leave slash-form internal names in the constant pool, so this
     * judges compiled output rather than a declaration, comment, or contract. Adding `JdbcTemplate` to this loader
     * would break this test.
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
        // Non-vacuity: prove the scan actually examines this class's contents.
        assertTrue("org/springframework/core/io/support/PathMatchingResourcePatternResolver" in text)
        assertTrue("content/v2" in text)
    }
}

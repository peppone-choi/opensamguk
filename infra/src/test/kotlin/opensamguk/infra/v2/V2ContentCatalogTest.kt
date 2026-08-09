package opensamguk.infra.v2

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.CommandLineRunner

class V2ContentCatalogTest {

    private val fixture = V2ContentCatalog(FIXTURE_LOCATION)

    @Test
    fun `loads active metadata as the typed v2 catalog contract`() {
        val metadata = fixture.load("active")

        assertEquals(1, metadata.schemaVersion)
        assertEquals("active", metadata.id)
        assertEquals(V2ContentStatus.ACTIVE, metadata.status)
        assertEquals("v2-catalog-fixture/scenario/cities.json", metadata.source)
        assertEquals("2dc5ec3c107b828044d331acaa3a294a4de3e53915474000566143f1b959c9ee", metadata.sha256)
        assertEquals(2, metadata.cityCount)
        assertEquals(1, metadata.scenarioOwnedCityCount)
    }

    @Test
    fun `rejects candidate metadata instead of loading it`() {
        assertFailsWith<IllegalArgumentException> { fixture.load("candidate") }
    }

    @Test
    fun `rejects excluded metadata instead of loading it`() {
        assertFailsWith<IllegalArgumentException> { fixture.load("excluded") }
    }

    @Test
    fun `rejects budget-only metadata instead of loading it`() {
        assertFailsWith<IllegalArgumentException> { fixture.load("budget-only") }
    }

    @Test
    fun `fails closed for missing required fields and unknown statuses`() {
        assertFailsWith<IllegalArgumentException> { fixture.load("malformed") }
        assertFailsWith<IllegalArgumentException> { fixture.load("unknown-status") }
    }

    @Test
    fun `rejects metadata with an extra copied cities payload`() {
        assertFailsWith<IllegalArgumentException> { fixture.load("cities-payload") }
    }

    @Test
    fun `catalog entry lookups reject traversal and preserve direct-entry scope`() {
        assertFailsWith<IllegalArgumentException> { fixture.load("../candidate") }
        assertNull(fixture.read("../v2-decoy/decoy.json"))
        assertTrue("deep.json" !in fixture.names())
        assertTrue("decoy.json" !in fixture.names())
    }

    @Test
    fun `is not a startup runner`() {
        assertTrue(!ApplicationRunner::class.java.isAssignableFrom(V2ContentCatalog::class.java))
        assertTrue(!CommandLineRunner::class.java.isAssignableFrom(V2ContentCatalog::class.java))
    }

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
        assertTrue("org/springframework/core/io/support/PathMatchingResourcePatternResolver" in text)
        assertTrue("content/v2" in text)
    }

    private companion object {
        const val FIXTURE_LOCATION = "v2-catalog-fixture/content/v2"
    }
}

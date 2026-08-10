package opensamguk.infra.v2

import java.io.File
import java.net.URLClassLoader
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
        val error = assertFailsWith<IllegalArgumentException> { fixture.load("candidate") }

        assertEquals("v2 content 'candidate' is CANDIDATE and cannot be loaded", error.message)
    }

    @Test
    fun `rejects excluded metadata instead of loading it`() {
        val error = assertFailsWith<IllegalArgumentException> { fixture.load("excluded") }

        assertEquals("v2 content 'excluded' is EXCLUDED and cannot be loaded", error.message)
    }

    @Test
    fun `rejects budget-only metadata instead of loading it`() {
        val error = assertFailsWith<IllegalArgumentException> { fixture.load("budget-only") }

        assertEquals("v2 content 'budget-only' is BUDGET_ONLY and cannot be loaded", error.message)
    }

    @Test
    fun `fails closed for missing required fields and unknown statuses`() {
        val missingField = assertFailsWith<IllegalArgumentException> { fixture.load("malformed") }
        val unknownStatus = assertFailsWith<IllegalArgumentException> { fixture.load("unknown-status") }

        assertEquals("v2 content metadata must contain exactly the approved root keys", missingField.message)
        assertEquals("unknown v2 content status: DRAFT", unknownStatus.message)
    }

    @Test
    fun `rejects metadata with an extra copied cities payload`() {
        val error = assertFailsWith<IllegalArgumentException> { fixture.load("cities-payload") }

        assertEquals("v2 content metadata must contain exactly the approved root keys", error.message)
    }

    @Test
    fun `rejects duplicate metadata keys before the last status can satisfy the active contract`() {
        val error = assertFailsWith<IllegalArgumentException> { fixture.load("duplicate-status") }

        assertEquals("v2 content metadata must not contain duplicate keys: status", error.message)
    }

    @Test
    fun `rejects duplicate classpath metadata instead of selecting one arbitrarily`() {
        val originalClassLoader = Thread.currentThread().contextClassLoader
        val duplicateRoot = V2ContentCatalogTest::class.java.classLoader
            .getResource(DUPLICATE_CLASSPATH_ROOT)
            ?: fail("duplicate classpath fixture root not found")

        URLClassLoader(arrayOf(duplicateRoot), originalClassLoader).use { duplicateClassLoader ->
            Thread.currentThread().contextClassLoader = duplicateClassLoader
            try {
                val error = assertFailsWith<IllegalArgumentException> {
                    V2ContentCatalog(FIXTURE_LOCATION).load("active")
                }

                assertEquals("v2 content metadata is ambiguous: active", error.message)
            } finally {
                Thread.currentThread().contextClassLoader = originalClassLoader
            }
        }
    }

    @Test
    fun `catalog entry lookups reject traversal and preserve direct-entry scope`() {
        assertTrue(
            V2ContentCatalogTest::class.java.classLoader.getResource("$FIXTURE_LOCATION/nested/deep.json") != null,
        )
        assertTrue(
            V2ContentCatalogTest::class.java.classLoader
                .getResource("v2-catalog-fixture/content/v2-decoy/decoy.json") != null,
        )

        val traversal = assertFailsWith<IllegalArgumentException> { fixture.load("../candidate") }

        assertEquals("v2 content id is invalid: ../candidate", traversal.message)
        assertNull(fixture.read("../v2-decoy/decoy.json"))
        assertNull(fixture.read("deep.json"))
        assertNull(fixture.read("decoy.json"))
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
        const val DUPLICATE_CLASSPATH_ROOT = "v2-catalog-duplicate-classpath/"
    }
}

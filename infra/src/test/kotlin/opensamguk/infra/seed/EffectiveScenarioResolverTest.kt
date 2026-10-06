package opensamguk.infra.seed

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EffectiveScenarioResolverTest {

    @Test
    fun `same-name external scenario overrides the bundled scenario`(@TempDir scenarioDir: Path) {
        Files.writeString(scenarioDir.resolve("scenario_990002.json"), scenarioJson("external"), StandardCharsets.UTF_8)

        val scenario = EffectiveScenarioResolver(scenarioDir.toString()).resolve("scenario_990002")

        assertEquals("external", scenario.title)
    }

    @Test
    fun `missing external scenario falls back to the bundled scenario`(@TempDir scenarioDir: Path) {
        val scenario = EffectiveScenarioResolver(scenarioDir.toString()).resolve("scenario_990002")

        assertTrue(scenario.generals.isNotEmpty())
    }

    @Test
    fun `malformed selected external scenario does not fall back to the bundled scenario`(@TempDir scenarioDir: Path) {
        Files.writeString(scenarioDir.resolve("scenario_990002.json"), "{ malformed", StandardCharsets.UTF_8)

        assertFails {
            EffectiveScenarioResolver(scenarioDir.toString()).resolve("scenario_990002")
        }
    }

    @Test
    fun `unreadable selected external scenario does not fall back to the bundled scenario`(@TempDir scenarioDir: Path) {
        Files.createDirectory(scenarioDir.resolve("scenario_990002.json"))

        assertFails {
            EffectiveScenarioResolver(scenarioDir.toString()).readScenarioJson("scenario_990002")
        }
    }

    private fun scenarioJson(title: String): String =
        """{"title":"$title","startYear":180,"map":{},"const":{},"nation":[],"general":[],"general_ex":[],"diplomacy":[]}"""

    @Test
    fun `external observer and parse use the same captured bytes even when the path changes`(@TempDir temporary: Path) {
        val directory = temporary.toRealPath()
        val path = directory.resolve("scenario_990002.json")
        val wire = ("  " + scenarioJson("첫 원문") + " \n").toByteArray(StandardCharsets.UTF_8)
        Files.write(path, wire)
        var captured: CapturedScenarioOriginal? = null
        val resolver = EffectiveScenarioResolver(directory.toString(), onSelectedOriginal = { original ->
            captured = original
            Files.writeString(path, scenarioJson("later file"))
        })
        assertEquals("첫 원문", resolver.resolve("scenario_990002").title)
        val original = assertNotNull(captured)
        assertEquals(SelectedScenarioOrigin.EXTERNAL, original.origin)
        assertEquals(selectedOriginalSha(wire), original.rawSha256)
        assertContentEquals(wire, original.openOriginal().use { it.readAllBytes() })
        assertEquals("later file", ScenarioJson.loadScenario(Files.readString(path)).title)
    }

    @Test
    fun `classpath observer and UTF8 decode share a single resource read`() {
        val reads = AtomicInteger()
        val wire = scenarioJson("classpath 원문").toByteArray(StandardCharsets.UTF_8)
        val loader = object : ClassLoader(null) {
            override fun getResourceAsStream(name: String): InputStream {
                assertEquals("scenario/scenario_990002.json", name)
                reads.incrementAndGet()
                return ByteArrayInputStream(wire)
            }
        }
        var observed: CapturedScenarioOriginal? = null
        val resolver = EffectiveScenarioResolver(classLoader = loader, onSelectedOriginal = { observed = it })
        assertEquals("classpath 원문", resolver.resolve("scenario_990002").title)
        assertEquals(1, reads.get())
        val original = assertNotNull(observed)
        assertEquals(SelectedScenarioOrigin.CLASSPATH, original.origin)
        assertContentEquals(wire, original.originalBytes())
        assertEquals(1, reads.get())
    }

    @Test
    fun `observer refusal on selected external bytes cannot fall back to classpath`(@TempDir temporary: Path) {
        val directory = temporary.toRealPath()
        Files.writeString(directory.resolve("scenario_990002.json"), scenarioJson("external"))
        val loader = object : ClassLoader(null) {
            override fun getResourceAsStream(name: String): InputStream? = error("refused selection fell back to classpath")
        }
        val resolver = EffectiveScenarioResolver(directory.toString(), loader, onSelectedOriginal = { throw SelectedSourceUnavailable() })
        assertFailsWith<SelectedSourceUnavailable> { resolver.resolve("scenario_990002") }
    }
}

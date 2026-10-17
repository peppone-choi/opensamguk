package opensamguk.common.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.engine.archprobe.A3DiagnosticProbe
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class A3DiagnosticsTest {
    @TempDir
    lateinit var directory: Path

    private val origins = listOf(
        A3DiagnosticProbe.ThirdHandler::class.java,
        A3DiagnosticProbe.FirstHandler::class.java,
        A3DiagnosticProbe.FifthHandler::class.java,
        A3DiagnosticProbe.SecondHandler::class.java,
        A3DiagnosticProbe.FourthHandler::class.java,
        A3DiagnosticProbe.CleanHandler::class.java,
    )

    private fun measure(classes: List<Class<*>>) = ArchitectureRuleSupport.detect(
        ClassFileImporter().importClasses(classes), "opensamguk.engine",
    ).getValue("A3")

    @Test
    fun `counts unique bytecode origins rather than dependency targets or edges`() {
        val measured = measure(origins)
        assertEquals(5, measured.count)
        assertEquals(6, measured.scopedClasses)
        assertEquals(5, measured.originTargets.size)
        assertEquals(7, measured.originTargets.values.sumOf { it.size })
        assertEquals(3, measured.originTargets.getValue(A3DiagnosticProbe.FirstHandler::class.java.name).size)
        assertEquals(3, measured.examples.size, "Keep the legacy truncated examples")
        assertEquals(origins.filterNot { it == A3DiagnosticProbe.CleanHandler::class.java }.map { it.name }.sorted(),
            measured.originTargets.keys.toList(), "Nested bytecode names are retained, not collapsed to source files")
    }

    @Test
    fun `exports every origin and target deterministically without changing measurements`() {
        val measured = measure(origins)
        val output = ArchitectureRuleSupport.writeA3Diagnostics("game-engine", measured, directory.resolve("first"))
        val text = Files.readString(output)
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals("a3-origins.json", output.fileName.toString())
        assertEquals(setOf("version", "module", "rule", "origins"), json.keys)
        assertEquals(1, json.getValue("version").jsonPrimitive.int)
        assertEquals("game-engine", json.getValue("module").jsonPrimitive.content)
        assertEquals("A3", json.getValue("rule").jsonPrimitive.content)
        val exported = json.getValue("origins").jsonArray.map { element ->
            val entry = element.jsonObject
            assertEquals(setOf("origin", "targets"), entry.keys)
            entry.getValue("origin").jsonPrimitive.content to
                entry.getValue("targets").jsonArray.map { it.jsonPrimitive.content }
        }
        assertEquals(measured.originTargets.entries.map { it.key to it.value }, exported)
        assertEquals(5, exported.size, "The full export must not inherit take(3)")
        assertTrue(text.endsWith("\n"))
        val reversed = measure(origins.reversed())
        assertEquals(measured, reversed)
        // Also exercise the writer with deliberately shuffled, repeated targets.
        val shuffled = reversed.copy(originTargets = reversed.originTargets.entries.reversed().associate {
            it.key to it.value.reversed() + it.value
        })
        val again = ArchitectureRuleSupport.writeA3Diagnostics("game-engine", shuffled, directory.resolve("second"))
        assertEquals(text, Files.readString(again))
    }

    @Test
    fun `empty inventory is valid and module input cannot alter the output path`() {
        val empty = ArchitectureRuleSupport.Measurement(0, 0, emptyList())
        val output = ArchitectureRuleSupport.writeA3Diagnostics("game-engine", empty, directory)
        assertEquals("{\"version\":1,\"module\":\"game-engine\",\"rule\":\"A3\",\"origins\":[]}\n",
            Files.readString(output))
        listOf("common", "../game-engine", "unknown").forEach { module ->
            assertFailsWith<IllegalArgumentException> {
                ArchitectureRuleSupport.writeA3Diagnostics(module, empty, directory.resolve("rejected"))
            }
        }
        assertTrue(Files.notExists(directory.resolve("rejected")))
    }
}

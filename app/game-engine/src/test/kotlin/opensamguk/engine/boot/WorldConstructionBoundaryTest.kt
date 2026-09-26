package opensamguk.engine.boot

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class WorldConstructionBoundaryTest {
    @Test fun `product world construction stays behind the validated loader`() {
        val sourceRoot = Path.of("src/main/kotlin/opensamguk/engine")
        for (name in listOf("TurnWorldState", "WorldSnapshot")) {
            val call = Regex("\\b$name\\s*\\(")
            val constructors = Files.walk(sourceRoot).use { files ->
                files.filter { it.toString().endsWith(".kt") }
                    .flatMap { path ->
                        Files.readAllLines(path).stream()
                            .filter { line -> call.containsMatchIn(line) && !line.trimStart().startsWith("data class ") }
                            .map { path.toString() }
                    }.toList()
            }
            assertEquals(listOf(sourceRoot.resolve("boot/WorldSnapshotLoader.kt").toString()), constructors,
                "$name must be constructed only after WorldSnapshotLoader validates worldFormat")
        }
    }
}

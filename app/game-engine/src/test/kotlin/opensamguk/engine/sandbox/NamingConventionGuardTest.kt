package opensamguk.engine.sandbox

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Guards against reintroducing design-era version prefixes in product declarations.
 * The package and file-name rule is enforced separately by naming_lint.py.
 */
class NamingConventionGuardTest {

    /** `class V2X` / `object V2X` / `interface V2X`: only declarations with an uppercase letter after `V2`; `V26__` is excluded. */
    private val declaration = Regex("""\b(class|object|interface)\s+(V2[A-Z]\w*)""")

    private val scannedRoots = listOf(
        "app/game-engine/src/main/kotlin",
        "app/game-api/src/main/kotlin",
        "app/gateway-api/src/main/kotlin",
        "infra/src/main/kotlin",
        "common/src/main/kotlin",
        "logic/src/main/kotlin",
    )

    /** The test working directory may be a module or project root, so walk upward to `settings.gradle.kts`. */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        fail("repo root (settings.gradle.kts) not found from ${File("").absolutePath}")
    }

    @Test
    fun `version-prefixed declarations stay absent`() {
        val root = repoRoot()
        val sourceDirs = scannedRoots.map { File(root, it) }.filter { it.isDirectory }
        if (sourceDirs.size != scannedRoots.size) {
            fail("expected all scanned source roots to exist under ${root.absolutePath}, got $sourceDirs")
        }

        val violations = sourceDirs
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .flatMap { file ->
                declaration.findAll(file.readText()).map { m ->
                    "${file.relativeTo(root).path}: ${m.groupValues[2]}"
                }.toList()
            }
            .sorted()

        assertEquals(
            emptyList(), violations,
            "version-prefixed product declarations must stay absent",
        )
    }
}

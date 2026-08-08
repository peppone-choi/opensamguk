package opensamguk.engine.v2

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * OPENSAM-35 GATE-f2 F1 — naming-heuristic layer for plan §4-2 convention 1: **v2 runtime code belongs in an
 * `opensamguk.*.v2.*` package**. `V2ProductionContextBeanGateIT` detects leakage only when a bean's **type name
 * contains `.v2.`**, so a declaration such as `opensamguk.engine.ledger.V2CityLedgerStore` can silently evade the
 * gate. This source scan closes the naming-convention half of that gap.
 *
 * Measurement (2026-08-08): there are six `class|object|interface V2…` declarations across main sources. Five
 * are already in `opensamguk.*.v2.*`; the other is Flyway's `V26__npc_lifecycle_phase_units`, which is excluded
 * because a digit follows `V2`. There are therefore zero false positives.
 *
 * **Limit (intentional):** this enforces only the naming convention. v2 code with names that do **not** start
 * with `V2` (for example, `SandboxCityLedger` or `LedgerV2Store`) remains a review concern. As a source-text scan,
 * it also cannot distinguish the same pattern in a string literal or comment; there are currently none.
 */
class V2NamingConventionGuardTest {

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
    fun `V2-prefixed declarations live in an opensamguk v2 package`() {
        val root = repoRoot()
        val sourceDirs = scannedRoots.map { File(root, it) }.filter { it.isDirectory }
        if (sourceDirs.size != scannedRoots.size) {
            fail("expected all scanned source roots to exist under ${root.absolutePath}, got $sourceDirs")
        }

        val violations = sourceDirs
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .flatMap { file ->
                val text = file.readText()
                val pkg = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
                    .find(text)?.groupValues?.get(1).orEmpty()
                val segments = pkg.split('.')
                val inV2Package = segments.firstOrNull() == "opensamguk" && "v2" in segments
                if (inV2Package) {
                    emptyList()
                } else {
                    declaration.findAll(text).map { m ->
                        "${file.relativeTo(root).path}: ${m.groupValues[2]} (package '$pkg')"
                    }.toList()
                }
            }
            .sorted()

        assertEquals(
            emptyList(), violations,
            "V2-prefixed declarations must live in an opensamguk.*.v2.* package (plan §4-2 convention 1)",
        )
    }
}

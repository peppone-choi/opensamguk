package opensamguk.engine.boot

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * OPENSAM-149 D1 tripwire — `RehydrateService` is fully implemented but has ZERO production callers,
 * so a restarted daemon silently drops the active auction+bid, betting and message pools that the P6
 * gate item 4 names. Evidence: `docs/superpowers/research/2026-07-25-opensam-149-rehydrate-defects.md`.
 *
 * Static source guard in the style of [opensamguk.engine.config.AiProductionWiringGuardTest] — no
 * Docker, no Spring context, fails in milliseconds the moment the boot wiring regresses back to
 * "implemented but never called".
 */
class RehydrateWiringTest {

    private fun mainSourceRoot(): File = listOf(
        File("src/main/kotlin/opensamguk/engine"),
        File("app/game-engine/src/main/kotlin/opensamguk/engine"),
    ).firstOrNull { it.isDirectory }
        ?: error("game-engine main source root not found from ${File(".").absolutePath}")

    /** Production `.kt` files (the service's own file excluded) whose text contains [needle]. */
    private fun productionFilesContaining(needle: String): List<String> {
        val root = mainSourceRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "RehydrateService.kt" }
            .filter { it.readText().contains(needle) }
            .map { it.relativeTo(root).path }
            .sorted()
            .toList()
    }

    @Test
    fun `production boot wiring constructs and calls RehydrateService`() {
        val constructors = productionFilesContaining("RehydrateService(")
        assertTrue(
            constructors.isNotEmpty(),
            "NOT WIRED: no production file under game-engine main sources constructs `RehydrateService(`. " +
                "Boot only wires WorldSnapshotLoader (config/BootstrapConfig.kt `worldSnapshotLoader` + " +
                "`inMemoryTurnWorld`), so on daemon restart the active auction+bid pool, the betting pool " +
                "and the polymorphic message pool are never reloaded into memory (OPENSAM-149 D1). " +
                "Wire it at the daemon bootstrap seam — config/DaemonLoopConfig.kt `turnRunService`, where " +
                "the auction/message id allocators are already seeded from the DB.",
        )

        val callers = productionFilesContaining(".rehydrate(")
        assertTrue(
            callers.isNotEmpty(),
            "CONSTRUCTED BUT NEVER CALLED: production files $constructors build a RehydrateService but no " +
                "production file calls `.rehydrate(`. Constructing the service loads nothing — the daemon " +
                "still boots with empty auction/betting/message pools (OPENSAM-149 D1).",
        )
    }
}

package opensamguk.engine.boot

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * OPENSAM-149 D1 tripwire — the REAL restart-survivor seam.
 *
 * The P6 gate item 4 ("restart-rehydrate lossless") names the survivor pools: the betting pool, the
 * polymorphic message pool, and the id allocators that must not restart from zero (the auction+bid
 * pool it also named was retired in #917 A2). `RehydrateService` was the P6-era design for that, but
 * the daemon settled on a different one and the service was never wired — it was deleted in #917 A2:
 * the pools are served from the injected repositories on demand
 * (`MessageRepository.findByWorldIdAndMailboxAndValidUntilAfter` is the `valid_until > now` predicate),
 * and the allocators are seeded from the DB in `DaemonLoopConfig.turnRunService`. A boot-time memory
 * copy on top of that would create a SECOND source of truth for the same rows.
 *
 * So this guards what production actually relies on, not the abandoned service. Evidence and the D1
 * reclassification: `docs/superpowers/research/2026-07-25-opensam-149-rehydrate-defects.md`.
 *
 * Static source guard in the style of [opensamguk.engine.config.AiProductionWiringGuardTest] — no
 * Docker, no Spring context, fails in milliseconds if the boot wiring regresses.
 */
class RehydrateWiringTest {

    private fun mainSourceRoot(): File = listOf(
        File("src/main/kotlin/opensamguk/engine"),
        File("app/game-engine/src/main/kotlin/opensamguk/engine"),
    ).firstOrNull { it.isDirectory }
        ?: error("game-engine main source root not found from ${File(".").absolutePath}")

    /**
     * The body of the `turnRunService` @Bean ONLY — sliced to the next top-level declaration, in the
     * style of [opensamguk.engine.boot.HotColdWorldCatalogGuardTest]'s `privateMethodBody`.
     *
     * Scoping matters: grepping the whole file would keep passing if the seeding drifted out of the
     * live bean into dead code or a different (unwired) helper.
     */
    private fun turnRunServiceBody(): String {
        val file = mainSourceRoot().resolve("config/DaemonLoopConfig.kt")
        assertTrue(file.isFile, "config/DaemonLoopConfig.kt moved — re-aim this guard at the new daemon bootstrap seam")
        val source = file.readText()
        val start = Regex("""\n    fun turnRunService\b""").find(source)?.range?.first
            ?: error("DaemonLoopConfig no longer declares the `turnRunService` bean — re-aim this guard")
        val end = Regex("""\n    (?:@Bean|private fun |fun )""").find(source, start + 1)?.range?.first
            ?: source.length
        val body = source.substring(start, end)
        // Self-check: the slice must be a STRICT subset. `realtimePublisher` is declared before
        // turnRunService, so its presence would mean the slice degenerated to the whole file and
        // every assertion below would be vacuously satisfied by unrelated code.
        assertTrue(
            "fun realtimePublisher(" !in body,
            "slice degenerated — turnRunServiceBody() captured other beans, so this guard proves nothing",
        )
        return body
    }

    @Test
    fun `the daemon seeds its id allocators from the DB so a restart cannot reissue live ids`() {
        val source = turnRunServiceBody()

        assertTrue(
            source.contains("messageRepository.findMaxId()"),
            "NOT SEEDED: DaemonLoopConfig.turnRunService no longer seeds the message id allocator from " +
                "`messageRepository.findMaxId()`. A restarted daemon would restart message ids at 0 and " +
                "overwrite live rows on the next flush (OPENSAM-149 D1 / P6 gate item 4).",
        )
        assertTrue(
            source.contains("battleReplayRepository.findMaxId()"),
            "NOT SEEDED: DaemonLoopConfig.turnRunService no longer seeds the battle replay id allocator from " +
                "`battleReplayRepository.findMaxId()`. A restarted daemon would reissue replay ids that result " +
                "logs already cite (OPENSAM-149 D1 / P6 gate item 4).",
        )
        assertTrue(
            source.contains("messageIdAllocator = { ++nextMessageId }") &&
                source.contains("battleReplayIdAllocator = { ++nextBattleReplayId }"),
            "NOT WIRED: the DB-seeded counters are no longer handed to the ChangeRecorder allocators, so " +
                "the seeding above has no effect (OPENSAM-149 D1).",
        )
    }

    @Test
    fun `the survivor pools are served from injected repositories, not a boot-time memory copy`() {
        val source = turnRunServiceBody()

        val missing = listOf(
            "messageRepository: MessageRepository",
            "bettingRepository: opensamguk.infra.read.BettingRepository",
        ).filterNot { source.contains(it) }

        assertTrue(
            missing.isEmpty(),
            "POOL SOURCE LOST: DaemonLoopConfig.turnRunService no longer injects $missing. These " +
                "repositories ARE the restart-survivor path for the message / betting " +
                "pools the P6 gate item 4 names — they are read on demand instead of copied into memory " +
                "at boot. If a pool moved to a boot-time memory copy, that copy is now the thing to " +
                "guard (OPENSAM-149 D1).",
        )
    }
}

package opensamguk.engine.boot

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * OPENSAM-149 D1 tripwire — the REAL restart-survivor seam.
 *
 * The P6 gate item 4 ("restart-rehydrate lossless") names four survivor pools: the active
 * auction+bid pool, the betting pool, the polymorphic message pool, and the id allocators that must
 * not restart from zero. [opensamguk.engine.turn.RehydrateService] was the P6-era design for that,
 * but the daemon settled on a different one and the service was never wired: the pools are served
 * from the injected repositories on demand (`MessageRepository.findByWorldIdAndMailboxAndValidUntilAfter`
 * is the same `valid_until > now` predicate `RehydrateService.loadActiveMessages` implements), and the
 * allocators are seeded from the DB in `DaemonLoopConfig.turnRunService`. Wiring RehydrateService on
 * top of that would create a SECOND source of truth for the same rows.
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

    private fun daemonLoopConfig(): String = mainSourceRoot().resolve("config/DaemonLoopConfig.kt")
        .also { assertTrue(it.isFile, "config/DaemonLoopConfig.kt moved — re-aim this guard at the new daemon bootstrap seam") }
        .readText()

    @Test
    fun `the daemon seeds its id allocators from the DB so a restart cannot reissue live ids`() {
        val source = daemonLoopConfig()

        assertTrue(
            source.contains("messageRepository.findMaxId()"),
            "NOT SEEDED: DaemonLoopConfig no longer seeds the message id allocator from " +
                "`messageRepository.findMaxId()`. A restarted daemon would restart message ids at 0 and " +
                "overwrite live rows on the next flush (OPENSAM-149 D1 / P6 gate item 4).",
        )
        assertTrue(
            source.contains("auctionRepository.findAll()") && source.contains("maxOrNull()"),
            "NOT SEEDED: DaemonLoopConfig no longer derives the auction id allocator from the persisted " +
                "auction rows. A restarted daemon would reissue auction ids that are still active " +
                "(OPENSAM-149 D1 / P6 gate item 4).",
        )
        assertTrue(
            source.contains("messageIdAllocator = { ++nextMessageId }") &&
                source.contains("auctionIdAllocator = { ++nextAuctionId }"),
            "NOT WIRED: the DB-seeded counters are no longer handed to the ChangeRecorder allocators, so " +
                "the seeding above has no effect (OPENSAM-149 D1).",
        )
    }

    @Test
    fun `the survivor pools are served from injected repositories, not a boot-time memory copy`() {
        val source = daemonLoopConfig()

        val missing = listOf(
            "auctionRepository: AuctionRepository",
            "auctionBidRepository: AuctionBidRepository",
            "messageRepository: MessageRepository",
            "bettingRepository: opensamguk.infra.read.BettingRepository",
        ).filterNot { source.contains(it) }

        assertTrue(
            missing.isEmpty(),
            "POOL SOURCE LOST: DaemonLoopConfig.turnRunService no longer injects $missing. These " +
                "repositories ARE the restart-survivor path for the auction / bid / message / betting " +
                "pools the P6 gate item 4 names — they are read on demand instead of copied into memory " +
                "at boot. If a pool moved to a boot-time memory copy, that copy is now the thing to " +
                "guard (OPENSAM-149 D1).",
        )
    }
}

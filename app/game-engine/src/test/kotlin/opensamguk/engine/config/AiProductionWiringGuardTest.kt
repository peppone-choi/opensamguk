package opensamguk.engine.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class AiProductionWiringGuardTest {
    private fun source(): String = listOf(
        File("src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt"),
        File("app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt"),
    ).firstOrNull { it.isFile }?.readText()
        ?: error("DaemonLoopConfig.kt source not found from ${File(".").absolutePath}")

    @Test
    fun `production drains nation and general AI state at both lifecycle boundaries`() {
        val source = source()

        assertTrue(source.contains("ai.drainNationPassDeltas(recorder)"))
        assertTrue(source.contains("ai.drainGeneralPassDeltas(recorder)"))
    }

    @Test
    fun `production seeds auction ids from persisted rows`() {
        val source = source()

        assertTrue(source.contains("auctionRepository.findAll().mapNotNull { it.id }.maxOrNull() ?: 0"))
        assertTrue(source.contains("auctionIdAllocator = { ++nextAuctionId }"))
    }

    @Test
    fun `production seeds message ids from persisted rows`() {
        val source = source()

        assertTrue(source.contains("messageRepository.findMaxId()"))
        assertTrue(source.contains("messageIdAllocator = { ++nextMessageId }"))
    }
}

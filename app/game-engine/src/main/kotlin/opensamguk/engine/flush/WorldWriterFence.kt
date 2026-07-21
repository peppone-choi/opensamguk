package opensamguk.engine.flush

import opensamguk.common.world.WorldId

/**
 * OPENSAM-131 — per-world writer epoch + expected world_version for flush CAS.
 */
data class WorldWriterFence(
    val worldId: WorldId,
    val writerEpoch: Long,
    val expectedWorldVersion: Long,
) {
    fun nextExpectedAfterCommit(): Long = expectedWorldVersion + 1
}

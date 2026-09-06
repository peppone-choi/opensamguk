package opensamguk.infra.read

import opensamguk.common.world.WorldId
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Phase 4X-C — `battle_replay.id` 선할당 시드(`findMaxId()`, world-scoped; `MessageRepository` 선례).
 * 엔진 부팅 때 한 번 읽어 recorder 의 `battleReplayIdAllocator` 를 `max+1` 부터 시작시킨다.
 */
@Repository
class BattleReplayRepository(
    private val jdbc: JdbcTemplate,
    @Value("\${opensamguk.world-id:0}") opensamgukWorldId: Int,
    @Value("\${OPENSAMGUK_WORLD_ID:0}") envWorldId: Int,
) {
    private val worldId: WorldId = WorldId(
        when {
            opensamgukWorldId > 0 -> opensamgukWorldId
            envWorldId > 0 -> envWorldId
            else -> error("opensamguk.world-id or OPENSAMGUK_WORLD_ID must be a positive integer")
        },
    )

    fun findMaxId(): Int =
        jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM battle_replay WHERE world_id = ?", Int::class.java, worldId.value) ?: 0
}

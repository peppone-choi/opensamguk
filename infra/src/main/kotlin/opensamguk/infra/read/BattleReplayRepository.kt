package opensamguk.infra.read

import opensamguk.common.world.WorldId
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * Phase 4X-C — `battle_replay.id` 선할당 시드(`findMaxId()`, world-scoped; `MessageRepository`/`SelectPoolRepository` 선례).
 * 엔진 부팅 때 한 번 읽어 recorder 의 `battleReplayIdAllocator` 를 `max+1` 부터 시작시킨다.
 * 엔진은 infra `@Repository` 를 스캔하지 않으므로 [SideReadRepositoryConfiguration] 이 `@Bean` 으로 등록한다(2026-09-07 로컬 실측:
 * 스캔 누락이면 `turnRunService` 빈 생성이 실패해 턴 루프가 매 틱 백오프한다 — 컨테이너 health 는 그래도 UP 이라 로그를 봐야 한다).
 */
class BattleReplayRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val worldId: WorldId,
) {
    fun findMaxId(): Int =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(id), 0) FROM battle_replay WHERE world_id = :world_id",
            MapSqlParameterSource().addValue("world_id", worldId.value),
            Int::class.java,
        ) ?: 0
}

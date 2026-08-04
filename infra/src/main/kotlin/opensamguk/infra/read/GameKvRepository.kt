package opensamguk.infra.read

import opensamguk.common.world.WorldId
import opensamguk.infra.entity.GameKvEntity
import org.springframework.data.repository.Repository as SpringDataRepository

/**
 * `game_kv` 범용 JPA read 리포지토리.
 *
 * [InheritanceRepository]가 inheritance 네임스페이스 전용인 것과 달리, 테이블 차원
 * (`"table"` 컬럼)으로 행을 읽는 범용 read seam. 엔진의 베팅 마스터 조회
 * (PHP `KVStorage::getStorage($db,'betting')->getValue("id_{n}")` — Betting.php:42-44)가
 * 첫 소비자다. 쓰기 경로는 항상 [GameKvRowMapper] + [JdbcFlushExecutor](one-daemon-write).
 */
interface GameKvRepository {
    fun findByTable(table: String): List<GameKvEntity>
}

internal interface GameKvRawRepository : SpringDataRepository<GameKvEntity, Int> {
    fun findByWorldIdAndTable(worldId: Int, table: String): List<GameKvEntity>
}

internal class WorldScopedGameKvRepository(
    private val raw: GameKvRawRepository,
    private val worldId: WorldId,
) : GameKvRepository {
    override fun findByTable(table: String): List<GameKvEntity> =
        raw.findByWorldIdAndTable(worldId.value, table)
}

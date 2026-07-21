package opensamguk.gameapi.read

import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.entity.GameKvEntity
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.data.repository.Repository as SpringDataRepository

/**
 * F4 READ for `game_kv` — process-world scoped for world-owned families; global `inheritance`
 * rows remain world_id NULL (OPENSAM-126 mixed contract / OPENSAM-127).
 */
interface GameKvReadRawRepository : SpringDataRepository<GameKvEntity, Int> {
    @Query(
        "select k from GameKvEntity k where k.table = :table and k.namespace = :namespace and k.key = :key " +
            "and (k.worldId = :worldId or (k.table = 'inheritance' and k.worldId is null))",
    )
    fun findScoped(@Param("worldId") worldId: Int, @Param("table") table: String, @Param("namespace") namespace: String, @Param("key") key: String): GameKvEntity?

    @Query(
        "select k from GameKvEntity k where k.table = :table and k.namespace = :namespace " +
            "and (k.worldId = :worldId or (k.table = 'inheritance' and k.worldId is null))",
    )
    fun findScopedByTableNamespace(@Param("worldId") worldId: Int, @Param("table") table: String, @Param("namespace") namespace: String): List<GameKvEntity>

    @Query(
        "select k from GameKvEntity k where k.worldId = :worldId or (k.table = 'inheritance' and k.worldId is null)",
    )
    fun findAllScoped(@Param("worldId") worldId: Int): List<GameKvEntity>
}

@Repository
class GameKvReadRepository(
    private val raw: GameKvReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findByTableAndNamespaceAndKey(table: String, namespace: String, key: String): GameKvEntity? =
        raw.findScoped(worldId.value, table, namespace, key)

    fun findByTableAndNamespace(table: String, namespace: String): List<GameKvEntity> =
        raw.findScopedByTableNamespace(worldId.value, table, namespace)

    fun findAll(): List<GameKvEntity> = raw.findAllScoped(worldId.value)
}

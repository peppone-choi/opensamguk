package opensamguk.infra.read

import opensamguk.infra.entity.GameKvEntity
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.repository.Repository as SpringDataRepository

/**
 * JPA read repository for inheritance data backed by `game_kv` table.
 *
 * PHP stores inheritance per-user under the `inheritance_{ownerId}` namespace
 * in the `storage` table (V7 `game_kv`, `"table" = 'inheritance'`).
 *
 * This repository queries the KV store for inheritance entries. The write path
 * (daemon flush) goes through [GameKvRowMapper] + [JdbcFlushExecutor].
 */
interface InheritanceRepository {
    fun findByInheritanceNamespace(namespace: String): List<GameKvEntity>
    fun findByTableAndNamespaceAndKey(table: String, namespace: String, key: String): GameKvEntity?
}

internal interface InheritanceRawRepository : SpringDataRepository<GameKvEntity, Int> {
    @Query(
        """
        SELECT kv FROM GameKvEntity kv
        WHERE kv.table = 'inheritance' AND kv.worldId IS NULL AND kv.namespace = :namespace
        """,
    )
    fun findByInheritanceNamespace(@Param("namespace") namespace: String): List<GameKvEntity>

    @Query(
        """
        SELECT kv FROM GameKvEntity kv
        WHERE kv.table = 'inheritance'
          AND kv.worldId IS NULL
          AND kv.namespace = :namespace
          AND kv.key = :key
        """,
    )
    fun findByInheritanceNamespaceAndKey(
        @Param("namespace") namespace: String,
        @Param("key") key: String,
    ): GameKvEntity?
}

internal class GlobalInheritanceRepository(
    private val raw: InheritanceRawRepository,
) : InheritanceRepository {
    override fun findByInheritanceNamespace(namespace: String): List<GameKvEntity> =
        raw.findByInheritanceNamespace(namespace)

    override fun findByTableAndNamespaceAndKey(table: String, namespace: String, key: String): GameKvEntity? =
        if (table == "inheritance") raw.findByInheritanceNamespaceAndKey(namespace, key) else null
}

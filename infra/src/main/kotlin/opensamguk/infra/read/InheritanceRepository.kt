package opensamguk.infra.read

import opensamguk.infra.entity.GameKvEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * JPA read repository for inheritance data backed by `game_kv` table.
 *
 * PHP stores inheritance per-user under the `inheritance_{ownerId}` namespace
 * in the `storage` table (V7 `game_kv`, `"table" = 'inheritance'`).
 *
 * This repository queries the KV store for inheritance entries. The write path
 * (daemon flush) goes through [GameKvRowMapper] + [JdbcFlushExecutor].
 */
@Repository
interface InheritanceRepository : JpaRepository<GameKvEntity, Int> {

    /**
     * Find all KV entries for a given owner under the inheritance namespace.
     * Namespace format: `inheritance_{ownerId}` (e.g. `inheritance_42`).
     */
    @Query(
        """
        SELECT kv FROM GameKvEntity kv
        WHERE kv.table = 'inheritance' AND kv.namespace = :namespace
        """
    )
    fun findByInheritanceNamespace(@Param("namespace") namespace: String): List<GameKvEntity>

    /**
     * Find a specific inheritance key for an owner.
     */
    fun findByTableAndNamespaceAndKey(table: String, namespace: String, key: String): GameKvEntity?
}

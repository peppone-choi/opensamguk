package opensamguk.gameapi.read

import opensamguk.infra.entity.GameKvEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * F4 READ-only access to the string-namespace KV store (`game_kv`, V7) for the F4 pages that read
 * non-entity game state: tournament admin state (`table='game_env'`), NPC policy
 * (`table='game_env'`/`nation_env`-adjacent keys), and inherit-point owner data
 * (`table='inheritance'`, namespace `inheritance_{ownerId}`).
 *
 * This is a SECOND read view onto the same `GameKvEntity` the infra `InheritanceRepository` maps; it is
 * declared in `opensamguk.gameapi.read` (already entity/repo-scanned by GameApiApplication) so the F4
 * controllers can read any string-namespace key generically without coupling to the inheritance-named
 * repo. The `value` is the raw jsonb string; callers decode it. Empty/absent key → null (graceful).
 *
 * game-api ONLY (§7); never written here — the daemon flush owns every game_kv write.
 */
interface GameKvReadRepository : JpaRepository<GameKvEntity, Int> {
    /** A single KV row by (table, namespace, key); null when absent (graceful default). */
    fun findByTableAndNamespaceAndKey(table: String, namespace: String, key: String): GameKvEntity?

    /** All KV rows in a (table, namespace) — e.g. the whole `game_env` config bag. */
    fun findByTableAndNamespace(table: String, namespace: String): List<GameKvEntity>
}

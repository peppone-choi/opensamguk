package opensamguk.gameapi.read

import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.data.repository.Repository as SpringDataRepository

/**
 * B4a admin general logs — process-world scoped log_entry (OPENSAM-127 residual).
 */
interface AdminGeneralLogReadRawRepository : SpringDataRepository<WorldLogReadEntity, Int> {
    @Query(
        value = """
            SELECT id, year, month, phase, text FROM log_entry
            WHERE world_id = :worldId AND scope = 'GENERAL' AND general_id = :generalId
              AND category = CAST(:category AS log_category)
            ORDER BY id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findRecentByGeneral(@Param("worldId") worldId: Int, @Param("generalId") generalId: Int, @Param("category") category: String, @Param("limit") limit: Int): List<WorldLogReadEntity>

    @Query(
        value = """
            SELECT id, year, month, phase, text FROM log_entry
            WHERE world_id = :worldId AND scope = 'GENERAL' AND general_id = :generalId
              AND category = CAST(:category AS log_category)
              AND id < :before
            ORDER BY id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findRecentByGeneralBefore(@Param("worldId") worldId: Int, @Param("generalId") generalId: Int, @Param("category") category: String, @Param("before") before: Int, @Param("limit") limit: Int): List<WorldLogReadEntity>

    @Query(
        value = """
            SELECT id, year, month, phase, text FROM log_entry
            WHERE world_id = :worldId AND scope = 'GENERAL' AND general_id = :generalId AND category = 'HISTORY'
            ORDER BY id DESC
        """,
        nativeQuery = true,
    )
    fun findAllHistoryByGeneral(@Param("worldId") worldId: Int, @Param("generalId") generalId: Int): List<WorldLogReadEntity>
}

@Repository
class AdminGeneralLogReadRepository(
    private val raw: AdminGeneralLogReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findRecentByGeneral(generalId: Int, category: String, limit: Int): List<WorldLogReadEntity> =
        raw.findRecentByGeneral(worldId.value, generalId, category, limit)

    fun findRecentByGeneralBefore(
        generalId: Int,
        category: String,
        before: Int,
        limit: Int,
    ): List<WorldLogReadEntity> =
        raw.findRecentByGeneralBefore(worldId.value, generalId, category, before, limit)

    fun findAllHistoryByGeneral(generalId: Int): List<WorldLogReadEntity> =
        raw.findAllHistoryByGeneral(worldId.value, generalId)
}

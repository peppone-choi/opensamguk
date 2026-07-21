package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.data.repository.Repository as SpringDataRepository

@Entity
@Table(name = "log_entry")
class WorldLogReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "year")
    var year: Int = 0,

    @Column(name = "month")
    var month: Int = 0,

    @Column(name = "phase")
    var phase: Int = 1,

    @Column(name = "text")
    var text: String = "",
)

interface WorldLogReadRawRepository : SpringDataRepository<WorldLogReadEntity, Int> {
    @Query(
        value = """
            SELECT id, year, month, phase, text FROM log_entry
            WHERE world_id = :worldId AND scope = 'SYSTEM' AND category IN ('HISTORY', 'SUMMARY')
            ORDER BY id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findRecentWorldLog(@Param("worldId") worldId: Int, @Param("limit") limit: Int): List<WorldLogReadEntity>
}

@Repository
class WorldLogReadRepository(
    private val raw: WorldLogReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findRecentWorldLog(limit: Int): List<WorldLogReadEntity> =
        raw.findRecentWorldLog(worldId.value, limit)
}

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

/**
 * W3-F2 — `rank_data` READ-only mapping, process-world scoped (OPENSAM-127).
 * Daemon write: ChangeRecorder rank channel → JdbcFlushExecutor only.
 */
@Entity
@Table(name = "rank_data")
class RankDataReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "world_id")
    var worldId: Int = 0,

    @Column(name = "nation_id")
    var nationId: Int = 0,

    @Column(name = "general_id")
    var generalId: Int = 0,

    @Column(name = "type")
    var type: String = "",

    @Column(name = "value")
    var value: Int = 0,
)

interface RankDataReadRawRepository : SpringDataRepository<RankDataReadEntity, Int> {
    fun findByWorldIdAndGeneralId(worldId: Int, generalId: Int): List<RankDataReadEntity>

    fun findByWorldIdAndGeneralIdAndType(worldId: Int, generalId: Int, type: String): RankDataReadEntity?

    @Query(
        "select r from RankDataReadEntity r " +
            "where r.worldId = :worldId and r.generalId in :generalIds and r.type in :types",
    )
    fun findByWorldIdAndGeneralIdsAndTypes(
        @Param("worldId") worldId: Int,
        @Param("generalIds") generalIds: Collection<Int>,
        @Param("types") types: Collection<String>,
    ): List<RankDataReadEntity>
}

@Repository
class RankDataReadRepository(
    private val raw: RankDataReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findByGeneralId(generalId: Int): List<RankDataReadEntity> =
        raw.findByWorldIdAndGeneralId(worldId.value, generalId)

    fun findByGeneralIdAndType(generalId: Int, type: String): RankDataReadEntity? =
        raw.findByWorldIdAndGeneralIdAndType(worldId.value, generalId, type)

    fun findByGeneralIdsAndTypes(
        generalIds: Collection<Int>,
        types: Collection<String>,
    ): List<RankDataReadEntity> =
        raw.findByWorldIdAndGeneralIdsAndTypes(worldId.value, generalIds, types)
}

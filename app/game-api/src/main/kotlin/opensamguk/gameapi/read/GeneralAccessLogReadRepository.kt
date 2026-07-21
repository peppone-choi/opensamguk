package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.stereotype.Repository
import java.time.Instant
import org.springframework.data.repository.Repository as SpringDataRepository

@Entity
@Table(name = "general_access_log")
class GeneralAccessLogReadEntity(
    @Id
    @Column(name = "id")
    var id: Long = 0,

    @Column(name = "world_id")
    var worldId: Int = 0,

    @Column(name = "general_id")
    var generalId: Int = 0,

    @Column(name = "user_id")
    var userId: Long? = null,

    @Column(name = "last_refresh")
    var lastRefresh: Instant? = null,

    @Column(name = "refresh")
    var refresh: Int = 0,

    @Column(name = "refresh_total")
    var refreshTotal: Int = 0,

    @Column(name = "refresh_score")
    var refreshScore: Int = 0,

    @Column(name = "refresh_score_total")
    var refreshScoreTotal: Int = 0,
)

interface GeneralAccessLogReadRawRepository : SpringDataRepository<GeneralAccessLogReadEntity, Long> {
    fun findByWorldId(worldId: Int): List<GeneralAccessLogReadEntity>
    fun findByWorldIdAndGeneralId(worldId: Int, generalId: Int): GeneralAccessLogReadEntity?
    fun findByWorldIdAndGeneralIdIn(worldId: Int, generalIds: Collection<Int>): List<GeneralAccessLogReadEntity>
}

@Repository
class GeneralAccessLogReadRepository(
    private val raw: GeneralAccessLogReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findAll(): List<GeneralAccessLogReadEntity> = raw.findByWorldId(worldId.value)

    fun findByGeneralId(generalId: Int): GeneralAccessLogReadEntity? =
        raw.findByWorldIdAndGeneralId(worldId.value, generalId)

    fun findByGeneralIdIn(generalIds: Collection<Int>): List<GeneralAccessLogReadEntity> =
        raw.findByWorldIdAndGeneralIdIn(worldId.value, generalIds)
}

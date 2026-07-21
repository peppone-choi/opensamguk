package opensamguk.gameapi.owner

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Instant

class GeneralOwnerEntity(
    var generalId: Long = 0,
    var userId: Long = 0,
    var claimedAt: Instant = Instant.EPOCH,
)

interface GeneralOwnerRepository {
    fun findByUserId(userId: Long): GeneralOwnerEntity?

    fun existsByGeneralId(generalId: Long): Boolean

    fun findAllByOrderByGeneralIdAsc(): List<GeneralOwnerEntity>

    fun save(entity: GeneralOwnerEntity): GeneralOwnerEntity
}

internal data class GeneralOwnerRecordId(
    var worldId: Int = 0,
    var generalId: Long = 0,
) : Serializable

@Entity
@Table(name = "general_owner")
@IdClass(GeneralOwnerRecordId::class)
internal class GeneralOwnerRecord(
    @Id
    @Column(name = "world_id", nullable = false)
    var worldId: Int,

    @Id
    @Column(name = "general_id", nullable = false)
    var generalId: Long,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "claimed_at", nullable = false)
    var claimedAt: Instant,
)

internal interface GeneralOwnerRecordRepository : JpaRepository<GeneralOwnerRecord, GeneralOwnerRecordId> {
    fun findByWorldIdAndUserId(worldId: Int, userId: Long): GeneralOwnerRecord?

    fun existsByWorldIdAndGeneralId(worldId: Int, generalId: Long): Boolean

    fun findAllByWorldIdOrderByGeneralIdAsc(worldId: Int): List<GeneralOwnerRecord>
}

@Repository
internal class WorldScopedGeneralOwnerRepository(
    private val raw: GeneralOwnerRecordRepository,
    processWorld: GameApiProcessWorld,
) : GeneralOwnerRepository {
    private val worldId = processWorld.worldId

    override fun findByUserId(userId: Long): GeneralOwnerEntity? =
        raw.findByWorldIdAndUserId(worldId.value, userId)?.toValue()

    override fun existsByGeneralId(generalId: Long): Boolean =
        raw.existsByWorldIdAndGeneralId(worldId.value, generalId)

    override fun findAllByOrderByGeneralIdAsc(): List<GeneralOwnerEntity> =
        raw.findAllByWorldIdOrderByGeneralIdAsc(worldId.value).map { it.toValue() }

    override fun save(entity: GeneralOwnerEntity): GeneralOwnerEntity {
        raw.save(
            GeneralOwnerRecord(
                worldId = worldId.value,
                generalId = entity.generalId,
                userId = entity.userId,
                claimedAt = entity.claimedAt,
            ),
        )
        return entity
    }

    private fun GeneralOwnerRecord.toValue(): GeneralOwnerEntity = GeneralOwnerEntity(
        generalId = generalId,
        userId = userId,
        claimedAt = claimedAt,
    )
}

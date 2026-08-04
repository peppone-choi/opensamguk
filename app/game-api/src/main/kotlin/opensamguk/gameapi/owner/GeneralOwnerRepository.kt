package opensamguk.gameapi.owner

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.read.GeneralReadEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Instant

class GeneralOwnerEntity(
    var generalId: Long = 0,
    var userId: Long = 0,
    var claimedAt: Instant = Instant.EPOCH,
    var claimRequestId: String? = null,
)

internal fun GeneralOwnerEntity.isFinalizedFor(general: GeneralReadEntity, userId: Long): Boolean =
    if (claimRequestId == null) {
        general.npcState != GeneralPossessionService.CLAIMABLE_NPC_STATE
    } else {
        general.npcState == GeneralPossessionService.POSSESSED_NPC_STATE && general.userId == userId.toString()
    }

interface GeneralOwnerRepository {
    fun findByUserId(userId: Long): GeneralOwnerEntity?

    fun existsByGeneralId(generalId: Long): Boolean

    fun findAllByOrderByGeneralIdAsc(): List<GeneralOwnerEntity>

    fun save(entity: GeneralOwnerEntity): GeneralOwnerEntity

    fun deleteByUserIdAndGeneralIdAndClaimRequestId(userId: Long, generalId: Long, claimRequestId: String): Int
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

    @Column(name = "claim_request_id")
    var claimRequestId: String? = null,
)

internal interface GeneralOwnerRecordRepository : JpaRepository<GeneralOwnerRecord, GeneralOwnerRecordId> {
    fun findByWorldIdAndUserId(worldId: Int, userId: Long): GeneralOwnerRecord?

    fun existsByWorldIdAndGeneralId(worldId: Int, generalId: Long): Boolean

    fun findAllByWorldIdOrderByGeneralIdAsc(worldId: Int): List<GeneralOwnerRecord>

    @Modifying
    @Query(
        "delete from GeneralOwnerRecord r " +
            "where r.worldId = :worldId and r.userId = :userId and r.generalId = :generalId " +
            "and r.claimRequestId = :claimRequestId",
    )
    fun deleteByWorldIdAndUserIdAndGeneralIdAndClaimRequestId(
        @Param("worldId") worldId: Int,
        @Param("userId") userId: Long,
        @Param("generalId") generalId: Long,
        @Param("claimRequestId") claimRequestId: String,
    ): Int
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
                claimRequestId = entity.claimRequestId,
            ),
        )
        return entity
    }

    override fun deleteByUserIdAndGeneralIdAndClaimRequestId(
        userId: Long,
        generalId: Long,
        claimRequestId: String,
    ): Int = raw.deleteByWorldIdAndUserIdAndGeneralIdAndClaimRequestId(
        worldId = worldId.value,
        userId = userId,
        generalId = generalId,
        claimRequestId = claimRequestId,
    )

    private fun GeneralOwnerRecord.toValue(): GeneralOwnerEntity = GeneralOwnerEntity(
        generalId = generalId,
        userId = userId,
        claimedAt = claimedAt,
        claimRequestId = claimRequestId,
    )
}

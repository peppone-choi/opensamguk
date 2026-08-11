package opensamguk.gameapi.owner

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import opensamguk.gameapi.config.GameApiProcessWorld
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

interface GeneralOwnerRepository {
    fun findByUserId(userId: Long): GeneralOwnerEntity?

    fun existsByGeneralId(generalId: Long): Boolean

    fun findAllByOrderByGeneralIdAsc(): List<GeneralOwnerEntity>

    fun save(entity: GeneralOwnerEntity): GeneralOwnerEntity

    fun deleteByUserIdAndGeneralIdAndClaimRequestId(userId: Long, generalId: Long, claimRequestId: String): Int

    fun deleteIfUnchanged(entity: GeneralOwnerEntity): Int
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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "delete from GeneralOwnerRecord r " +
            "where r.worldId = :worldId and r.userId = :userId and r.generalId = :generalId " +
            "and r.claimedAt = :claimedAt and r.claimRequestId = :claimRequestId",
    )
    fun deleteIfCurrentRequest(
        @Param("worldId") worldId: Int,
        @Param("userId") userId: Long,
        @Param("generalId") generalId: Long,
        @Param("claimedAt") claimedAt: Instant,
        @Param("claimRequestId") claimRequestId: String,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "delete from GeneralOwnerRecord r " +
            "where r.worldId = :worldId and r.userId = :userId and r.generalId = :generalId " +
            "and r.claimedAt = :claimedAt and r.claimRequestId is null",
    )
    fun deleteIfCurrentLegacyReservation(
        @Param("worldId") worldId: Int,
        @Param("userId") userId: Long,
        @Param("generalId") generalId: Long,
        @Param("claimedAt") claimedAt: Instant,
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

    override fun deleteIfUnchanged(entity: GeneralOwnerEntity): Int =
        entity.claimRequestId?.let { requestId ->
            raw.deleteIfCurrentRequest(
                worldId = worldId.value,
                userId = entity.userId,
                generalId = entity.generalId,
                claimedAt = entity.claimedAt,
                claimRequestId = requestId,
            )
        } ?: raw.deleteIfCurrentLegacyReservation(
            worldId = worldId.value,
            userId = entity.userId,
            generalId = entity.generalId,
            claimedAt = entity.claimedAt,
        )

    private fun GeneralOwnerRecord.toValue(): GeneralOwnerEntity = GeneralOwnerEntity(
        generalId = generalId,
        userId = userId,
        claimedAt = claimedAt,
        claimRequestId = claimRequestId,
    )
}

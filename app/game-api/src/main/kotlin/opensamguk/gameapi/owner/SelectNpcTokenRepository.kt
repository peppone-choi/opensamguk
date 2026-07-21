package opensamguk.gameapi.owner

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.read.MetaJsonConverter
import org.hibernate.annotations.ColumnTransformer
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Instant
import java.util.Optional

class SelectNpcTokenEntity(
    var id: Long? = null,
    var ownerId: Long = 0,
    var validUntil: Instant = Instant.EPOCH,
    var pickMoreFrom: Instant = Instant.EPOCH,
    var pickResult: Map<String, Any?> = linkedMapOf(),
    var nonce: Int = 0,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

interface SelectNpcTokenRepository {
    fun findFirstByOwnerIdAndValidUntilAfterOrderByIdDesc(ownerId: Long, now: Instant): SelectNpcTokenEntity?

    fun findValidOtherTokens(ownerId: Long, now: Instant): List<SelectNpcTokenEntity>

    fun deleteOwnerOrExpired(ownerId: Long, now: Instant): Int

    fun save(entity: SelectNpcTokenEntity): SelectNpcTokenEntity

    fun flush()

    fun findById(id: Long): Optional<SelectNpcTokenEntity>
}

internal data class SelectNpcTokenRecordId(
    var worldId: Int = 0,
    var id: Long? = null,
) : Serializable

@Entity
@Table(name = "select_npc_token")
@IdClass(SelectNpcTokenRecordId::class)
internal class SelectNpcTokenRecord(
    @Id
    @Column(name = "world_id", nullable = false)
    var worldId: Int,

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "selectNpcTokenSequence")
    @SequenceGenerator(
        name = "selectNpcTokenSequence",
        sequenceName = "select_npc_token_id_seq",
        allocationSize = 1,
    )
    @Column(name = "id", nullable = false)
    var id: Long? = null,

    @Column(name = "owner_id", nullable = false)
    var ownerId: Long,

    @Column(name = "valid_until", nullable = false)
    var validUntil: Instant,

    @Column(name = "pick_more_from", nullable = false)
    var pickMoreFrom: Instant,

    @Convert(converter = MetaJsonConverter::class)
    @ColumnTransformer(write = "?::jsonb")
    @Column(name = "pick_result", nullable = false, columnDefinition = "jsonb")
    var pickResult: Map<String, Any?>,

    @Column(name = "nonce", nullable = false)
    var nonce: Int,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)

internal interface SelectNpcTokenRecordRepository :
    JpaRepository<SelectNpcTokenRecord, SelectNpcTokenRecordId> {
    fun findFirstByWorldIdAndOwnerIdAndValidUntilAfterOrderByIdDesc(
        worldId: Int,
        ownerId: Long,
        now: Instant,
    ): SelectNpcTokenRecord?

    @Query(
        "select t from SelectNpcTokenRecord t " +
            "where t.worldId = :worldId and t.ownerId <> :ownerId and t.validUntil >= :now",
    )
    fun findValidOtherTokens(
        @Param("worldId") worldId: Int,
        @Param("ownerId") ownerId: Long,
        @Param("now") now: Instant,
    ): List<SelectNpcTokenRecord>

    @Modifying
    @Query(
        "delete from SelectNpcTokenRecord t " +
            "where t.worldId = :worldId and (t.ownerId = :ownerId or t.validUntil < :now)",
    )
    fun deleteOwnerOrExpired(
        @Param("worldId") worldId: Int,
        @Param("ownerId") ownerId: Long,
        @Param("now") now: Instant,
    ): Int

    fun findByWorldIdAndId(worldId: Int, id: Long): SelectNpcTokenRecord?
}

@Repository
internal class WorldScopedSelectNpcTokenRepository(
    private val raw: SelectNpcTokenRecordRepository,
    processWorld: GameApiProcessWorld,
) : SelectNpcTokenRepository {
    private val worldId = processWorld.worldId

    override fun findFirstByOwnerIdAndValidUntilAfterOrderByIdDesc(
        ownerId: Long,
        now: Instant,
    ): SelectNpcTokenEntity? = raw
        .findFirstByWorldIdAndOwnerIdAndValidUntilAfterOrderByIdDesc(worldId.value, ownerId, now)
        ?.toValue()

    override fun findValidOtherTokens(ownerId: Long, now: Instant): List<SelectNpcTokenEntity> =
        raw.findValidOtherTokens(worldId.value, ownerId, now).map { it.toValue() }

    override fun deleteOwnerOrExpired(ownerId: Long, now: Instant): Int =
        raw.deleteOwnerOrExpired(worldId.value, ownerId, now)

    override fun save(entity: SelectNpcTokenEntity): SelectNpcTokenEntity {
        val saved = raw.save(
            SelectNpcTokenRecord(
                worldId = worldId.value,
                id = entity.id,
                ownerId = entity.ownerId,
                validUntil = entity.validUntil,
                pickMoreFrom = entity.pickMoreFrom,
                pickResult = entity.pickResult,
                nonce = entity.nonce,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
            ),
        )
        entity.id = saved.id
        return entity
    }

    override fun flush() = raw.flush()

    override fun findById(id: Long): Optional<SelectNpcTokenEntity> =
        Optional.ofNullable(raw.findByWorldIdAndId(worldId.value, id)?.toValue())

    private fun SelectNpcTokenRecord.toValue(): SelectNpcTokenEntity = SelectNpcTokenEntity(
        id = id,
        ownerId = ownerId,
        validUntil = validUntil,
        pickMoreFrom = pickMoreFrom,
        pickResult = pickResult,
        nonce = nonce,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

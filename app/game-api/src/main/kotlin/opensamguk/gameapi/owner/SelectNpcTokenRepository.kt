package opensamguk.gameapi.owner

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.gameapi.read.MetaJsonConverter
import org.hibernate.annotations.ColumnTransformer
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

@Entity
@Table(name = "select_npc_token")
class SelectNpcTokenEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "owner_id")
    var ownerId: Long = 0,

    @Column(name = "valid_until")
    var validUntil: Instant = Instant.EPOCH,

    @Column(name = "pick_more_from")
    var pickMoreFrom: Instant = Instant.EPOCH,

    @Convert(converter = MetaJsonConverter::class)
    @ColumnTransformer(write = "?::jsonb")
    @Column(name = "pick_result", columnDefinition = "jsonb")
    var pickResult: Map<String, Any?> = linkedMapOf(),

    @Column(name = "nonce")
    var nonce: Int = 0,

    @Column(name = "created_at")
    var createdAt: Instant = Instant.EPOCH,

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.EPOCH,
)

interface SelectNpcTokenRepository : JpaRepository<SelectNpcTokenEntity, Long> {
    fun findFirstByOwnerIdAndValidUntilAfterOrderByIdDesc(ownerId: Long, now: Instant): SelectNpcTokenEntity?

    @Query(
        "select t from SelectNpcTokenEntity t " +
            "where t.ownerId <> :ownerId and t.validUntil >= :now",
    )
    fun findValidOtherTokens(
        @Param("ownerId") ownerId: Long,
        @Param("now") now: Instant,
    ): List<SelectNpcTokenEntity>

    @Modifying
    @Query("delete from SelectNpcTokenEntity t where t.ownerId = :ownerId or t.validUntil < :now")
    fun deleteOwnerOrExpired(
        @Param("ownerId") ownerId: Long,
        @Param("now") now: Instant,
    ): Int
}

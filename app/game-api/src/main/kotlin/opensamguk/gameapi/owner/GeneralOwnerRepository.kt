package opensamguk.gameapi.owner

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * F2 Wave 1 — ACCOUNT-side user↔general possession ("장수 점유") link.
 *
 * This is the ONE table game-api owns and may INSERT/DELETE via JPA. It is NOT a game-state table, so
 * writing it does NOT violate the one-daemon-write rule (which bans game-api from mutating
 * `general`/`city`/`nation`/...). The npc-flip side-effect of possession (a `general` mutation) is a
 * separate, deferred concern — see [GeneralPossessionService].
 *
 * Invariants enforced by the V10 schema: PK on `general_id` (a general is claimed at most once) +
 * UNIQUE on `user_id` (one owned general per user on this server).
 */
@Entity
@Table(name = "general_owner")
class GeneralOwnerEntity(
    @Id
    @Column(name = "general_id")
    var generalId: Long = 0,

    @Column(name = "user_id")
    var userId: Long = 0,

    @Column(name = "claimed_at")
    var claimedAt: Instant = Instant.EPOCH,
)

interface GeneralOwnerRepository : JpaRepository<GeneralOwnerEntity, Long> {
    /** The general this user owns on this server, or null (→ UI shows 장수선택/빙의). */
    fun findByUserId(userId: Long): GeneralOwnerEntity?

    /** Whether the given general is already claimed by anyone (the "unowned" guard). */
    fun existsByGeneralId(generalId: Long): Boolean

    /** All claimed general ids (to exclude from the claimable candidate pool). */
    @Suppress("FunctionName")
    fun findAllByOrderByGeneralIdAsc(): List<GeneralOwnerEntity>
}

package opensamguk.infra.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * JPA entity for `ng_betting` table (PHP `ng_betting`, schema.sql:644).
 *
 * Per-general bet rows. `user_id` is nullable (system/NPC bets carry no user).
 * `betting_type` is a JSON int-array key string (e.g. `[1,2]`).
 */
@Entity
@Table(name = "ng_betting")
class NgBettingEntity(
    @Column(name = "betting_id", nullable = false)
    var bettingId: Int,

    @Column(name = "general_id", nullable = false)
    var generalId: Int,

    @Column(name = "user_id")
    var userId: Int? = null,

    @Column(name = "betting_type", nullable = false)
    var bettingType: String,

    @Column(name = "amount", nullable = false)
    var amount: Int,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,
)

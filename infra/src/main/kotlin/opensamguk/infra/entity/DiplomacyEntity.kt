package opensamguk.infra.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * JPA entity for `diplomacy` table (PHP `diplomacy`, schema.sql:131).
 *
 * Directional pair: `src_nation_id` → `dest_nation_id` with `state_code` and `term`.
 * `is_dead` and `is_showing` are boolean flags; `meta` is jsonb.
 */
@Entity
@Table(name = "diplomacy")
class DiplomacyEntity(
    @Column(name = "src_nation_id", nullable = false)
    var srcNationId: Int,

    @Column(name = "dest_nation_id", nullable = false)
    var destNationId: Int,

    @Column(name = "state_code", nullable = false)
    var stateCode: Int,

    @Column(name = "term", nullable = false)
    var term: Int = 0,

    @Column(name = "is_dead", nullable = false)
    var isDead: Boolean = false,

    @Column(name = "is_showing", nullable = false)
    var isShowing: Boolean = true,

    @Column(name = "meta", nullable = false, columnDefinition = "jsonb")
    var meta: String = "{}",

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,
)

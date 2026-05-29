package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.logic.domain.Diplomacy
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Read-only JPA mapping of the `diplomacy` row for the PRECHECK path (game-api ONLY — §7).
 *
 * V1 baseline `diplomacy` keys on a `serial id` and stores a directional pair `(src_nation_id,
 * dest_nation_id, state_code, term)`. The logic [Diplomacy] is the 4-field slice (`me`/`you`/`state`/
 * `term`); the dest-* constraint family (C-DEST) consumes the state semantics. `is_dead`/`is_showing`/
 * `meta` are not part of the precheck slice and are not mapped.
 */
@Entity
@Table(name = "diplomacy")
class DiplomacyReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "src_nation_id")
    var srcNationId: Int = 0,

    @Column(name = "dest_nation_id")
    var destNationId: Int = 0,

    @Column(name = "state_code")
    var stateCode: Int = 0,

    @Column(name = "term")
    var term: Int = 0,
) {
    /** Materialize into the shared `:logic` [Diplomacy]. */
    fun toLogic(): Diplomacy = Diplomacy(
        me = srcNationId,
        you = destNationId,
        state = stateCode,
        term = term,
    )
}

interface DiplomacyReadRepository : JpaRepository<DiplomacyReadEntity, Int> {
    /** All directional rows where [srcNationId] is the source nation (the actor nation's diplomacy). */
    fun findBySrcNationId(srcNationId: Int): List<DiplomacyReadEntity>
}

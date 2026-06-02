package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

/**
 * F4 READ-only JPA mapping of the `nation_turn` row (V1 baseline + V2 `brief`) for the 사령부
 * (chief-reserved) page — the reserved nation commands per officer level.
 *
 * game-api ONLY (§7); never written here. The reserved turns are keyed
 * `(nation_id, officer_level, turn_idx)`; the page renders each chief post's reserved-turn list.
 */
@Entity
@Table(name = "nation_turn")
class NationTurnReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "nation_id")
    var nationId: Int = 0,

    @Column(name = "officer_level")
    var officerLevel: Int = 0,

    @Column(name = "turn_idx")
    var turnIdx: Int = 0,

    @Column(name = "action_code")
    var actionCode: String = "",

    @Column(name = "brief")
    var brief: String = "",
)

interface NationTurnReadRepository : JpaRepository<NationTurnReadEntity, Int> {
    /** All reserved nation-command rows for a nation, ordered by officer level then turn index. */
    fun findByNationIdOrderByOfficerLevelDescTurnIdxAsc(nationId: Int): List<NationTurnReadEntity>
}

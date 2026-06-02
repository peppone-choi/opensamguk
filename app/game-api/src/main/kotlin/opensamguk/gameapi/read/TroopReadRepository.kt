package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

/**
 * F4 READ-only JPA mapping of the `troop` row (V1 baseline) for the 부대 편성 page.
 *
 * The `troop` table EXISTS but carries ZERO rows in the fresh scenario_1010 seed — the controller
 * returns an empty list gracefully (200, no fabrication). PK is `troop_leader` (the leader general id).
 *
 * game-api ONLY (§7); never written here.
 */
@Entity
@Table(name = "troop")
class TroopReadEntity(
    @Id
    @Column(name = "troop_leader")
    var troopLeader: Int = 0,

    @Column(name = "nation")
    var nation: Int = 0,

    @Column(name = "name")
    var name: String = "",
)

interface TroopReadRepository : JpaRepository<TroopReadEntity, Int> {
    /** Troops in a nation (the nation roster's troop list), ordered by leader id. */
    fun findByNationOrderByTroopLeaderAsc(nation: Int): List<TroopReadEntity>
}

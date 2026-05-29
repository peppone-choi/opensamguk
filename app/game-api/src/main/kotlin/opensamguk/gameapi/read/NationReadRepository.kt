package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.logic.domain.Nation
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Read-only JPA mapping of the `nation` row for the PRECHECK path (game-api ONLY — §7).
 *
 * The logic [Nation] is the minimal slice shape: `id, level, capitalCityId` (`capital_city_id` is a
 * nullable column in the V1 baseline).
 */
@Entity
@Table(name = "nation")
class NationReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "level")
    var level: Int = 0,

    @Column(name = "capital_city_id")
    var capitalCityId: Int? = null,
) {
    /** Materialize into the shared `:logic` [Nation]. */
    fun toLogic(): Nation = Nation(
        id = id,
        level = level,
        capitalCityId = capitalCityId,
    )
}

interface NationReadRepository : JpaRepository<NationReadEntity, Int>

package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.logic.domain.General
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Read-only JPA mapping of the `general` row for the PRECHECK path (game-api ONLY — the
 * legitimate JPA use per §7). The daemon write path never touches JPA.
 *
 * Mirrors the column subset the precheck slice needs (same columns as the JDBC [GeneralRowMapper]):
 * `id, nation_id, city_id, leadership, strength, intel, injury, experience, dedication,
 * officer_level, gold, rice, meta`.
 *
 * The V1 baseline has NO `intel_exp`/`explevel`/`max_domestic_critical` column — those are read from
 * `meta` (OQ5), so this entity declares NO such column. `experience`/`dedication` are integer columns
 * widened to the logic `Double` raw accumulators (the row mapper does the same int->Double widening).
 *
 * Only the columns the entity declares are validated against the table (`ddl-auto: validate`);
 * the remaining `general` columns are simply not mapped here.
 */
@Entity
@Table(name = "general")
class GeneralReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "nation_id")
    var nationId: Int = 0,

    @Column(name = "city_id")
    var cityId: Int = 0,

    @Column(name = "leadership")
    var leadership: Int = 0,

    @Column(name = "strength")
    var strength: Int = 0,

    @Column(name = "intel")
    var intel: Int = 0,

    @Column(name = "injury")
    var injury: Int = 0,

    @Column(name = "experience")
    var experience: Int = 0,

    @Column(name = "dedication")
    var dedication: Int = 0,

    @Column(name = "officer_level")
    var officerLevel: Int = 0,

    @Column(name = "gold")
    var gold: Int = 0,

    @Column(name = "rice")
    var rice: Int = 0,

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "meta", columnDefinition = "jsonb")
    var meta: Map<String, Any?> = linkedMapOf(),
) {
    /** Materialize the read row into the shared `:logic` [General] (int->Double widen on exp/ded). */
    fun toLogic(): General = General(
        id = id,
        nationId = nationId,
        cityId = cityId,
        leadership = leadership,
        strength = strength,
        intel = intel,
        injury = injury,
        experience = experience.toDouble(),
        dedication = dedication.toDouble(),
        officerLevel = officerLevel,
        gold = gold,
        rice = rice,
        meta = meta,
    )
}

interface GeneralReadRepository : JpaRepository<GeneralReadEntity, Int>

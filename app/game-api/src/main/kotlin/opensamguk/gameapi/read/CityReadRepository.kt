package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.logic.domain.City
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Read-only JPA mapping of the `city` row for the PRECHECK path (game-api ONLY — §7).
 *
 * Column subset (same as the JDBC [CityRowMapper]): `id, nation_id, level, comm, comm_max, agri,
 * agri_max, supply_state, front_state, trust, meta`.
 *
 * `trust` is INTEGER in the V1 baseline but the logic `City.trust` is `Double` (the che math uses
 * `trust/100.0` & `trust/80.0`), so the read mapping WIDENS int -> Double on materialize.
 */
@Entity
@Table(name = "city")
class CityReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "nation_id")
    var nationId: Int = 0,

    @Column(name = "level")
    var level: Int = 0,

    @Column(name = "comm")
    var commerce: Int = 0,

    @Column(name = "comm_max")
    var commerceMax: Int = 0,

    @Column(name = "agri")
    var agriculture: Int = 0,

    @Column(name = "agri_max")
    var agricultureMax: Int = 0,

    @Column(name = "supply_state")
    var supplyState: Int = 0,

    @Column(name = "front_state")
    var frontState: Int = 0,

    @Column(name = "trust")
    var trust: Int = 0,

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "meta", columnDefinition = "jsonb")
    var meta: Map<String, Any?> = linkedMapOf(),
) {
    /** Materialize into the shared `:logic` [City] (int `trust` -> Double widen). */
    fun toLogic(): City = City(
        id = id,
        nationId = nationId,
        level = level,
        commerce = commerce,
        commerceMax = commerceMax,
        agriculture = agriculture,
        agricultureMax = agricultureMax,
        supplyState = supplyState,
        frontState = frontState,
        trust = trust.toDouble(),
        meta = meta,
    )
}

interface CityReadRepository : JpaRepository<CityReadEntity, Int>

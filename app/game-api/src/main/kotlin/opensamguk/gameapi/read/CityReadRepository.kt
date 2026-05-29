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
 * Column set (same as the JDBC [CityRowMapper]): the scalar surface + the P2 develop/defense columns
 * (`secu, secu_max, def, def_max, wall, wall_max, pop, pop_max, trade, region`) + `meta`.
 * (There is NO `city.tech` — tech is a NATION stat.)
 *
 * `trust` is INTEGER in the V1 baseline but the logic `City.trust` is `Double` (the che math uses
 * `trust/100.0` & `trust/80.0`), so the read mapping WIDENS int -> Double on materialize. `trade` is
 * `integer NOT NULL DEFAULT 100` in the baseline (never null in a seeded row); the logic field is
 * nullable `Int?`, so the materialize passes the read int through as a (non-null) `Int?`.
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

    @Column(name = "secu")
    var security: Int = 0,

    @Column(name = "secu_max")
    var securityMax: Int = 0,

    @Column(name = "def")
    var defense: Int = 0,

    @Column(name = "def_max")
    var defenseMax: Int = 0,

    @Column(name = "wall")
    var wall: Int = 0,

    @Column(name = "wall_max")
    var wallMax: Int = 0,

    @Column(name = "pop")
    var population: Int = 0,

    @Column(name = "pop_max")
    var populationMax: Int = 0,

    @Column(name = "trade")
    var trade: Int = 100,

    @Column(name = "region")
    var region: Int = 0,

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
        security = security,
        securityMax = securityMax,
        defense = defense,
        defenseMax = defenseMax,
        wall = wall,
        wallMax = wallMax,
        population = population,
        populationMax = populationMax,
        trade = trade,
        region = region,
        meta = meta,
    )
}

interface CityReadRepository : JpaRepository<CityReadEntity, Int>

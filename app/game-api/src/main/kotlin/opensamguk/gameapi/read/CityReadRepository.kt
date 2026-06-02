package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.logic.domain.City
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * Read-only JPA mapping of the `city` row for the PRECHECK path (game-api ONLY — §7).
 *
 * Column set (same as the JDBC [CityRowMapper]): the scalar surface + the P2 develop/defense columns
 * (`secu, secu_max, def, def_max, wall, wall_max, pop, pop_max, trade, region`) + `meta`.
 * (There is NO `city.tech` — tech is a NATION stat.)
 *
 * `trust` is `double precision` after the FC1 V4 migration (legacy schema is FLOAT) and the logic
 * `City.trust` is `Double` (the che math uses `trust/100.0` & `trust/80.0`), so the read maps the
 * column straight through as a `Double`. `trade` is NULLABLE after FC1 (RandomizeCityTradeRate writes
 * NULL for disabled cities); the logic field is nullable `Int?`, so a NULL column materializes to null.
 */
@Entity
@Table(name = "city")
class CityReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "name")
    var name: String = "",

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
    var trust: Double = 0.0,

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
    var trade: Int? = null,

    @Column(name = "region")
    var region: Int = 0,

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "conflict", columnDefinition = "jsonb")
    var conflict: Map<String, Any?> = linkedMapOf(),

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "meta", columnDefinition = "jsonb")
    var meta: Map<String, Any?> = linkedMapOf(),
) {
    /** Materialize into the shared `:logic` [City] (double `trust`, nullable `trade`). */
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
        trust = trust,
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

interface CityReadRepository : JpaRepository<CityReadEntity, Int> {
    /** F2: cities owned by a nation (세력 도시 / my-cities), ordered by id. */
    fun findByNationIdOrderByIdAsc(nationId: Int): List<CityReadEntity>

    /** F2 front-info / nation-detail city count. */
    fun countByNationId(nationId: Int): Long

    /**
     * F3 kingdoms ranking — `pop` = SUM(city.pop) over a nation's cities (there is NO `nation.pop`
     * column). COALESCE so a nation with no cities materializes 0, not null.
     */
    @Query("select coalesce(sum(c.population), 0) from CityReadEntity c where c.nationId = :nationId")
    fun sumPopulationByNationId(@Param("nationId") nationId: Int): Long
}

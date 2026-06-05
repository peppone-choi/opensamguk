package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.logic.domain.Nation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * Read-only JPA mapping of the `nation` row for the PRECHECK path (game-api ONLY — §7).
 *
 * Full P2 shape (same columns as the JDBC [opensamguk.infra.persistence.NationRowMapper]): `id, name,
 * color, capital_city_id, gold, rice, tech, level, type_code, meta`. `tech` is `double precision`.
 * `gennum`/`capset` have NO dedicated columns — they ride the `meta` jsonb and are read back from it
 * on materialize (`capital_city_id` is nullable in the V1 baseline).
 */
@Entity
@Table(name = "nation")
class NationReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "name")
    var name: String = "",

    @Column(name = "color")
    var color: String = "",

    @Column(name = "capital_city_id")
    var capitalCityId: Int? = null,

    @Column(name = "gold")
    var gold: Int = 0,

    @Column(name = "rice")
    var rice: Int = 0,

    @Column(name = "tech")
    var tech: Double = 0.0,

    @Column(name = "level")
    var level: Int = 0,

    @Column(name = "type_code")
    var typeCode: String = "che_중립",

    // W3 — power → 국력. V8__nation_power.sql `ALTER TABLE nation ADD COLUMN power INTEGER`(실 컬럼).
    // FrontNationInfo.power 표시용(1줄 add, ddl-auto validate 유지). GeneralReadRepository:168의
    // "no power column → SUM(crew) proxy" 주석은 V8 이후 stale(W3_FrontNationInfo §6).
    @Column(name = "power")
    var power: Int = 0,

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "meta", columnDefinition = "jsonb")
    var meta: Map<String, Any?> = linkedMapOf(),
) {
    /** Materialize into the shared `:logic` [Nation] (gennum/capset read back from `meta`). */
    fun toLogic(): Nation = Nation(
        id = id,
        level = level,
        capitalCityId = capitalCityId,
        name = name,
        color = color,
        typeCode = typeCode,
        gold = gold,
        rice = rice,
        power = power,
        tech = tech,
        gennum = (meta["gennum"] as? Number)?.toInt() ?: 0,
        capset = (meta["capset"] as? Number)?.toInt() ?: 0,
        meta = meta,
    )
}

/**
 * W3-F4 — 국가별 인구(도시) 집계 1행 프로젝션(N+1-safe grouped query 결과). FrontNationInfo의
 * `population.{cityCnt, now, max}`에 매핑된다.
 *  - nationId : 국가 id (group key)
 *  - cityCnt  : 보유 도시 수 COUNT(city)
 *  - now      : SUM(city.pop) — 현재 인구
 *  - max      : SUM(city.pop_max) — 최대 인구
 * 한 도시도 없는 국가는 group이 생성되지 않으므로 결과에 빠진다(호출부가 0으로 보정).
 */
interface NationPopulationAggregate {
    val nationId: Int
    val cityCnt: Long
    val now: Long
    val max: Long
}

/**
 * W3-F4 — 국가별 병력(장수) 집계 1행 프로젝션. FrontNationInfo의 `crew.{generalCnt, now, max}`에 매핑.
 *  - nationId    : 국가 id (group key)
 *  - generalCnt  : 소속 장수 수 COUNT(general)
 *  - now         : SUM(general.crew) — 현재 병력
 *  - max         : SUM(general.leadership) * 100 — 통솔 기반 최대 병력 수용량(PHP 패러티)
 * `npc_state = 5`(사망/임관대기 등 비활성)는 제외한다(FrontNationInfo spec §1). 장수가 없는
 * 국가는 group이 생성되지 않는다(호출부가 0으로 보정).
 */
interface NationCrewAggregate {
    val nationId: Int
    val generalCnt: Long
    val now: Long
    val max: Long
}

interface NationReadRepository : JpaRepository<NationReadEntity, Int> {

    /**
     * W3-F4 — 모든 국가의 인구 집계를 1회 grouped query로(N+1 방지). 도시가 0개인 국가는 결과에
     * 없으므로 호출부에서 `Map<nationId, agg>`로 색인 후 결손 국가를 {0,0,0}으로 보정한다.
     * `nation_id = 0`(재야/공백지 도시)도 그룹에 포함되니 호출부가 필요 시 필터링한다.
     */
    @Query(
        "select c.nationId as nationId, count(c) as cityCnt, " +
            "coalesce(sum(c.population), 0) as now, coalesce(sum(c.populationMax), 0) as max " +
            "from CityReadEntity c group by c.nationId",
    )
    fun aggregatePopulationByNation(): List<NationPopulationAggregate>

    /**
     * W3-F4 — 모든 국가의 병력 집계를 1회 grouped query로(N+1 방지). `npc_state = 5` 장수 제외.
     * `max`는 SUM(leadership)*100(통솔 기반 수용량, FrontNationInfo spec §1). 장수가 0명인 국가는
     * 결과에 없으므로 호출부가 {0,0,0}으로 보정. `nation_id = 0`(재야 장수)도 그룹에 포함된다.
     */
    @Query(
        "select g.nationId as nationId, count(g) as generalCnt, " +
            "coalesce(sum(g.crew), 0) as now, coalesce(sum(g.leadership), 0) * 100 as max " +
            "from GeneralReadEntity g where g.npcState <> 5 group by g.nationId",
    )
    fun aggregateCrewByNation(): List<NationCrewAggregate>

    /**
     * W3-F4 — 단일 국가용 인구 집계(상세 페이지에서 한 국가만 필요할 때). 도시 0개면 빈 결과.
     */
    @Query(
        "select c.nationId as nationId, count(c) as cityCnt, " +
            "coalesce(sum(c.population), 0) as now, coalesce(sum(c.populationMax), 0) as max " +
            "from CityReadEntity c where c.nationId = :nationId group by c.nationId",
    )
    fun aggregatePopulationOfNation(@Param("nationId") nationId: Int): NationPopulationAggregate?

    /**
     * W3-F4 — 단일 국가용 병력 집계. `npc_state = 5` 제외. 장수 0명이면 빈 결과.
     */
    @Query(
        "select g.nationId as nationId, count(g) as generalCnt, " +
            "coalesce(sum(g.crew), 0) as now, coalesce(sum(g.leadership), 0) * 100 as max " +
            "from GeneralReadEntity g where g.nationId = :nationId and g.npcState <> 5 group by g.nationId",
    )
    fun aggregateCrewOfNation(@Param("nationId") nationId: Int): NationCrewAggregate?
}

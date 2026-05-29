package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.logic.domain.Nation
import org.springframework.data.jpa.repository.JpaRepository

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
        tech = tech,
        gennum = (meta["gennum"] as? Number)?.toInt() ?: 0,
        capset = (meta["capset"] as? Number)?.toInt() ?: 0,
        meta = meta,
    )
}

interface NationReadRepository : JpaRepository<NationReadEntity, Int>

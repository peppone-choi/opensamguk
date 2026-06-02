package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * Read-only JPA mapping of the `general` row for the PRECHECK path (game-api ONLY — the
 * legitimate JPA use per §7). The daemon write path never touches JPA.
 *
 * Mirrors the column set the precheck slice needs (same columns as the JDBC [GeneralRowMapper]):
 * the scalar surface + the P2 military/equip columns (`crew, crew_type_id, train, atmos, troop_id,
 * weapon_code, book_code, horse_code, item_code, npc_state`) + the `last_turn`/`meta` jsonb.
 *
 * The V1 baseline has NO `intel_exp`/`explevel`/`leadership_exp`/`strength_exp`/`dedlevel`/
 * `max_domestic_critical` column — those are read from `meta` (OQ5), so this entity declares NO such
 * column. `experience`/`dedication` (and `train`/`atmos`) are integer columns widened to the logic
 * `Double` raw accumulators (the row mapper does the same int->Double widening). Read-path order need
 * not be preserved — [MetaJsonConverter] decodes `meta`/`last_turn` to insertion-ordered maps anyway.
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

    @Column(name = "name")
    var name: String = "",

    @Column(name = "picture")
    var picture: String? = null,

    @Column(name = "image_server")
    var imageServer: Int = 0,

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

    @Column(name = "crew")
    var crew: Int = 0,

    @Column(name = "crew_type_id")
    var crewTypeId: Int = 0,

    @Column(name = "train")
    var train: Int = 0,

    @Column(name = "atmos")
    var atmos: Int = 0,

    @Column(name = "troop_id")
    var troopId: Int = 0,

    @Column(name = "horse_code")
    var horseCode: String = "None",

    @Column(name = "weapon_code")
    var weaponCode: String = "None",

    @Column(name = "book_code")
    var bookCode: String = "None",

    @Column(name = "item_code")
    var itemCode: String = "None",

    @Column(name = "npc_state")
    var npcState: Int = 0,

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "last_turn", columnDefinition = "jsonb")
    var lastTurn: Map<String, Any?> = linkedMapOf(),

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "meta", columnDefinition = "jsonb")
    var meta: Map<String, Any?> = linkedMapOf(),
) {
    /** Materialize the read row into the shared `:logic` [General] (int->Double widen on exp/ded/train/atmos). */
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
        crew = crew,
        train = train.toDouble(),
        atmos = atmos.toDouble(),
        crewTypeId = crewTypeId,
        troop = troopId,
        horse = horseCode,
        weapon = weaponCode,
        book = bookCode,
        item = itemCode,
        npcType = npcState,
        lastTurn = LastTurn.fromRaw(lastTurn),
        meta = meta,
    )
}

interface GeneralReadRepository : JpaRepository<GeneralReadEntity, Int> {
    /** F2: generals in a nation (세력 장수 / my-generals), ordered by officer level desc then id. */
    fun findByNationIdOrderByOfficerLevelDescIdAsc(nationId: Int): List<GeneralReadEntity>

    /** F2: claimable NPC candidate pool (legacy `npc=2`), ordered by id for a stable list. */
    fun findByNpcStateOrderByIdAsc(npcState: Int): List<GeneralReadEntity>

    /** F2: the ruler/boss lookup — the highest officer in a nation (인사부 my-boss). */
    fun findFirstByNationIdOrderByOfficerLevelDesc(nationId: Int): GeneralReadEntity?

    /** F2 front-info counts. */
    fun countByNpcState(npcState: Int): Long
    fun countByNationId(nationId: Int): Long

    /** F2 Wave 6: officers stationed in a city (city-detail panel — cheap count, no row load). */
    fun countByCityId(cityId: Int): Long

    /** F4: members of a troop (the 부대 편성 member list — generals whose troop_id == the leader id). */
    fun findByTroopIdOrderByOfficerLevelDescIdAsc(troopId: Int): List<GeneralReadEntity>

    /**
     * F3 kingdoms ranking — `power` proxy (병력 column) = SUM(general.crew) over a nation's generals.
     * The legacy `a_kingdomList.php` sorts by a stored `nation.power` that has no column in this schema;
     * SUM(crew) is the documented faithful proxy (OQ-3). COALESCE so a general-less nation → 0.
     */
    @Query("select coalesce(sum(g.crew), 0) from GeneralReadEntity g where g.nationId = :nationId")
    fun sumCrewByNationId(@Param("nationId") nationId: Int): Long
}

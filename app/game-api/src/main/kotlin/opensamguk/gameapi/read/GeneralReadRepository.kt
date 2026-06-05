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

    // W3-F1 — 기존 V1/V6 컬럼을 READ DTO enrichment용으로 추가 매핑(1줄 add, ddl-auto validate 유지).
    // special_code → FrontGeneralInfo.specialDomestic(내정 특기), special2_code → specialWar(전투 특기).
    // V1 baseline `general` 컬럼(`text NOT NULL DEFAULT 'None'`).
    @Column(name = "special_code")
    var specialCode: String = "None",

    @Column(name = "special2_code")
    var special2Code: String = "None",

    // personal_code → FrontGeneralInfo.personal(성격). V1 `text NOT NULL DEFAULT 'None'`.
    @Column(name = "personal_code")
    var personalCode: String = "None",

    // penalty → V1 baseline에서 jsonb 컬럼(`jsonb NOT NULL DEFAULT '{}'`)이므로 스칼라가 아니라
    // MetaJsonConverter로 디코드(삽입순서 보존 맵). FrontGeneralInfo penalty 표시용.
    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "penalty", columnDefinition = "jsonb")
    var penalty: Map<String, Any?> = linkedMapOf(),

    // officer_city → 태수/군사/종사가 다스리는 도시 id. V6__p4_war_columns.sql:16
    // (`integer NOT NULL DEFAULT 0`). GeneralList 소속 도시 컬럼용.
    @Column(name = "officer_city")
    var officerCity: Int = 0,

    // turn_time → 장수 턴 시각(timestamptz). ChiefCenter/GeneralList의 TURNTIME_FULL 포맷 원천.
    // 다른 read 엔티티(BoardReadEntity.created_at, DiplomacyLetter.date)와 동일하게 `Instant`로 매핑
    // — Hibernate가 timestamptz를 UTC Instant로 정규화(엔진 WorldSnapshotLoader와 같은 좌표계).
    @Column(name = "turn_time")
    var turnTime: java.time.Instant? = null,

    // recent_war_time → GeneralList의 P1 `recent_war` 컬럼(최근 전투 시각, nullable). V1 baseline
    // `recent_war_time timestamptz`(NULL 허용). PHP는 `substr(recent_war, 0, 19)`로 노출하므로
    // turn_time과 동일하게 `Instant`로 매핑 후 TurnTimeFormatter.full(앞 19자)로 변환한다.
    @Column(name = "recent_war_time")
    var recentWarTime: java.time.Instant? = null,

    // age → 장수 나이. V1 baseline `age integer NOT NULL DEFAULT 20`(meta가 아닌 실제 컬럼).
    // GeneralList P0 `age` 컬럼용.
    @Column(name = "age")
    var age: Int = 20,

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

    /**
     * W3 GeneralList — 세력 장수 목록의 서버측 정렬. PHP `GeneralList.php:175`의
     * `SELECT ... FROM general WHERE nation = %i ORDER BY turntime ASC`를 그대로 이식.
     *
     * `turn_time`이 NULL인 행은 PHP MySQL의 `ORDER BY ... ASC`(NULL이 가장 앞)와 동형이 되도록
     * `NULLS FIRST`로 둔다(opensamguk seed는 turn_time NOT NULL이라 1010에선 무차이). 동률 시
     * 안정 정렬 보장을 위해 id ASC를 2차 키로 둔다(PHP 8.0+ stable sort 선례 — §6, 비-안정 2차
     * 비교자 추가 금지에 부합: 결정적 출력 목적의 tie-break일 뿐 패러티 순서를 바꾸지 않음).
     */
    @Query(
        "select g from GeneralReadEntity g where g.nationId = :nationId " +
            "order by g.turnTime asc nulls first, g.id asc",
    )
    fun findByNationIdOrderByTurnTimeAsc(@Param("nationId") nationId: Int): List<GeneralReadEntity>

    /** F2: claimable NPC candidate pool (legacy `npc=2`), ordered by id for a stable list. */
    fun findByNpcStateOrderByIdAsc(npcState: Int): List<GeneralReadEntity>

    /** F2: the ruler/boss lookup — the highest officer in a nation (인사부 my-boss). */
    fun findFirstByNationIdOrderByOfficerLevelDesc(nationId: Int): GeneralReadEntity?

    /** F2 front-info counts. */
    fun countByNpcState(npcState: Int): Long
    fun countByNationId(nationId: Int): Long

    /**
     * W3 FrontGlobalInfo — NPC 장수 수(`npc_state > 0`). PHP `SELECT npc, count(no) FROM general GROUP BY npc`의
     * NPC 합과 동치(user는 `countByNpcState(0)`). createdUserCnt/createdNPCCnt 분리 카운트용.
     */
    fun countByNpcStateGreaterThan(npcState: Int): Long

    /** F2 Wave 6: officers stationed in a city (city-detail panel — cheap count, no row load). */
    fun countByCityId(cityId: Int): Long

    /** F4: members of a troop (the 부대 편성 member list — generals whose troop_id == the leader id). */
    fun findByTroopIdOrderByOfficerLevelDescIdAsc(troopId: Int): List<GeneralReadEntity>

    /**
     * W3 FrontNationInfo `topChiefs` — PHP `SELECT officer_level, no, name, npc FROM general
     * WHERE nation = %i AND officer_level >= 11`(GetFrontInfo.php:270-273). 수뇌(군주/참모급) 목록.
     * PHP는 무순서지만 deterministic 출력을 위해 officer_level desc, id asc 정렬(표시 전용, 패러티 무관).
     */
    fun findByNationIdAndOfficerLevelGreaterThanEqualOrderByOfficerLevelDescIdAsc(
        nationId: Int,
        officerLevel: Int,
    ): List<GeneralReadEntity>

    /**
     * F3 kingdoms ranking — `power` proxy (병력 column) = SUM(general.crew) over a nation's generals.
     * The legacy `a_kingdomList.php` sorts by a stored `nation.power` that has no column in this schema;
     * SUM(crew) is the documented faithful proxy (OQ-3). COALESCE so a general-less nation → 0.
     */
    @Query("select coalesce(sum(g.crew), 0) from GeneralReadEntity g where g.nationId = :nationId")
    fun sumCrewByNationId(@Param("nationId") nationId: Int): Long

    /**
     * W9 (9a) `getWorldMap`의 `shownByGeneralList` 원천 — 아국 장수가 소재한 도시 id 집합.
     * PHP `func_map.php:135-138`: `select distinct city from general where nation=%i`.
     * PHP는 무순서지만 deterministic 출력을 위해 cityId-ascending 정렬(표시 전용, 패러티 무관).
     */
    @Query("select distinct g.cityId from GeneralReadEntity g where g.nationId = :nationId order by g.cityId asc")
    fun findDistinctCityIdByNationId(@Param("nationId") nationId: Int): List<Int>
}

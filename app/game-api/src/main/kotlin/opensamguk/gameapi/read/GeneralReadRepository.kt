package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository as SpringDataRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional

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

    @Column(name = "world_id")
    var worldId: Int = 0,

    @Column(name = "user_id")
    var userId: String? = null,

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

    // 정치/매력 (RTK14 divergence) — V16 마이그레이션 INT 컬럼(NOT NULL DEFAULT 50). JPA auto-select.
    @Column(name = "politics")
    var politics: Int = 50,

    @Column(name = "charm")
    var charm: Int = 50,

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

interface GeneralReadRawRepository : SpringDataRepository<GeneralReadEntity, Int> {
    fun findByWorldIdAndId(worldId: Int, id: Int): Optional<GeneralReadEntity>
    fun findByWorldId(worldId: Int): List<GeneralReadEntity>
    fun countByWorldId(worldId: Int): Long

    /** F2: generals in a nation (세력 장수 / my-generals), ordered by officer level desc then id. */
    fun findByWorldIdAndNationIdOrderByOfficerLevelDescIdAsc(worldId: Int, nationId: Int): List<GeneralReadEntity>

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
        "select g from GeneralReadEntity g where g.worldId = :worldId and g.nationId = :nationId " +
            "order by g.turnTime asc nulls first, g.id asc",
    )
    fun findByWorldIdAndNationIdOrderByTurnTimeAsc(
        @Param("worldId") worldId: Int,
        @Param("nationId") nationId: Int,
    ): List<GeneralReadEntity>

    /** F2: claimable NPC candidate pool (legacy `npc=2`), ordered by id for a stable list. */
    fun findByWorldIdAndNpcStateOrderByIdAsc(worldId: Int, npcState: Int): List<GeneralReadEntity>

    /** F2: the ruler/boss lookup — the highest officer in a nation (인사부 my-boss). */
    fun findFirstByWorldIdAndNationIdOrderByOfficerLevelDesc(worldId: Int, nationId: Int): GeneralReadEntity?

    /** F2 front-info counts. */
    fun countByWorldIdAndNpcState(worldId: Int, npcState: Int): Long
    fun countByWorldIdAndNationId(worldId: Int, nationId: Int): Long

    /**
     * D5 — 참여 가능 사용자 수. PHP `GetBettingDetail.php`의
     * `SELECT count(no) FROM general WHERE npc < 2`.
     */
    fun countByWorldIdAndNpcStateLessThan(worldId: Int, npcState: Int): Long

    /**
     * W0-2(P0-25) — 소유자 확인(CheckOwner) 대상 장수 목록. PHP `v_inheritPoint.php:19-22`
     * `SELECT no, name FROM general WHERE npc < 2`(유저/빙의 장수). id ASC로 안정 순서 고정.
     */
    fun findByWorldIdAndNpcStateLessThanOrderByIdAsc(worldId: Int, npcState: Int): List<GeneralReadEntity>

    /**
     * W3 FrontGlobalInfo — NPC 장수 수(`npc_state > 0`). PHP `SELECT npc, count(no) FROM general GROUP BY npc`의
     * NPC 합과 동치(user는 `countByNpcState(0)`). createdUserCnt/createdNPCCnt 분리 카운트용.
     */
    fun countByWorldIdAndNpcStateGreaterThan(worldId: Int, npcState: Int): Long

    /** F2 Wave 6: officers stationed in a city (city-detail panel — cheap count, no row load). */
    fun countByWorldIdAndCityId(worldId: Int, cityId: Int): Long

    /**
     * b_currentCity(도시정보) 장수 상세 테이블 원천 — PHP `b_currentCity.php:217`의
     * `SELECT ... from general where city=%i order by turntime`를 그대로 이식.
     *
     * 정렬은 `turn_time ASC`(턴 순). NULL은 `NULLS FIRST`(PHP MySQL ORDER BY ASC의 NULL-앞 동형;
     * 1010 seed는 turn_time NOT NULL이라 무차이). 동률 안정성 위해 id ASC 2차 키
     * (§6 결정적 tie-break — 패러티 순서 불변).
     */
    @Query(
        "select g from GeneralReadEntity g where g.worldId = :worldId and g.cityId = :cityId " +
            "order by g.turnTime asc nulls first, g.id asc",
    )
    fun findByWorldIdAndCityIdOrderByTurnTimeAsc(
        @Param("worldId") worldId: Int,
        @Param("cityId") cityId: Int,
    ): List<GeneralReadEntity>

    /** F4: members of a troop (the 부대 편성 member list — generals whose troop_id == the leader id). */
    fun findByWorldIdAndTroopIdOrderByOfficerLevelDescIdAsc(worldId: Int, troopId: Int): List<GeneralReadEntity>

    /**
     * W3 FrontNationInfo `topChiefs` — PHP `SELECT officer_level, no, name, npc FROM general
     * WHERE nation = %i AND officer_level >= 11`(GetFrontInfo.php:270-273). 수뇌(군주/참모급) 목록.
     * PHP는 무순서지만 deterministic 출력을 위해 officer_level desc, id asc 정렬(표시 전용, 패러티 무관).
     */
    fun findByWorldIdAndNationIdAndOfficerLevelGreaterThanEqualOrderByOfficerLevelDescIdAsc(
        worldId: Int,
        nationId: Int,
        officerLevel: Int,
    ): List<GeneralReadEntity>

    /**
     * W3 FrontCityInfo `officerList` — PHP `SELECT officer_level, name, npc FROM general
     * WHERE officer_city = %i AND officer_level IN (4,3,2)`(GetFrontInfo.php:504). 도시 관직(태수4/군사3/종사2).
     * 술어는 `officer_city`(관직 보유 도시)이지 `city_id`(소재 도시)가 아니다. 표시 전용 — id asc 정렬.
     */
    fun findByWorldIdAndOfficerCityAndOfficerLevelInOrderByIdAsc(
        worldId: Int,
        officerCity: Int,
        officerLevels: List<Int>,
    ): List<GeneralReadEntity>

    /**
     * F3 kingdoms ranking — `power` proxy (병력 column) = SUM(general.crew) over a nation's generals.
     * The legacy `a_kingdomList.php` sorts by a stored `nation.power` that has no column in this schema;
     * SUM(crew) is the documented faithful proxy (OQ-3). COALESCE so a general-less nation → 0.
     */
    @Query("select coalesce(sum(g.crew), 0) from GeneralReadEntity g where g.worldId = :worldId and g.nationId = :nationId")
    fun sumCrewByWorldIdAndNationId(
        @Param("worldId") worldId: Int,
        @Param("nationId") nationId: Int,
    ): Long

    /**
     * W9 (9a) `getWorldMap`의 `shownByGeneralList` 원천 — 아국 장수가 소재한 도시 id 집합.
     * PHP `func_map.php:135-138`: `select distinct city from general where nation=%i`.
     * PHP는 무순서지만 deterministic 출력을 위해 cityId-ascending 정렬(표시 전용, 패러티 무관).
     */
    @Query("select distinct g.cityId from GeneralReadEntity g where g.worldId = :worldId and g.nationId = :nationId order by g.cityId asc")
    fun findDistinctCityIdByWorldIdAndNationId(
        @Param("worldId") worldId: Int,
        @Param("nationId") nationId: Int,
    ): List<Int>

    /** B1 Join — one general per user check. */
    fun findByWorldIdAndUserId(worldId: Int, userId: String): GeneralReadEntity?

    /** B1 Join — unique name check. */
    fun existsByWorldIdAndName(worldId: Int, name: String): Boolean
}

@Repository
class GeneralReadRepository(
    private val raw: GeneralReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findById(id: Int): Optional<GeneralReadEntity> = raw.findByWorldIdAndId(worldId.value, id)

    fun findAll(): List<GeneralReadEntity> = raw.findByWorldId(worldId.value)

    fun count(): Long = raw.countByWorldId(worldId.value)

    fun findByNationIdOrderByOfficerLevelDescIdAsc(nationId: Int): List<GeneralReadEntity> =
        raw.findByWorldIdAndNationIdOrderByOfficerLevelDescIdAsc(worldId.value, nationId)

    fun findByNationIdOrderByTurnTimeAsc(nationId: Int): List<GeneralReadEntity> =
        raw.findByWorldIdAndNationIdOrderByTurnTimeAsc(worldId.value, nationId)

    fun findByNpcStateOrderByIdAsc(npcState: Int): List<GeneralReadEntity> =
        raw.findByWorldIdAndNpcStateOrderByIdAsc(worldId.value, npcState)

    fun findFirstByNationIdOrderByOfficerLevelDesc(nationId: Int): GeneralReadEntity? =
        raw.findFirstByWorldIdAndNationIdOrderByOfficerLevelDesc(worldId.value, nationId)

    fun countByNpcState(npcState: Int): Long = raw.countByWorldIdAndNpcState(worldId.value, npcState)

    fun countByNationId(nationId: Int): Long = raw.countByWorldIdAndNationId(worldId.value, nationId)

    fun countByNpcStateLessThan(npcState: Int): Long = raw.countByWorldIdAndNpcStateLessThan(worldId.value, npcState)

    fun findByNpcStateLessThanOrderByIdAsc(npcState: Int): List<GeneralReadEntity> =
        raw.findByWorldIdAndNpcStateLessThanOrderByIdAsc(worldId.value, npcState)

    fun countByNpcStateGreaterThan(npcState: Int): Long = raw.countByWorldIdAndNpcStateGreaterThan(worldId.value, npcState)

    fun countByCityId(cityId: Int): Long = raw.countByWorldIdAndCityId(worldId.value, cityId)

    fun findByCityIdOrderByTurnTimeAsc(cityId: Int): List<GeneralReadEntity> =
        raw.findByWorldIdAndCityIdOrderByTurnTimeAsc(worldId.value, cityId)

    fun findByTroopIdOrderByOfficerLevelDescIdAsc(troopId: Int): List<GeneralReadEntity> =
        raw.findByWorldIdAndTroopIdOrderByOfficerLevelDescIdAsc(worldId.value, troopId)

    fun findByNationIdAndOfficerLevelGreaterThanEqualOrderByOfficerLevelDescIdAsc(
        nationId: Int,
        officerLevel: Int,
    ): List<GeneralReadEntity> = raw.findByWorldIdAndNationIdAndOfficerLevelGreaterThanEqualOrderByOfficerLevelDescIdAsc(
        worldId.value,
        nationId,
        officerLevel,
    )

    fun findByOfficerCityAndOfficerLevelInOrderByIdAsc(
        officerCity: Int,
        officerLevels: List<Int>,
    ): List<GeneralReadEntity> = raw.findByWorldIdAndOfficerCityAndOfficerLevelInOrderByIdAsc(
        worldId.value,
        officerCity,
        officerLevels,
    )

    fun sumCrewByNationId(nationId: Int): Long = raw.sumCrewByWorldIdAndNationId(worldId.value, nationId)

    fun findDistinctCityIdByNationId(nationId: Int): List<Int> =
        raw.findDistinctCityIdByWorldIdAndNationId(worldId.value, nationId)

    fun findByUserId(userId: String): GeneralReadEntity? = raw.findByWorldIdAndUserId(worldId.value, userId)

    fun existsByName(name: String): Boolean = raw.existsByWorldIdAndName(worldId.value, name)
}

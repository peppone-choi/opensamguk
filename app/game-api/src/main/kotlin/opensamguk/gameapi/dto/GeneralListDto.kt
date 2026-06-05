package opensamguk.gameapi.dto

/**
 * W3 — 세력 장수 목록(`GET /api/nation/general-list`) 응답. PHP `API/Nation/GeneralList.php:332-340`의
 * 충실 이식.
 *
 * PHP shape:
 * ```
 * {
 *   result: true,
 *   permission: <0..2>,                 // checkSecretPermission(우리는 derivePermission 0/1/2)
 *   column: <컬럼 키 목록>,              // 권한 tier로 필터된 viewColumns + customViewColumns 순서
 *   list: <행[][]>,                     // 각 행 = column 순서의 flat array
 *   troops: <부대[]>,                    // 부대 편성(1010 seed는 0행)
 *   env: { year, month, turnterm, ... }, // 전역 KV
 *   myGeneralID: <내 장수 id|null>
 * }
 * ```
 *
 * `column`/`list`의 flat-array 계약은 W9 [CityListResponse](`cityArgsList`/`cities`)와 동형이다 —
 * 컬럼 순서가 곧 행 튜플 순서이며, FE(AG-Grid GeneralList.vue)가 column 키로 각 셀을 해석한다.
 *
 * list 셀은 Int/String/null/구조체(reservedCommand)가 섞이므로 `List<List<Any?>>`로 직렬화한다.
 * (PHP도 동질 배열이 아닌 mixed tuple.)
 */
data class GeneralListResponse(
    val result: Boolean,
    /** 호출자의 권한 tier(0 일반 / 1 관직자 / 2 수뇌). P1 컬럼은 permission >= 1에서만 노출. */
    val permission: Int,
    /** 권한 tier로 필터·remap된 컬럼 키 순서. `list` 각 행의 셀 순서와 1:1. */
    val column: List<String>,
    /** 장수 행들. 각 행 = `column` 순서의 flat array(`turntime ASC` 정렬). */
    val list: List<List<Any?>>,
    /** 부대 편성 목록(부대장 id/이름/턴시각/예약명령). 1010 seed는 빈 목록. */
    val troops: List<GeneralListTroop>,
    /** 전역 환경(year/month/turnterm/killturn 등) — PHP env 봉투. */
    val env: GeneralListEnv,
    /** 호출자가 점유한 장수 id. 점유 없으면 null. */
    val myGeneralID: Int?,
)

/**
 * PHP `troops` 항목(GeneralList.php:190-195, 301-310). 부대장 장수 1명당 1개.
 *  - id            : 부대장 장수 id(troop_leader)
 *  - name          : 부대명
 *  - turntime      : 부대장 턴 시각(TURNTIME_FULL)
 *  - reservedCommand: 부대장 예약명령의 brief를 '집합'/'-'로 매핑한 목록(GeneralList.php:303-309)
 */
data class GeneralListTroop(
    val id: Int,
    val name: String,
    val turntime: String?,
    val reservedCommand: List<String>,
)

/**
 * PHP `env`(GeneralList.php:152) — `game_env`의 year/month/turntime/turnterm/autorun_user/killturn.
 *
 * opensamguk 원천:
 *  - year/month  : `world_state.current_year/current_month`
 *  - turnterm    : `world_state.tick_seconds / 60`(분 단위, FrontInfo 선례와 동일)
 *  - turntime    : 다음 턴 처리 시각(world_state.config['turntime'] 또는 미설정 시 null)
 *  - autorunUser : `world_state.config['autorun_user']`(없으면 null) — KV 미배선 시 null(§2 KV-wiring)
 *  - killturn    : `world_state.config['killturn']`(없으면 기본값) — KV 미배선 시 null
 *
 * autorunUser/turntime/killturn은 daemon KV 미배선 영역(§2 onlineGen/notice와 동질)이라 원천이 없으면
 * null로 두고 fabricate하지 않는다.
 */
data class GeneralListEnv(
    val year: Int,
    val month: Int,
    val turnterm: Int,
    val turntime: String?,
    val autorunUser: Int?,
    val killturn: Int?,
)

/**
 * 한 장수 행을 column-flat array로 펴기 전의 **티어드 중간 표현**. 컨트롤러가 P0/P1 필드를 모두 채우고,
 * `column` 순서에 맞춰 셀을 골라 `List<Any?>`로 평탄화한다(BLOCKED 필드 dex1-5는 column에서 제외되어
 * 평탄화 대상에 들지 않는다).
 *
 * 필드명 = PHP viewColumns/customViewColumns의 remap 후 키(specialDomestic/specialWar 등). 각 필드의
 * 원천·티어는 본 클래스 내 주석 참조.
 */
data class GeneralListRow(
    // ── P0 (reqPermission 0) — viewColumns ──────────────────────────────────────
    val no: Int,                       // general.id
    val name: String,                  // general.name
    val nation: Int,                   // general.nation_id
    val npc: Int,                      // general.npc_state
    val injury: Int,                   // general.injury
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val explevel: Int,                 // getExpLevel(experience) (DomesticHelpers)
    val dedlevel: Int,                 // getDedLevel(dedication)  (DomesticHelpers)
    val gold: Int,
    val rice: Int,
    val killturn: Int?,                // meta['killturn'](없으면 null — §2 KV 영역)
    val picture: String?,
    val imgsvr: Int,                   // general.image_server
    val age: Int,                      // general.age (V1 컬럼)
    val specialDomestic: String,       // remap special → specialDomestic (special_code)
    val specialWar: String,            // remap special2 → specialWar (special2_code)
    val personal: String,              // personal_code
    val belong: Int,                   // meta['belong']
    val troop: Int,                    // general.troop_id
    val city: Int,                     // general.city_id
    val specage: Int,                  // meta['specage']
    val specage2: Int,                 // meta['specage2']

    // ── P1 (reqPermission 1) — viewColumns ──────────────────────────────────────
    val leadershipExp: Double,         // meta['leadership_exp']
    val strengthExp: Double,           // meta['strength_exp']
    val intelExp: Double,              // meta['intel_exp']
    // dex1..dex5 — §2 BLOCKED(컬럼/meta 원천 없음). column 목록에서 제외하므로 여기 필드도 두지 않는다.
    val experience: Int,
    val dedication: Int,
    val officerLevel: Int,             // general.officer_level (raw, remap 없는 P1 컬럼)
    val officerCity: Int,              // general.officer_city (V6)
    // defence_train — §2 BLOCKED(컬럼/meta 미검증). column에서 제외.
    val crewtype: Int,                 // general.crew_type_id
    val crew: Int,
    val train: Int,
    val atmos: Int,
    val turntime: String?,             // TURNTIME_FULL(turn_time)
    val horse: String,                 // horse_code
    val weapon: String,                // weapon_code
    val book: String,                  // book_code
    val item: String,                  // item_code
    val recentWar: String?,            // TURNTIME_FULL(recent_war_time)

    // ── RANK(P1) — rank_data(F2) ────────────────────────────────────────────────
    val warnum: Int,
    val killnum: Int,
    val deathnum: Int,
    val killcrew: Int,
    val deathcrew: Int,
    val firenum: Int,

    // ── customViewColumns(computed) ─────────────────────────────────────────────
    val officerLevelText: String,      // getOfficerLevelText(effectiveOfficerLevel, nationLevel)
    val lbonus: Int,                   // calcLeadershipBonus(officer_level, nationLevel)
    val ownerName: String?,            // §2 BLOCKED(owner_name 원천 없음) — 항상 null
    val honorText: String,             // getHonor(experience)
    val dedLevelText: String,          // getDedLevelText(dedlevel)
    val bill: Int,                     // getBillByLevel(dedlevel)
    val reservedCommand: List<ReservedCommandItem>?, // P1: general_turn turn_idx<5 (없으면 null)
)

/**
 * PHP reservedCommand 항목(GeneralList.php:230-234) — 한 예약 슬롯. `action`/`arg`/`brief`.
 *  - action : general_turn.action_code
 *  - arg    : general_turn.arg(구조체 — jsonb 그대로)
 *  - brief  : general_turn.brief(요약 문자열)
 */
data class ReservedCommandItem(
    val action: String,
    val arg: Map<String, Any?>,
    val brief: String,
)

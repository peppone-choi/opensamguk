package opensamguk.gameapi.dto

/**
 * K1 진입(엔트런스) — `GET /api/server-basic-info`의 read 계약. devsam `j_server_basic_info.php`의
 * `{game, me}` 충실 포팅(reserved 가오픈 분기는 opensamguk에 해당 개념 부재 → 생략, 날조 없음).
 *
 * gateway 로비가 서버별로 fan-out 호출해 진입 상태머신(entrance.ts)을 그린다:
 *   me && me.name        → 입장
 *   userCnt >= maxUserCnt → 장수 등록 마감
 *   그 외               → 미등록 + 3버튼(canCreate/canSelectNPC/canSelectPool)
 *
 * 모든 값은 실 world_state / general / nation 쿼리 결과(하드코딩·날조 금지).
 */
data class ServerBasicInfoResponse(
    val game: ServerGameInfo?, // world_state 미시드 시에만 null
    val me: ServerMeInfo?, // 이 서버에 소유 장수가 없으면 null
)

/** devsam `$admin`(game) 블록 — 진입 화면 서버 정보 + 등록 게이팅 값. */
data class ServerGameInfo(
    val isUnited: Int, // config.isunited (0=경쟁중 / 1·2·3=이벤트·천하통일 상태)
    val npcMode: Int, // config.npcmode (0 불가 / 1 가능 / 2 선택 생성)
    val year: Int, // world_state.current_year
    val month: Int, // world_state.current_month
    val scenario: String, // config.title(scenario_text) || scenario_code
    val maxUserCnt: Int, // config.maxgeneral || GameConst.defaultMaxGeneral(패러티 기본 캡)
    val turnTerm: Int, // tick_seconds / 60 (분)
    val userCnt: Int, // general npc<2 (PC + 빙의됨)
    val npcCnt: Int, // general npc>=2 (순수 NPC)
    val nationCnt: Int, // nation level>0
    val fictionMode: String, // '가상' | '사실' (config.fiction)
    val joinMode: String?, // config.join_mode (예: onlyRandom)
    val blockGeneralCreate: Int, // config.block_general_create (bit&1 = 장수생성 금지)
    val defaultStatTotal: Int, // GameConst.defaultStatTotal (장수 생성 폼 캡)
    val otherTextInfo: String, // '표준' 또는 '랜덤 임관 전용' 등
    /** 서버 운영 상태 — CLOSED / PRE_OPEN / OPEN */
    val status: String,
)

/** devsam `$me` 블록 — owner=userID 장수의 이름/초상(없으면 부모가 me=null). */
data class ServerMeInfo(
    val name: String,
    val picture: String?,
    val imageServer: Int,
)

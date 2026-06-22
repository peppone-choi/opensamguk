package opensamguk.gameapi.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// F4 — READ-only action-page DTOs. Verbatim field names the web/game pages consume (spec
// 2026-06-03-F4-action-pages-spec.md). All shapes are graceful-empty where the seed has no rows.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

// ── GET /api/generals — PUBLIC, permission=0 fields only (page 14 / 9-P0) ──────────────────────────
/**
 * Public all-general row. permission=0 surface ONLY: NO refresh_score, NO raw exp/ded breakdown,
 * NO gold/rice (OQ-5 — 미인증 공개 surface라 자금/경험치 원값을 노출하지 않는다).
 * `nationName`은 국가 NAME(재야 for nationId 0); `nationColor`는 hex(#000000 for 재야).
 *
 * 전체 장수(GeneralList.vue)는 명성/계급을 **레벨 버킷**으로 표시·정렬한다(raw exp/ded가 아님). 따라서
 * 명성 = `explevel`(Lv) + `honorText`(칭호), 계급 = `dedlevel` + `dedLevelText`(품관) + `bill`(봉록)을
 * 내려보낸다 — 모두 실 `general` 컬럼에서 파생되는 공개 버킷이며(getExpLevel/getDedLevel/getDedLevelText/
 * getBillByLevel/getHonor, GeneralListController와 동일 공식) raw 원값을 누설하지 않으므로 OQ-5 위반 아님.
 * `nationId/npc/officerLevelText`도 레거시가 공개 목록에서 쓰는 표시 필드라 보강한다.
 */
data class PublicGeneral(
    /** 장수 id(legacy `no`). FE `GeneralListItem.generalId`. */
    val generalId: Int,
    val name: String,
    /** 국가 id(0 = 재야). FE 세력 필터/그룹 키. */
    val nationId: Int,
    /** 국가명(재야면 F4StateText.NEUTRAL_NATION_NAME). PHP a_genList의 `$nationname[nation]`(국가 미존재→"-"는 재야로 표시). */
    val nationName: String,
    val nationColor: String,
    /** NPC 상태(0 user / 1 possessed-NPC / 2+ pure NPC). PHP formatName/getNPCColor 입력(이름 색). */
    val npc: Int,
    val officerLevel: Int,
    /** getOfficerLevelText(officerLevel, nationLevel) — 직책(관직) 한글명. a_genList "관 직" 컬럼. */
    val officerLevelText: String,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val politics: Int, // 정치/매력 (RTK14 divergence)
    val charm: Int, // 정치/매력 (RTK14 divergence)
    /** 명성 레벨 버킷 = getExpLevel(experience). a_genList "레 벨" 컬럼("Lv {explevel}")·정렬 키. */
    val explevel: Int,
    /** 명성 칭호 = getHonor(experience). a_genList "명 성" 컬럼. */
    val honorText: String,
    /** 계급 레벨 버킷 = getDedLevel(dedication). 계급 정렬 키. */
    val dedlevel: Int,
    /** 계급 한글명 = getDedLevelText(dedlevel). a_genList "계 급" 컬럼. */
    val dedLevelText: String,
    /** 봉록 = getBillByLevel(dedlevel). 계급 컬럼 부가 표시("({bill})"). */
    val bill: Int,
    val cityName: String,

    // ── a_genList(장수일람, fid 30) 15컬럼 보강(C3①) ────────────────────────────────────────────────
    // PHP `hwe/a_genList.php`가 행마다 노출하는 표시 필드. raw 코드는 코드대로 두고 한글 해석값을 ADD
    // (이미 이식된 헬퍼 SpecialityHelper/personalityNameOf/getOfficerLevelText/getHonor 재사용 — 날조 아님).
    /** 장수 초상화 파일명(PHP `picture`). a_genList "얼 굴" 컬럼(이미지 src). */
    val picture: String?,
    /** 초상 이미지 서버 번호(PHP `imgsvr`→GetImageURL). FE가 CDN base 합성. */
    val imageServer: Int,
    /** 나이(PHP `age` → "{age}세"). a_genList "연령" 컬럼. */
    val age: Int,
    /** 성격 한글명 = personalityNameOf(personal_code) (PHP displayCharInfo의 getName()). a_genList "성격" 컬럼. */
    val personalText: String,
    /** 내정 특기명 = SpecialityHelper.domesticName(special_code) (PHP displaySpecialDomesticInfo). a_genList "특기"(앞). None→"-". */
    val specialDomesticText: String,
    /** 전투 특기명 = SpecialityHelper.warName(special2_code) (PHP displaySpecialWarInfo). a_genList "특기"(뒤). None→"-". */
    val specialWarText: String,
    /** 부상률(0~100, PHP `injury`). >0이면 통/무/지에 부상보너스(감산)·적색 표시(FE). a_genList 통무지 컬럼. */
    val injury: Int,
    /** 통솔 통솔보너스 = calcLeadershipBonus(officer_level, nationLevel) (PHP). >0이면 통솔에 "+{lbonus}"(cyan). */
    val lbonus: Int,
    /**
     * 삭턴(killturn) = 남은 활동 턴. PHP `general.killturn` 컬럼. opensamguk은 general 스칼라 컬럼이 아니라
     * meta(`killturn`)에서 읽는다(GeneralListController와 동일 원천). meta 미기재 시 null(부재=미기록, 날조 금지).
     */
    val killturn: Int?,
    // [§2 BLOCKED — general_access_log 부재] 벌점(refresh_score_total)은 PHP가
    // `LEFT JOIN general_access_log`에서 읽는다(a_genList.php:114). 해당 테이블이 opensamguk 스키마
    // 어디에도 없어(P8 미이식) read 원천이 없다 → 컬럼 자체를 노출하지 않는다(값 날조 금지).
    // 또한 a_genList의 기본 정렬(type=9, refresh_score_total DESC)도 이 원천 부재로 재현 불가 —
    // FE는 가용한 정렬 키만 제공하고 벌점 정렬/컬럼은 데이터가 생길 때(P8) 추가한다.
)

// ── GET /api/tournament — state/bracket/standings/rankings/msg (pages 12, 13, 11-bracket) ──────────
data class TournamentBracketMatch(
    val groupNo: Int,
    val seq: Int,
    val leftName: String?,
    val rightName: String?,
    val winnerName: String?,
)

data class TournamentRankingRow(
    val rank: Int,
    val generalName: String,
    val nationName: String,
    val value: Int,
)

/** One of the 4 ranking-type tables (전력전/통솔전/일기토/설전). */
data class TournamentRankingBoard(
    val type: String,
    val rows: List<TournamentRankingRow>,
)

data class TournamentResponse(
    /** Legacy tournament state machine 0-8 (0 = no tournament / closed). */
    val state: Int,
    val tnmtType: Int,
    val tnmtTypeText: String,
    val tnmtMsg: String,
    val turnTerm: Int,
    /** 8 preliminary group standings; each group is an ordered list of ranking rows. */
    val groups: List<List<TournamentRankingRow>>,
    /** 16강 bracket matches. */
    val bracket: List<TournamentBracketMatch>,
    /** The 4 ranking-type boards (always present, possibly empty). */
    val rankings: List<TournamentRankingBoard>,
)

// ── GET /api/diplomacy/letters — nations map + letters + myNationID (page 1) ───────────────────────
/**
 * `nations` 맵 1 항목 — legacy `NationStaticItem`(`ts/defs/index.ts:87`) 부분집합. PHP
 * `j_diplomacy_get_letter.php`의 nations 맵은 `getNationStaticInfo()` 행을 그대로 내려보내므로
 * `level`까지 포함한다(FE 수신국 select가 `Lv.{level}` 표시·정렬에 사용 — `ts/diplomacy.ts:345 nationList`).
 * id/name/color/level은 모두 실 `nation` 컬럼이므로 날조 아님.
 */
data class DiplomacyNationInfo(
    val id: Int,
    val name: String,
    val color: String,
    /** legacy `NationStaticItem.level` — 국가 레벨(수신국 select 표시용). 실 `nation.level` 컬럼. */
    val level: Int,
)

/**
 * 외교 서신 1 당사자(송/수신). PHP `j_diplomacy_get_letter.php`는 `aux['src']`/`aux['dest']`(서신 INSERT
 * 시점 스냅샷, `j_diplomacy_send_letter.php:153-164`)를 그대로 내려보내고 `nationID`만 컬럼값으로 덮어쓴다.
 * FE(`ts/diplomacy.ts` `LetterFullTarget`/`LetterNationTarget`)가 소비하는 키 셋과 1:1로 맞춘다:
 *  - 송신측(`src`)은 항상 풀 타깃 = `{nationID, nationName, nationColor, generalName, generalIcon}`.
 *  - 수신측(`dest`)은 제안 시점엔 서명자가 없어 nation만 = `{nationID, nationName, nationColor}`이며
 *    승인/서명 후 `generalName`/`generalIcon`이 채워질 수 있다(legacy에선 INSERT 후 update가 없어
 *    제안 단계 dest는 nation-only). 미기재 키는 null(부재=미기록, 날조 금지).
 *
 * 와이어 키는 legacy snake/camel 혼용을 그대로 따른다(`nationID`/`nationName`/`nationColor` —
 * MessageTarget·aux 직렬화 키). Jackson 기본은 프로퍼티명 그대로 내보내므로 `@JsonProperty`로 고정한다.
 */
data class DiplomacyLetterParty(
    @get:JsonProperty("nationID")
    val nationId: Int,
    @get:JsonProperty("nationName")
    val nationName: String,
    @get:JsonProperty("nationColor")
    val nationColor: String,
    /** 서명 장수명(`aux[...]['generalName']`). nation-only 당사자(미서명 수신측)면 null. */
    val generalName: String? = null,
    /** 서명 장수 초상 URL(`aux[...]['generalIcon']` = GetImageURL(imgsvr, picture)). 미서명이면 null. */
    val generalIcon: String? = null,
)

data class DiplomacyLetter(
    val no: Int,
    val src: DiplomacyLetterParty,
    val dest: DiplomacyLetterParty,
    /** legacy `prev_no` — 이전(대체된) 문서 번호. 신규 문서면 null. */
    @get:JsonProperty("prev_no")
    val prevNo: Int?,
    /** legacy `state`(소문자) proposed/activated/cancelled/replaced — FE가 stateText 매핑·버튼 게이트 키로 소비. */
    val state: String,
    /**
     * 상태 한글 라벨(opensamguk 편의 read 필드). Verbatim 제안됨/승인됨/거부됨/대체됨
     * ([F4StateText.letterStateText]). legacy FE는 클라이언트에서 매핑하지만 read DTO에서도 동봉한다(부가 표시).
     */
    val stateText: String,
    /**
     * legacy `state_opt`(`aux['state_opt']`) — 파기 2단계 진행상태. `try_destroy_src`/`try_destroy_dest`/null.
     * FE 파기 버튼 노출/disable 및 '송신측/수신측의 파기 요청' 라벨 결정자.
     */
    @get:JsonProperty("state_opt")
    val stateOpt: String?,
    /** legacy `text_brief` — 요약문. */
    val brief: String,
    /** legacy `text_detail` — 본문. permission<3 호출자에겐 '(권한이 부족합니다)'로 마스킹된다. */
    val detail: String,
    val date: Instant,
    val srcSigner: Int,
    val destSigner: Int?,
)

data class DiplomacyLettersResponse(
    val result: Boolean,
    val myNationID: Int,
    val nations: Map<String, DiplomacyNationInfo>,
    val letters: List<DiplomacyLetter>,
)

// ── GET /api/diplomacy/conflict — GetDiplomacy.php envelope (page 2 / 중원정보) ─────────────────────
/**
 * PHP `getNationStaticInfo()` 한 행(SimpleNationObj) — `func.php:38-82`의
 * `select nation, name, color, type, level, capital, gennum, power from nation` + `cities` 보강.
 * 필드명/순서 모두 PHP 그대로.
 */
data class SimpleNationObj(
    /** 국가 id (PHP `nation` 컬럼). */
    val nation: Int,
    val name: String,
    val color: String,
    /** 국가 성향 type_code (PHP `type` 컬럼). */
    val type: String,
    val level: Int,
    /** 수도 도시 id (PHP `capital`). 없으면 0. */
    val capital: Int,
    /** 장수 수 (PHP `gennum`, meta jsonb). */
    val gennum: Int,
    /** 보유 도시명 목록 — city 행 nationId 그룹, 삽입(행) 순서. */
    val cities: List<String>,
    val power: Int,
)

/**
 * D11 GetDiplomacy 봉투 — PHP `GetDiplomacy.php:98-104` 그대로:
 * `{result, nations[], conflict[[cityId,{nationId:pct}]], diplomacyList{me:{you:state}}, myNationID}`.
 */
data class DiplomacyConflictResponse(
    val result: Boolean,
    /** level>0 국가, power DESC 정렬(PHP array_filter + uasort -power). */
    val nations: List<SimpleNationObj>,
    /**
     * 분쟁 튜플 목록: `[[cityId, {nationId: pct}]]`. pct = round(100*killnum/sum, 1) — PhpRound
     * half-away 소수1자리 Double. 빈 '{}' 또는 항목<2 도시는 제외(PHP GetDiplomacy.php:59-72).
     */
    val conflict: List<List<Any>>,
    /**
     * 외교 관계 맵: me → (you → state). viewer-conditional 마스킹 —
     * me/you 둘 다 내 국가가 아니면 3..7→2, 한쪽이라도 내 국가면 원 state(PHP:91-95).
     */
    val diplomacyList: Map<String, Map<String, Int>>,
    /** 내 국가 id(미인증/재야면 0). PHP `SELECT nation FROM general WHERE owner=userID`. */
    val myNationID: Int,
)

// ── GET /api/nation/{id}/finance — page 3 (W0-2 P0-51 legacy 중첩 구조 재구축) ─────────────────────
/**
 * 내무부 응답 — PHP `v_nationStratFinan.php:126-154` staticValues 중첩 shape 충실:
 * `{editable, nationMsg, scoutMsg, nationID, officerLevel, year, month, nationsList, gold, rice,
 *   income{gold{city,war},rice{city,wall}}, outcome, policy{rate,bill,secretLimit,blockScout,blockWar},
 *   warSettingCnt{remain,inc,max}}`.
 *
 * 기존 평면 shape(income:Int 등)는 FE 타입(중첩)과 불일치해 국가 소속자 전원 런타임 크래시였다(P0-51).
 *
 * [§2 BLOCKED — 날조 금지로 nullable, W1-O 배선]
 *  - income/outcome(P0-52): PHP는 getGoldIncome/getWarGoldIncome/getRiceIncome/getWallIncome/getOutcome
 *    (v_nationStratFinan.php:77-115, rate=100 기준)로 live 산출. logic 이식은 완료(ProcessIncome.kt)지만
 *    read game-api에 nation-type income 파이프라인이 조립돼 있지 않아(IdentityDto MyCitySummary §2 동일)
 *    W1-O가 배선할 때까지 null — 위조 0(아무도 안 쓰는 meta 키 read)을 제거한다.
 *  - nationMsg/scoutMsg/warSettingCnt.remain(P0-53): 실제 write는 nation_env KV
 *    (NationFinanceSetters: nationNotice/scout_msg/available_war_setting_cnt)인데 종전 read는 meta 잘못된
 *    스토어였다(라운드트립 불능). nation_env read repo(W1-O 신설)까지 null(부재=미배선, 날조 금지).
 *  - nationsList(P0-54): 전국가 7컬럼 외교 표(v_nationStratFinan.php:45-72 diplomacy{state,term}+cityCnt).
 *    타입([NationFinanceNationItem])만 정본화, 조립은 W1-O.
 */
data class NationFinanceResponse(
    val result: Boolean,
    val nationId: Int,
    val name: String,
    val color: String,
    val level: Int,
    /** 호출자의 officer_level(PHP `$me['officer_level']`, v_nationStratFinan.php:132). 미인증/무장수 → 0. */
    val officerLevel: Int = 0,
    /** 현재 게임 연도(PHP game_env year, :133). */
    val year: Int = 0,
    /** 현재 게임 월(PHP game_env month, :134). */
    val month: Int = 0,
    val gold: Int,
    val rice: Int,
    /** 수입 4분해(PHP :104-113 income{gold{city,war},rice{city,wall}}). rate=100 LIVE 산정(IncomeTick 재사용). */
    val income: NationFinanceIncome? = null,
    /** 지출(PHP :115 getOutcome(100, dedicationList) — npc!=5 dedication 합). */
    val outcome: Int? = null,
    /** 국가 방침 묶음(PHP :142-148 policy). */
    val policy: NationFinancePolicy = NationFinancePolicy(),
    /** 선포 설정 잔여/증가/최대(PHP :149-153 warSettingCnt). */
    val warSettingCnt: NationFinanceWarSettingCnt,
    /** 국가 방침문(PHP :129 nationNotice['msg'] — nation_env KV). BLOCKED(P0-53) → null. */
    val nationMsg: String? = null,
    /** 임관 권유문(PHP :130 scout_msg — nation_env KV). BLOCKED(P0-53) → null. */
    val scoutMsg: String? = null,
    /** 전국가 외교 표(PHP :135 nationsList = getAllNationStaticInfo + cityCnt + diplomacy{state,term}). */
    val nationsList: List<NationFinanceNationItem>? = null,
    /** True only when the caller may edit (officer_level >= 5). game-api computes from the principal. */
    val editable: Boolean,
)

/** PHP `income` 블록(v_nationStratFinan.php:104-113) — 금{도시,전쟁}/쌀{도시,성벽}. */
data class NationFinanceIncome(
    val gold: NationFinanceGoldIncome,
    val rice: NationFinanceRiceIncome,
)

/** 금 수입 분해 — city=getGoldIncome, war=getWarGoldIncome. */
data class NationFinanceGoldIncome(
    val city: Int,
    val war: Int,
)

/** 쌀 수입 분해 — city=getRiceIncome, wall=getWallIncome. */
data class NationFinanceRiceIncome(
    val city: Int,
    val wall: Int,
)

/**
 * PHP `policy` 블록(v_nationStratFinan.php:142-148). 원천은 nation.meta(rate/bill/secretlimit/war/scout —
 * NationFinanceSetters 실제 write 키). meta 미기재(시드 미기록) 시 null — 0/false 날조 금지(P1-077 동일 규약).
 * blockWar/blockScout = `meta[war]/[scout] != 0`(PHP `$nation['war'] != 0` 동식, P0-53 read 키 정합).
 */
data class NationFinancePolicy(
    val rate: Int? = null,
    val bill: Int? = null,
    val secretLimit: Int? = null,
    val blockScout: Boolean? = null,
    val blockWar: Boolean? = null,
)

/**
 * PHP `warSettingCnt` 블록(v_nationStratFinan.php:149-153). inc/max는 GameConst 실상수
 * (incAvailableWarSettingCnt/maxAvailableWarSettingCnt — GameConstBase 동일 값). remain은 nation_env KV
 * `available_war_setting_cnt` — read repo 부재로 null(P0-53 BLOCKED, W1-O 배선).
 */
data class NationFinanceWarSettingCnt(
    val remain: Int? = null,
    val inc: Int,
    val max: Int,
)

/**
 * PHP `nationsList` 한 항목(v_nationStratFinan.php:48-72) — getAllNationStaticInfo 행
 * (nation/name/color/type/level/capital/gennum/power) + `cityCnt`(속령수) + `diplomacy{state,term}`
 * (자국은 state 7/term null). W0-2에서 타입만 정본화 — 조립은 W1-O(P0-54).
 */
data class NationFinanceNationItem(
    /** 국가 id(PHP `nation` 컬럼명 그대로). */
    val nation: Int,
    val name: String,
    val color: String,
    val type: String,
    val level: Int,
    val capital: Int,
    val gennum: Int,
    val power: Int,
    /** 속령수(PHP cityCnt — city GROUP BY nation). */
    val cityCnt: Int,
    val diplomacy: NationFinanceDiplomacyState,
)

/** PHP `diplomacy` 블록 — state(0~7, 자국 7)/term(잔여 개월, 자국 null). */
data class NationFinanceDiplomacyState(
    val state: Int,
    val term: Int? = null,
)

// ── GET /api/nation/chief-reserved — 8 chief posts + reserved turns (page 7) ───────────────────────
/**
 * 사령부(chief-center) 예약 국가 명령 1슬롯. PHP `GetReservedCommand.php:96-100`의
 * `['action'=>..., 'brief'=>..., 'arg'=>Json::decode(arg)]`에 대응.
 *
 * W3-ChiefCenter: 기존 `actionCode/brief`에 더해 `arg`(예약 명령의 구조화된 타겟, `nation_turn.arg` jsonb)를
 * 추가한다 — PHP가 슬롯마다 `Json::decode($arg)`로 내려보내는 누락된 read 필드. 빈 인자는 `{}`(삽입순서 맵).
 */
data class ChiefReservedTurn(
    val turnIdx: Int,
    val actionCode: String,
    val brief: String,
    /** 예약 국가 명령의 구조화 인자(`nation_turn.arg`). 인자 없는 명령은 빈 맵 `{}`. */
    val arg: Map<String, Any?> = emptyMap(),
)

/**
 * 한 직책(officer_level)의 사령부 칸. PHP `nationChiefList[officer_level]`에 대응.
 * 직책이 공석(해당 레벨의 장수 없음)이면 `name/turnTime/npcType`은 null이고 예약 턴 목록만 남는다.
 */
data class ChiefPost(
    val officerLevel: Int,
    /** F4StateText.CHIEF_POSTS의 정본 직책명(군주/참모/…). 칸 고정 라벨. */
    val title: String,
    // W3-ChiefCenter — 직책 보유 장수 정보(PHP `getName()/getTurnTime(TURNTIME_FULL)/getNPCType()`).
    /** 직책 보유 장수 이름. 공석이면 null. 원천: general.name. */
    val name: String? = null,
    /** 직책 보유 장수의 TURNTIME_FULL("YYYY-MM-DD HH:MM:SS"). 공석이면 null. 원천: general.turn_time. */
    val turnTime: String? = null,
    /** 직책 보유 장수의 NPC 상태(0=PC, 1=NPC, 2+=잠금/후보). 공석이면 null. 원천: general.npc_state. */
    val npcType: Int? = null,
    /** PHP `getOfficerLevelText(officer_level, nationLevel)` — 국가 레벨별 직책 한글명. */
    val officerLevelText: String,
    val reservedTurns: List<ChiefReservedTurn>,
)

/**
 * 사령부 명령 팔레트의 1개 명령. PHP `getChiefCommandTable`의 `values[]` 한 항목
 * (`{value, compensation, possible, title, simpleName, reqArg, reason}`)에 대응.
 *
 * `possible`/`reason`은 [AvailableCommandsController]와 동일하게 실제 precheck 결과로 채운다
 * (데몬과 동일 제약 라이브러리, precheck == full). `compensation`만 PHP `getCompensationStyle()`(▲/▼)
 * 미포팅으로 0(중립) 고정 flag로 남는다 — BLOCKED 아닌 read-DTO 단계의 알려진 flag.
 */
data class ChiefCommand(
    /** 예약 액션 코드(`Util::getClassNameFromObj`). 원천: CommandRegistry 정의 key. */
    val value: String,
    val simpleName: String,
    val title: String,
    /** 보정 스타일(▲/▼). PHP `getCompensationStyle()` 미포팅 → 0(중립) 고정 flag. */
    val compensation: Int,
    /** 명령 가능 여부. 실제 precheck 결과(deny면 false). */
    val possible: Boolean,
    /** 인자 필요 명령 여부(`argsSchema` 비어있지 않음). */
    val reqArg: Boolean,
    /**
     * 모달 인자 폼 타입(city/nation/general/amount). `argsSchema` 키에서 파생하며
     * [opensamguk.gameapi.web.AvailableCommandsController.argTypeOf]와 동일 규칙(날조 아님).
     * 인식 가능한 인자 키가 없으면 null(인자 없는 명령 또는 페이지-고정 인자 명령).
     */
    val argType: String? = null,
    /**
     * deny 사유(possible=false일 때). PHP-충실 reason 문자열.
     * AvailableCommandsController.AvailableCommand.reason 미러.
     */
    val reason: String? = null,
)

/** 사령부 명령 팔레트의 1개 카테고리(휴식/인사/외교/특수/전략/기타). */
data class ChiefCommandCategory(
    val category: String,
    val values: List<ChiefCommand>,
)

data class ChiefReservedResponse(
    val result: Boolean,
    // W3-ChiefCenter — 호출자 식별(JWT principal에서 resolve).
    /** 호출자(나)의 장수 id. */
    val myGeneralId: Int,
    /** 호출자(나)의 officer_level. */
    val myOfficerLevel: Int,
    val nationId: Int,
    // W3-ChiefCenter — 국가 컨텍스트.
    /** 국가명(재야면 F4StateText.NEUTRAL_NATION_NAME). */
    val nationName: String?,
    /** 국가 레벨(재야면 0). */
    val nationLevel: Int,
    // W3-ChiefCenter — 게임 시각(world_state 클럭).
    /** 현재 게임 연도. */
    val year: Int,
    /** 현재 게임 월. */
    val month: Int,
    /** 턴 텀(분). PHP `turnTerm`(legacy 이름 유지). */
    val turnTerm: Int,
    val maxChiefTurn: Int,
    val posts: List<ChiefPost>,
    // W3-ChiefCenter — 부대 목록(troop_leader → name) + 명령 팔레트 + 수뇌 여부.
    /** 국가 부대 목록: troopLeader → 부대명(PHP `troopList`). 시드 무행이면 빈 맵. */
    val troopList: Map<String, String>,
    /** 사령부 명령 팔레트(PHP `getChiefCommandTable` / `commandList`). */
    val commandList: List<ChiefCommandCategory>,
    /**
     * 수뇌 여부(officer_level > 4). PHP `isChief`.
     * Jackson 기본 BeanIntrospector가 `isXxx` boolean 게터를 `is` 제거해 `chief`로 직렬화하므로
     * 와이어 키를 `isChief`로 고정한다(FE `$.isChief` 소비).
     */
    @get:JsonProperty("isChief")
    val isChief: Boolean,
    /**
     * BLOCKED (W3_PLAN §2: `defence_train / autorun_limit | no column`). PHP `autorun_limit`는
     * `general.aux`(general_access_log/aux) 원천이 opensamguk 스키마에 없어 read 경로가 부재 →
     * null로 둔다(날조 금지). 컬럼/메타 write 경로가 생기면 채운다.
     */
    val autorunLimit: Int? = null,
)

// ── GET /api/nation/npc-policy — page 8 ────────────────────────────────────────────────────────────
data class NpcPolicyResponse(
    val result: Boolean,
    val nationId: Int,
    /** Default policy field map (initial values; 초깃값으로 button). */
    val defaultPolicy: Map<String, Any?>,
    /** Current policy field map (live values). */
    val currentPolicy: Map<String, Any?>,
    /** Ordered chief-command priority list. */
    val chiefPriority: List<String>,
    /** Ordered general-command priority list. */
    val generalPriority: List<String>,
    /** Who last set each policy section (auditing). */
    val lastSetters: Map<String, Any?>,
)

// ── GET /api/inherit-point — page 15 ───────────────────────────────────────────────────────────────
data class InheritActionCost(
    val buff: List<Int>,
    val resetTurnTime: Int,
    val resetSpecialWar: Int,
    val randomUnique: Int,
    val nextSpecial: Int,
    val minSpecificUnique: Int,
    val checkOwner: Int,
    val bornStatPoint: Int,
)

data class InheritStat(
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val statMin: Int,
    val statMax: Int,
)

data class InheritLog(
    val id: Int,
    val year: Int,
    val month: Int,
    val text: String,
    /**
     * W0-2(P1-043) — 기록 시각 'yyyy-MM-dd HH:mm:ss'(PHP user_record.date — v_inheritPoint.php:74).
     * opensamguk 동등 컬럼 inheritance_log.created_at. 부재(레거시 행) 시 null(날조 금지).
     */
    val date: String? = null,
)

data class InheritPointResponse(
    val result: Boolean,
    /** Inheritance point amounts per InheritanceKey value. */
    val items: Map<String, Int>,
    /** Currently-owned inherit buffs (buffKey → level). */
    val currentInheritBuff: Map<String, Int>,
    val maxInheritBuff: Int,
    val resetTurnTimeLevel: Int,
    val resetSpecialWarLevel: Int,
    val inheritActionCost: InheritActionCost,
    /** Available special-war picks: key → {title, info}. */
    val availableSpecialWar: Map<String, Any?>,
    /** Available unique items: key → {title, rawName, info}. */
    val availableUnique: Map<String, Any?>,
    val lastInheritPointLogs: List<InheritLog>,
    /**
     * W0-2(P0-25) — 소유자 확인(CheckOwner) 대상 장수 {no: name} 맵
     * (PHP v_inheritPoint.php:19-22 `SELECT no, name FROM general WHERE npc < 2`, :107 staticValue).
     * 삽입순서(id ASC) 보존 — JSON 키는 PHP와 동일하게 장수 no.
     */
    val availableTargetGeneral: Map<Int, String> = emptyMap(),
    val currentStat: InheritStat,
)

// ── GET /api/board?secret= — page 4 ────────────────────────────────────────────────────────────────
data class BoardComment(
    val id: Int,
    val authorGeneralId: Int,
    val authorName: String,
    val text: String,
    val date: Instant,
)

data class BoardArticle(
    val id: Int,
    val nationId: Int,
    val authorGeneralId: Int,
    val authorName: String,
    val title: String,
    val contentHtml: String,
    val date: Instant,
    val comments: List<BoardComment>,
    // [C1-α BLOCKED — author_icon 원천 부재] legacy `j_board_article_add.php`는 작성 시점 작성자
    // 초상(imgsvr/picture → GetImageURL)을 INSERT하고 BoardArticle.vue가 64px로 렌더하지만,
    // opensamguk `board_post` 스키마(V1__baseline.sql:352)에 author_icon/imgsvr/picture 컬럼이 없어
    // (P8 미이식) read 원천이 없다 → 필드 자체를 노출하지 않는다(값 날조 금지, parityViolation MEDIUM 백로그).
    // 복원 시 = 마이그레이션(author_icon 컬럼) + intake INSERT + DTO + FE 일괄. 댓글(BoardComment)은
    // legacy에도 초상이 없으므로 author_icon 부재가 정상이다.
)

data class BoardResponse(
    val result: Boolean,
    /** True → 기밀실 view, false → 회의실 view. */
    val secret: Boolean,
    /** Board title text: verbatim 회의실 / 기밀실. */
    val title: String,
    val articles: List<BoardArticle>,
    /** Set when a permission gate blocked the secret board (renders as INFO, not error). */
    val blockedReason: String?,
)

// ── GET /api/troops — page 6 ───────────────────────────────────────────────────────────────────────
// 레거시 grand truth: hwe/ts/PageTroop.vue. 멤버십/뮤테이션 게이팅은 `myGeneralId`(부대장 여부)와
// `permission`(레거시 myPermission)에서 파생되므로 응답에 둘 다 싣는다(Direction A). 멤버 소속 도시는
// 숫자 id가 아니라 한글 `cityName`으로 노출한다(레거시는 cityConst[city].name 표시, bug #11).
data class TroopMember(
    val generalId: Int,
    val name: String,
    val officerLevel: Int,
    val crew: Int,
    val cityName: String,
    /** 레거시 getNPCColor 색상 티어 입력(npc_state). 0=유저/1=빙의/2+=순수 NPC. */
    val npc: Int,
)

data class TroopRow(
    val troopLeader: Int,
    val name: String,
    val nation: Int,
    val leaderName: String,
    /** 부대장 소재 도시 한글명(레거시 카드 헤더 '【 <city> 】'). */
    val leaderCityName: String,
    /** 부대장 npc 티어(레거시 부대장 이름 색상). */
    val leaderNpc: Int,
    /** 부대장 턴 시각 "YYYY-MM-DD HH:MM:SS"(레거시 '【턴】' = turnTime.slice(14,19))., null이면 빈 문자열. */
    val turnTime: String,
    /**
     * 레거시 troop.reservedCommandBrief(예약 명령 브리핑 목록). troop 읽기 모델에 예약명령 원천이 없고
     * fresh seed에는 부대가 0개이므로 빈 목록으로 둔다(날조 금지 — 규율 5). 추후 예약명령 배선 시 채운다.
     */
    val reservedCommandBrief: List<String>,
    val members: List<TroopMember>,
    /** (N명) member count for the 부대 list header. */
    val memberCount: Int,
)

data class TroopsResponse(
    val result: Boolean,
    val troops: List<TroopRow>,
    /** 호출자가 빙의한 장수 id(레거시 myGeneralID). 멤버십/뮤테이션 게이팅 기준. 미인증/무빙의=0. */
    val myGeneralId: Int,
    /** 레거시 myPermission(officer_level 파생: 0 일반/1 관직자/2 수뇌). 부대명 변경(>=4 의도)·게이팅용. */
    val permission: Int,
)

// ── GET /api/history?yearMonth — page 16 (연감) ─────────────────────────────────────────────────────
// 와이어 정합: 레거시 `Global/GetHistory.php` + `PageHistory.vue`가 기대하는 셀렉터/레코드 셰이프.
// PageHistory.vue는 staticValues{firstYearMonth,lastYearMonth,currentYearMonth,serverNick,mapName}와
// history{map,nations,global_history,global_action}를 소비한다. opensamguk read는 단일 서버이므로
// serverId/mapName은 정보용으로만 채운다(셀렉터는 [first,last] 범위만 사용).
//
// yearMonth = Util::joinYearMonth = year*12 + (month-1). parseYearMonth = [ym/12, ym%12+1].

/**
 * 선택된 월의 연감 레코드(PageHistory.vue `history`). map/nations는 yearbook_history jsonb 스냅샷(MapViewer +
 * SimpleNationList 원천). globalHistory/globalAction(중원 정세/장수 동향)은 opensamguk yearbook_history에
 * **컬럼이 없다**(V1__baseline.sql:227 — map/nations만 영속) → 빈 배열(§5 날조 금지, BLOCKED). 월별 글로벌
 * 로그 컬럼이 배선되면 1줄로 채워진다. nations/map은 jsonb 원형(삽입순 보존) 그대로 전달.
 */
data class HistoryRecord(
    val serverId: String,
    val year: Int,
    val month: Int,
    val globalHistory: List<String>, // 중원 정세 — yearbook_history 미보유 → [](BLOCKED)
    val globalAction: List<String>,  // 장수 동향 — yearbook_history 미보유 → [](BLOCKED)
    val nations: Any?,               // SimpleNationList 원천(jsonb 원형: 배열 또는 맵)
    val map: Any?,                   // MapViewer 스냅샷(jsonb 원형)
    val hash: String,
)

data class HistoryResponse(
    val result: Boolean,
    val firstYearMonth: Int,        // Util::joinYearMonth(first row)
    val lastYearMonth: Int,         // Util::joinYearMonth(last row)
    val currentYearMonth: Int,      // 진행중 서버: world_state 현재 월. world_state도 없으면 0.
    val serverId: String,
    val mapName: String,
    val record: HistoryRecord?,     // 선택 월 레코드. 범위 밖/행 없음이면 null(빈 상태).
)

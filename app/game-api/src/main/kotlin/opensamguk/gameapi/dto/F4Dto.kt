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
    /** 국가명(재야면 F4StateText.NEUTRAL_NATION_NAME). */
    val nationName: String,
    val nationColor: String,
    /** NPC 상태(0 user / 1 possessed-NPC / 2+ pure NPC). FE 이름 색/정렬. */
    val npc: Int,
    val officerLevel: Int,
    /** getOfficerLevelText(officerLevel, nationLevel) — 직책 한글명. */
    val officerLevelText: String,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    /** 명성 레벨 버킷 = getExpLevel(experience). 명성 컬럼 표시("Lv {explevel}")·정렬 키. */
    val explevel: Int,
    /** 명성 칭호 = getHonor(experience). "Lv {explevel} ({honorText})". */
    val honorText: String,
    /** 계급 레벨 버킷 = getDedLevel(dedication). 계급 컬럼 정렬 키. */
    val dedlevel: Int,
    /** 계급 한글명 = getDedLevelText(dedlevel). 계급 컬럼 표시. */
    val dedLevelText: String,
    /** 봉록 = getBillByLevel(dedlevel). 계급 컬럼 부가 표시("({bill})"). */
    val bill: Int,
    val crew: Int,
    val cityName: String,
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
data class DiplomacyNationInfo(
    val id: Int,
    val name: String,
    val color: String,
)

data class DiplomacyLetterParty(
    val nationId: Int,
    val name: String,
    val color: String,
)

data class DiplomacyLetter(
    val no: Int,
    val src: DiplomacyLetterParty,
    val dest: DiplomacyLetterParty,
    val prevNo: Int?,
    val state: String,
    /** Verbatim 제안됨/승인됨/거부됨/대첵됨. */
    val stateText: String,
    val stateOpt: String?,
    val brief: String,
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

// ── GET /api/nation/{id}/finance — page 3 ──────────────────────────────────────────────────────────
data class NationFinanceResponse(
    val result: Boolean,
    val nationId: Int,
    val name: String,
    val color: String,
    val level: Int,
    val gold: Int,
    val rice: Int,
    val income: Int,
    val outcome: Int,
    val rate: Int,
    val bill: Int,
    val warSettingCnt: Int,
    val secretLimit: Int,
    val blockWar: Boolean,
    val blockScout: Boolean,
    val nationMsg: String,
    val scoutMsg: String,
    /** True only when the caller may edit (officer_level >= 5). game-api computes from the principal. */
    val editable: Boolean,
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
data class TroopMember(
    val generalId: Int,
    val name: String,
    val officerLevel: Int,
    val crew: Int,
    val cityName: String,
)

data class TroopRow(
    val troopLeader: Int,
    val name: String,
    val nation: Int,
    val leaderName: String,
    val members: List<TroopMember>,
    /** (N명) member count for the 부대 list header. */
    val memberCount: Int,
)

data class TroopsResponse(
    val result: Boolean,
    val troops: List<TroopRow>,
)

// ── GET /api/history?yearMonth — page 16 ───────────────────────────────────────────────────────────
data class HistoryMonth(
    val year: Int,
    val month: Int,
    val profileName: String,
    val map: Map<String, Any?>,
    val nations: Map<String, Any?>,
)

data class HistoryResponse(
    val result: Boolean,
    val months: List<HistoryMonth>,
)

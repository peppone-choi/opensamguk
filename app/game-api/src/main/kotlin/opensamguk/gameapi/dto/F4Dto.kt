package opensamguk.gameapi.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// F4 — READ-only action-page DTOs. Verbatim field names the web/game pages consume (spec
// 2026-06-03-F4-action-pages-spec.md). All shapes are graceful-empty where the seed has no rows.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

// ── GET /api/generals — PUBLIC, permission=0 fields only (page 14 / 9-P0) ──────────────────────────
/**
 * Public all-general row. permission=0 surface ONLY: NO refresh_score, NO exp breakdown, NO gold/rice.
 * `nation` is the nation NAME (재야 for nationId 0); `nationColor` the hex (#000000 for 재야).
 */
data class PublicGeneral(
    val id: Int,
    val name: String,
    val nation: String,
    val nationColor: String,
    val officerLevel: Int,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
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

data class DiplomacyLetter(
    val id: Int,
    val srcNationId: Int,
    val destNationId: Int,
    val prevId: Int?,
    val state: String,
    /** Verbatim 제안됨/승인됨/거부됨/대체됨. */
    val stateText: String,
    val textBrief: String,
    val textDetail: String,
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

// ── GET /api/diplomacy/conflict — per-city 분쟁% + matrix (page 2) ─────────────────────────────────
data class CityConflict(
    val cityId: Int,
    val cityName: String,
    /** nationId → 분쟁(conflict) percentage; ordered map preserving insertion order. */
    val conflict: Map<String, Int>,
)

data class DiplomacyConflictResponse(
    val result: Boolean,
    val cities: List<CityConflict>,
    /** Global diplomacy matrix: srcNationId → (destNationId → masked stateCode). */
    val matrix: Map<String, Map<String, Int>>,
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
 * (`{value, compensation, possible, title, simpleName, reqArg}`)에 대응.
 *
 * `compensation`/`possible`은 game-api에 아직 포팅되지 않은 PHP `getCompensationStyle()`/
 * `hasMinConditionMet()`에 해당하므로 [AvailableCommandsController]와 동일하게 보수적 기본값
 * (compensation=0, possible=true)을 쓴다. 이는 BLOCKED가 아니라 read-DTO 단계의 알려진 flag —
 * 실제 precheck/보정 표시는 후속 wave에서 연결.
 */
data class ChiefCommand(
    /** 예약 액션 코드(`Util::getClassNameFromObj`). 원천: CommandRegistry 정의 key. */
    val value: String,
    val simpleName: String,
    val title: String,
    /** 보정 스타일(▲/▼). PHP `getCompensationStyle()` 미포팅 → 0(중립) 고정 flag. */
    val compensation: Int,
    /** 최소조건 충족 여부. PHP `hasMinConditionMet()` 미포팅 → true 고정 flag. */
    val possible: Boolean,
    /** 인자 필요 명령 여부(`argsSchema` 비어있지 않음). */
    val reqArg: Boolean,
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

// ── GET /api/votes + /api/votes/{id} — page 5 ──────────────────────────────────────────────────────
data class VoteSummary(
    val id: Int,
    val title: String,
    val openerName: String,
    val multipleOptions: Int,
    val startAt: Instant,
    val endAt: Instant?,
    val closed: Boolean,
)

data class VoteOptionResult(
    val index: Int,
    val text: String,
    val count: Int,
)

data class VoteCommentRow(
    val id: Int,
    val generalName: String,
    val nationName: String,
    val text: String,
    val date: Instant,
)

data class VoteDetailResponse(
    val result: Boolean,
    val id: Int,
    val title: String,
    val body: String,
    val openerName: String,
    val multipleOptions: Int,
    val startAt: Instant,
    val endAt: Instant?,
    val closed: Boolean,
    val options: List<VoteOptionResult>,
    val userCnt: Int,
    /** The calling general's selection (option indices); empty if not voted / no principal. */
    val myVote: List<Int>,
    val comments: List<VoteCommentRow>,
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

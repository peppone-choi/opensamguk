package opensamguk.gameapi.dto

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
data class ChiefReservedTurn(
    val turnIdx: Int,
    val actionCode: String,
    val brief: String,
)

data class ChiefPost(
    val officerLevel: Int,
    val title: String,
    val reservedTurns: List<ChiefReservedTurn>,
)

data class ChiefReservedResponse(
    val result: Boolean,
    val nationId: Int,
    val maxChiefTurn: Int,
    val posts: List<ChiefPost>,
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

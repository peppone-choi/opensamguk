package opensamguk.gameapi.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// D9-D10 — Vote read DTOs. Disjunct from F4Dto.kt. Shapes mirror PHP VoteInfo / GetVoteList /
// GetVoteDetail verbatim (devsam-core frozen historical baseline (ADR-LITE-042; not current product authority)).
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** PHP `VoteInfo` DTO — 7 fields, no body, no closed. */
data class VoteInfo(
    val id: Int,
    val title: String,
    val multipleOptions: Int,
    /** Empty string → null so FE falls back to "[SYSTEM]" via `??`. */
    val opener: String?,
    /** "YYYY-MM-DD HH:MM:SS" — NOT ISO-8601 (FE does lexical comparison). */
    val startDate: String,
    /** "YYYY-MM-DD HH:MM:SS" or null. */
    val endDate: String?,
    /** Option texts in insertion order (poll.options.values). */
    val options: List<String>,
)

/** D9 GetVoteList envelope — voteID-keyed map, preserving insertion order. */
data class VoteListResponse(
    val result: Boolean = true,
    /** voteID → VoteInfo, LinkedHashMap preserves poll list order (newest first). */
    val votes: Map<Int, VoteInfo>,
)

/**
 * One comment row — mirrors PHP `VoteComment` DTO (DTO/VoteComment.php) verbatim:
 * id, voteID(vote_id), generalID(general_id), nationID(nation_id), nationName(nation_name),
 * generalName(general_name), text, date.
 */
data class VoteCommentDto(
    val id: Int,
    @get:JsonProperty("voteID")
    val voteID: Int,
    @get:JsonProperty("generalID")
    val generalID: Int,
    /** PHP VoteComment.nationID (RawName nation_id) — 댓글 작성 당시 국가 id. */
    @get:JsonProperty("nationID")
    val nationID: Int,
    val nationName: String,
    val generalName: String,
    val text: String,
    /** Formatted as "YYYY-MM-DD HH:MM:SS" to match FE expectation. */
    val date: String,
)

/** D10 GetVoteDetail envelope. */
data class VoteDetailResponse(
    val result: Boolean,
    val voteInfo: VoteInfo,
    /** GROUP BY selection — each entry is [List<Int>, Int] (selection combo, count). */
    val votes: List<List<Any>>,
    val comments: List<VoteCommentDto>,
    /** null when not voted / anonymous / no principal. */
    val myVote: List<Int>?,
    /** Total generals with npc < 2 (PHP `SELECT count(*) FROM general WHERE npc < 2`). */
    val userCnt: Int,
)

// ── Shared formatting ─────────────────────────────────────────────────────────────────────────────

private val VOTE_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

/** Format Instant → "YYYY-MM-DD HH:MM:SS" for vote dates (startDate/endDate/comment.date). */
internal fun formatVoteDate(instant: Instant?): String? =
    instant?.let { VOTE_DATE_FORMATTER.format(it) }

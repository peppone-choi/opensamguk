package opensamguk.gameapi.adviser

import com.fasterxml.jackson.annotation.JsonInclude
import opensamguk.logic.input.Phase

/** Root status of `GET /api/retinue/proposals` (D124 consumer ACK, docs/development/retinue-proposals-read.md). */
enum class AdviserProposalsStatus { READY, NOT_SEEDED, UNAVAILABLE }

/** Machine reason for a non-READY root. */
enum class AdviserProposalsReason {
    /** No typed adviser-proposal producer or store exists yet. */
    PROPOSALS_NOT_SEEDED,

    /** The process world, its clock or the actor's world binding cannot be confirmed. */
    WORLD_UNAVAILABLE,
}

/**
 * `proposals` is `null` whenever its source is absent; only a verified empty turn would be `[]`. No row is produced:
 * proposalType/status have no confirmed native enum list yet, the internal score is never published and confidence
 * waits for the server formula (D58), so the element type stays unpublished until a real producer exists.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class AdviserProposalsDto(
    val status: AdviserProposalsStatus,
    val reason: AdviserProposalsReason?,
    val now: Phase?,
    val proposals: List<Nothing>?,
)

data class AdviserProposalsErrorDetailDto(val code: String, val message: String)
data class AdviserProposalsErrorDto(val error: AdviserProposalsErrorDetailDto)

package opensamguk.gameapi.frontier

import com.fasterxml.jackson.annotation.JsonInclude
import opensamguk.logic.input.Phase

/** Root status of `GET /api/frontier` (D124 consumer ACK, docs/development/frontier-read.md). */
enum class FrontierStatus { READY, NOT_SEEDED, UNAVAILABLE }

/** Machine reason for a non-READY root. */
enum class FrontierReason {
    /** No external contact source is persisted for any nation (ExternalContact has no store, codec or producer). */
    CONTACTS_NOT_SEEDED,

    /** The process world, its clock or the actor's world binding cannot be confirmed. */
    WORLD_UNAVAILABLE,

    /** The actor belongs to no nation; frontier relations are a nation matter. */
    NO_NATION,
}

/**
 * `actors` is `null` whenever its source is absent; only a verified empty result would be `[]`. No row is produced
 * yet — a historical CANDIDATE actor is never shown as a contact — so the element type stays unpublished until an
 * ACTIVE, dated, scenario-assigned contact source exists.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class FrontierDto(
    val status: FrontierStatus,
    val reason: FrontierReason?,
    val now: Phase?,
    val actors: List<Nothing>?,
)

data class FrontierErrorDetailDto(val code: String, val message: String)
data class FrontierErrorDto(val error: FrontierErrorDetailDto)

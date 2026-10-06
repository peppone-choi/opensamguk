package opensamguk.gameapi.court.office

import com.fasterxml.jackson.annotation.JsonInclude
import opensamguk.logic.input.Phase

/** Root status of `GET /api/court/local-offices` (D124 consumer ACK, docs/development/court-local-offices-read.md). */
enum class LocalOfficesStatus { READY, NOT_SEEDED, UNAVAILABLE }

/** Machine reason for a non-READY root. Never a guessed office state. */
enum class LocalOfficesReason {
    /** game_env has no `localOfficeTenures` key — no tenure source was ever written. */
    TENURES_NOT_SEEDED,

    /** The process world, its clock or the actor's world binding cannot be confirmed. */
    WORLD_UNAVAILABLE,

    /** The persisted tenure value is malformed or belongs to another world. */
    TENURES_INVALID,

    /** The actor belongs to no nation; local offices are a nation matter. */
    NO_NATION,

    /**
     * Open tenures exist but no jurisdiction snapshot projection (counties, ownership, holder position, warehouse
     * link, magistrate/corps) is connected, so state/missing/actualCountyIds cannot be computed without guessing.
     */
    JURISDICTION_SNAPSHOT_UNAVAILABLE,
}

/**
 * Lists are `null` whenever their source is absent or cannot be computed; only a verified empty result is `[]`.
 * Rows are not produced yet: the effective-state projection has no jurisdiction snapshot source, and appointment
 * options / issued offers have no connected reader. Those element types stay unpublished until a real source exists.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class LocalOfficesDto(
    val status: LocalOfficesStatus,
    val reason: LocalOfficesReason?,
    val now: Phase?,
    val localOffices: List<Nothing>?,
    val appointmentOptions: List<Nothing>?,
    val pendingOffers: List<Nothing>?,
)

data class LocalOfficesErrorDetailDto(val code: String, val message: String)
data class LocalOfficesErrorDto(val error: LocalOfficesErrorDetailDto)

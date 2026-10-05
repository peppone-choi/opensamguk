package opensamguk.gameapi.court.imperial

import opensamguk.gameapi.read.ImperialPresenceBadgeResponse
import opensamguk.logic.input.Phase

enum class ImperialActiveCourtSourceStatus { READY, NOT_SEEDED, UNAVAILABLE }

/** Internal ACTIVE-only materialization, without HTTP, observer ACL or durable revision semantics. */
data class ImperialActiveCourtSource(
    val status: ImperialActiveCourtSourceStatus,
    val context: ImperialActiveCourtContext? = null,
    val lines: List<ImperialActiveCourtLine>? = null,
)

/** Game time identifies this read's context; it is not a CAS token or source revision. */
data class ImperialActiveCourtContext(val worldId: Int, val now: Phase)

/** The presence badge is already ACTIVE-only and contains the authoritative emperor position. */
data class ImperialActiveCourtLine(
    val presence: ImperialPresenceBadgeResponse,
    val courtCityName: String?,
)

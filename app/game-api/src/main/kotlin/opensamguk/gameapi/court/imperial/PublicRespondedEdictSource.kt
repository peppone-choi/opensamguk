package opensamguk.gameapi.court.imperial

import opensamguk.logic.imperial.ProjectedImperialEdict
import opensamguk.logic.input.Phase

enum class PublicRespondedEdictSourceStatus { READY, NOT_SEEDED, UNAVAILABLE }

/** Internal public subset only; no raw edict, privileged viewer or command eligibility. */
data class PublicRespondedEdictSource(
    val status: PublicRespondedEdictSourceStatus,
    val context: PublicRespondedEdictContext? = null,
    val records: List<ProjectedImperialEdict>? = null,
)

/** Actual read context, never a durable revision or CAS token. */
data class PublicRespondedEdictContext(val worldId: Int, val now: Phase)

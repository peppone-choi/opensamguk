package opensamguk.gameapi.adviser

import opensamguk.logic.input.Phase

/**
 * Pure decision. No adviser-proposal producer persists anything yet, so a verified actor gets NOT_SEEDED — never
 * READY with an empty list, which would claim "no proposal this turn" from a source that does not exist.
 */
object AdviserProposalsProjection {
    fun project(now: Phase): AdviserProposalsDto =
        AdviserProposalsDto(AdviserProposalsStatus.NOT_SEEDED, AdviserProposalsReason.PROPOSALS_NOT_SEEDED, now, null)

    fun unavailable(reason: AdviserProposalsReason, now: Phase? = null) =
        AdviserProposalsDto(AdviserProposalsStatus.UNAVAILABLE, reason, now, null)
}

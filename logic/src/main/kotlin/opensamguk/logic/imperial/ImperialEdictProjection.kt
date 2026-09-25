package opensamguk.logic.imperial

enum class EdictViewScope { FULL, PROPOSAL_ONLY, COURIER_ENVELOPE }

data class ProjectedImperialEdict(
    val id: String,
    val imperialLineCode: String,
    val stage: EdictStage,
    val scope: EdictViewScope,
    val text: String?,
    val recipientFactionId: Int?,
)

/** Viewer-specific projection. Secret-order bodies and existence are never sent to outsiders. */
object ImperialEdictProjection {
    fun forViewer(
        edict: ImperialEdict,
        viewerGeneralId: Int?,
        viewerFactionId: Int?,
        authorizedCourtGeneralIds: Set<Int>,
    ): ProjectedImperialEdict? {
        require(viewerGeneralId == null || viewerGeneralId > 0)
        require(viewerFactionId == null || viewerFactionId > 0)
        require(authorizedCourtGeneralIds.all { it > 0 })
        val proposal = edict.proposal
        val receivedByFaction = viewerFactionId == proposal.recipientFactionId &&
            edict.stage in setOf(EdictStage.DELIVERED, EdictStage.RESPONDED)
        val courtInsider = viewerGeneralId != null &&
            (viewerGeneralId == proposal.emperorId || viewerGeneralId == edict.registrarId || viewerGeneralId in authorizedCourtGeneralIds)
        if (courtInsider || receivedByFaction || (!edict.secret && edict.stage == EdictStage.RESPONDED)) {
            return ProjectedImperialEdict(proposal.id, proposal.imperialLineCode, edict.stage,
                EdictViewScope.FULL, edict.review?.reviewedText ?: proposal.proposedText, proposal.recipientFactionId)
        }
        if (viewerGeneralId != null && viewerGeneralId == proposal.proposerId) {
            if (edict.secret) {
                return ProjectedImperialEdict(proposal.id, proposal.imperialLineCode, EdictStage.PROPOSED,
                    EdictViewScope.PROPOSAL_ONLY, proposal.proposedText, proposal.recipientFactionId)
            }
            return ProjectedImperialEdict(proposal.id, proposal.imperialLineCode, edict.stage,
                EdictViewScope.FULL, edict.review?.reviewedText ?: proposal.proposedText, proposal.recipientFactionId)
        }
        if (viewerGeneralId != null && viewerGeneralId == edict.courierId &&
            edict.stage in setOf(EdictStage.DISPATCHED, EdictStage.DELIVERED, EdictStage.RESPONDED)) {
            return ProjectedImperialEdict(proposal.id, proposal.imperialLineCode, EdictStage.DISPATCHED,
                EdictViewScope.COURIER_ENVELOPE, null, proposal.recipientFactionId)
        }
        return null
    }
}

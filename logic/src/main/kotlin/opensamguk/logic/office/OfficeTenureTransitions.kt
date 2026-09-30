package opensamguk.logic.office

/** The handler rechecks OfficeAppointmentRules before calling this transition. */
object OfficeTenureTransitions {
    fun appoint(
        tenureId: String,
        offer: OfficeAppointmentOffer,
        nationId: Int,
        appointedTurn: Long,
        acceptedTurn: Long,
    ): OfficeTenure {
        require(offer.status == OfficeOfferStatus.ACCEPTED) { "appointment offer not accepted" }
        require(nationId > 0 && appointedTurn >= 0 && acceptedTurn >= appointedTurn)
        return OfficeTenure(
            id = tenureId,
            officeId = offer.request.officeId,
            jurisdictionId = offer.request.jurisdictionId,
            holderId = offer.request.candidateId,
            issuerId = offer.request.issuerId,
            nationId = nationId,
            origin = OfficeClaimOrigin.POLITY_APPOINTMENT,
            appointedTurn = appointedTurn,
            acceptedTurn = acceptedTurn,
        )
    }

    /** Arrival is a historical fact; later loss of the seat leaves a nominal, not effective, tenure. */
    fun arrive(tenure: OfficeTenure, snapshot: OfficeJurisdictionSnapshot, turn: Long): OfficeTenure {
        require(tenure.isActive && tenure.acceptedTurn != null && turn >= tenure.acceptedTurn)
        require(tenure.jurisdictionId == snapshot.jurisdictionId)
        require(snapshot.holderCountyId == snapshot.seatCountyId && snapshot.seatCountyId in snapshot.ownedCountyIds) {
            "holder must arrive at an owned jurisdiction seat"
        }
        if (tenure.isAssumed) {
            require(tenure.seatCountyId == snapshot.seatCountyId) { "already assumed at another seat" }
            return tenure
        }
        return tenure.copy(assumedTurn = turn, seatCountyId = snapshot.seatCountyId)
    }

    /** Death, retirement, dismissal, or departure ends the tenure; the caller records the cause. */
    fun end(tenure: OfficeTenure, turn: Long): OfficeTenure {
        require(tenure.isActive && tenure.acceptedTurn != null && turn >= tenure.acceptedTurn)
        return tenure.copy(endedTurn = turn)
    }
}

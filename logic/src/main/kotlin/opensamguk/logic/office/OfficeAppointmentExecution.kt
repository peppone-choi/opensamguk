package opensamguk.logic.office

sealed interface OfficeAppointmentCompletion {
    data class Appointed(val tenure: OfficeTenure) : OfficeAppointmentCompletion
    data class Denied(val reason: OfficeAppointmentFailure) : OfficeAppointmentCompletion
}

sealed interface OfficeDismissalCompletion {
    data class Dismissed(val tenure: OfficeTenure) : OfficeDismissalCompletion
    data class Denied(val reason: OfficeAppointmentFailure) : OfficeDismissalCompletion
}

/** Reuses the precheck assessment on fresh state immediately before changing a tenure. */
object OfficeAppointmentExecution {
    fun complete(
        tenureId: String,
        offer: OfficeAppointmentOffer,
        freshContext: OfficeAppointmentContext,
        catalog: OfficeCatalog,
        rules: OfficeRules,
        appointedTurn: Long,
        acceptedTurn: Long,
    ): OfficeAppointmentCompletion {
        val assessment = OfficeAppointmentRules.assess(offer.request, freshContext, catalog, rules)
        if (assessment is OfficeAppointmentAssessment.Denied) return OfficeAppointmentCompletion.Denied(assessment.reason)
        if (offer.status != OfficeOfferStatus.ACCEPTED) {
            return OfficeAppointmentCompletion.Denied(OfficeAppointmentFailure.OFFER_NOT_ACCEPTED)
        }
        if (freshContext.activeTenures.any { it.id == tenureId }) {
            return OfficeAppointmentCompletion.Denied(OfficeAppointmentFailure.DUPLICATE_TENURE_ID)
        }
        return OfficeAppointmentCompletion.Appointed(OfficeTenureTransitions.appoint(
            tenureId, offer, freshContext.issuerNationId, appointedTurn, acceptedTurn,
        ))
    }

    fun dismiss(
        issuerNationId: Int,
        issuerIsRuler: Boolean,
        tenureId: String,
        tenures: Collection<OfficeTenure>,
        turn: Long,
    ): OfficeDismissalCompletion {
        val assessment = OfficeAppointmentRules.assessDismissal(issuerNationId, issuerIsRuler, tenureId, tenures)
        if (assessment is OfficeAppointmentAssessment.Denied) return OfficeDismissalCompletion.Denied(assessment.reason)
        val tenure = tenures.single { it.id == tenureId }
        return OfficeDismissalCompletion.Dismissed(OfficeTenureTransitions.end(tenure, turn))
    }
}

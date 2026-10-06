package opensamguk.gameapi.court.offer

import opensamguk.logic.office.OfficeAppointmentOffer

/** Preserve stored facts, never perform a reply, expiry transition or opaque lookup. */
object OfficeStoredOfferView {
    fun project(offer: OfficeAppointmentOffer): CourtOfferDto = CourtOfferDto(
        kind = CourtOfferSourceKind.OFFICE,
        sourceRef = CourtOfferSourceRefDto(CourtOfferSourceKind.OFFICE, offer.id),
        fromGeneralId = offer.request.issuerId,
        terms = OfficeOfferTermsDto(offer.request.officeId, offer.request.jurisdictionId, offer.request.seatCountyId),
        issuedAt = offer.issuedAt,
        dueAt = offer.dueAt,
        state = offer.status.name,
    )
}

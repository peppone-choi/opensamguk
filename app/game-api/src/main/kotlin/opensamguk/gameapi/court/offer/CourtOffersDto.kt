package opensamguk.gameapi.court.offer

import com.fasterxml.jackson.annotation.JsonInclude
import opensamguk.logic.input.Phase

enum class CourtOffersStatus { READY, UNAVAILABLE }
enum class CourtOfferSourceKind { OFFICE, VASSAL_FOUNDING, VASSAL_AMENDMENT, OFFICE_NOMINATION }
enum class CourtOfferReadStatus { READY, UNAVAILABLE }
enum class CourtOfferRevisionStatus { UNVERSIONED }

@JsonInclude(JsonInclude.Include.ALWAYS)
data class CourtOffersDto(
    val status: CourtOffersStatus,
    val now: Phase?,
    val offers: List<CourtOfferDto>,
    val sources: List<CourtOfferSourceDto>,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class CourtOfferSourceDto(
    val sourceKind: CourtOfferSourceKind,
    val readStatus: CourtOfferReadStatus,
    val records: List<CourtOfferDto>? = null,
    val revisionStatus: CourtOfferRevisionStatus? = null,
    val revisionToken: String? = null,
)

interface CourtOfferTermsDto

data class OfficeOfferTermsDto(
    val officeId: String,
    val jurisdictionId: String,
    val seatCountyId: Int,
) : CourtOfferTermsDto

data class CourtOfferSourceRefDto(val kind: CourtOfferSourceKind, val id: String)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class CourtOfferDto(
    val kind: CourtOfferSourceKind,
    val sourceRef: CourtOfferSourceRefDto,
    val fromGeneralId: Int,
    val terms: CourtOfferTermsDto,
    val issuedAt: Phase,
    val dueAt: Phase,
    val state: String,
    val offerId: String? = null,
    val replyInputId: String? = null,
    val responseStatus: CourtOfferReadStatus = CourtOfferReadStatus.UNAVAILABLE,
    val responseOptions: List<Nothing> = emptyList(),
)

data class CourtOfferErrorDetailDto(val code: String, val message: String)
data class CourtOfferErrorDto(val error: CourtOfferErrorDetailDto)

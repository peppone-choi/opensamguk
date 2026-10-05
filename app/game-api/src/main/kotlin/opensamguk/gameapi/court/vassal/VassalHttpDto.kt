package opensamguk.gameapi.court.vassal

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonUnwrapped
import opensamguk.logic.input.Phase

enum class VassalHttpStatus { PARTIAL, UNAVAILABLE }
enum class VassalFieldStatus { READY, UNAVAILABLE }
enum class MonthlyTributeStatus { PAID, UNPAID, ZERO_DUE, NO_RECEIPT, UNAVAILABLE }

@JsonInclude(JsonInclude.Include.ALWAYS)
data class VassalHttpDto(
    val status: VassalHttpStatus,
    val now: Phase?,
    val contractsStatus: StoredVassalTermsStatus,
    val contracts: List<VassalDisplayDto> = emptyList(),
    val activityStatus: VassalFieldStatus = VassalFieldStatus.UNAVAILABLE,
    val foundingOptionsStatus: VassalFieldStatus = VassalFieldStatus.UNAVAILABLE,
    val foundingOptions: List<Nothing> = emptyList(),
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class VassalDisplayDto(
    @get:JsonUnwrapped val terms: VassalContractTermsDto,
    @get:JsonProperty("isHumanStatus")
    val isHumanStatus: VassalFieldStatus,
    val monthlyTribute: MonthlyTributeDto,
    val calendarStatus: VassalFieldStatus = VassalFieldStatus.UNAVAILABLE,
    val signedAt: Phase? = null,
    val expiresAt: Phase? = null,
    val endedAt: Phase? = null,
    val reinforcementResponse: VassalResponseDeadlineDto = VassalResponseDeadlineDto(),
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class MonthlyTributeDto(
    val status: MonthlyTributeStatus,
    val receipt: VassalTributeTermsDto?,
    val obligationStatus: VassalFieldStatus = VassalFieldStatus.UNAVAILABLE,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class VassalResponseDeadlineDto(
    val status: VassalFieldStatus = VassalFieldStatus.UNAVAILABLE,
    val requestId: String? = null,
    val dueTurn: Long? = null,
    val dueAt: Phase? = null,
)

data class VassalErrorDetailDto(val code: String, val message: String)
data class VassalErrorDto(val error: VassalErrorDetailDto)

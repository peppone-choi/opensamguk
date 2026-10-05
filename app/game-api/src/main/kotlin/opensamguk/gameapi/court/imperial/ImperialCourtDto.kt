package opensamguk.gameapi.court.imperial

import com.fasterxml.jackson.annotation.JsonInclude

enum class ImperialCourtStatus { READY, NOT_SEEDED, STATE_UNAVAILABLE }
enum class ImperialCourtFieldState { READY, NOT_APPLICABLE, UNAVAILABLE }

data class ImperialCourtDto(val status: ImperialCourtStatus, val lines: List<ImperialCourtLineDto>)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ImperialCourtLineDto(
    val code: String,
    val name: String,
    val status: String,
    val holderGeneralId: Int?,
    val emperorName: String?,
    val courtCityId: Int?,
    val courtCityName: String?,
    val regentGeneralId: Int?,
    val regentName: String?,
    val courtNationId: Int?,
    val courtNationName: String?,
    val fieldStates: ImperialCourtFieldStates,
)

data class ImperialCourtFieldStates(
    val holder: ImperialCourtFieldState,
    val courtCity: ImperialCourtFieldState,
    val regent: ImperialCourtFieldState,
    val courtNation: ImperialCourtFieldState,
)

data class ImperialCourtErrorDto(val error: ImperialCourtErrorDetailDto)
data class ImperialCourtErrorDetailDto(val code: String, val message: String)

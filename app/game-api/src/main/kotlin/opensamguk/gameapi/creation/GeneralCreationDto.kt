package opensamguk.gameapi.creation

data class GeneralCreationStatsDto(
    val leadership: Int, val strength: Int, val intel: Int, val politics: Int, val charm: Int,
)

data class GeneralCreationChoiceDto(
    val kind: String,
    val name: String? = null,
    val nativeCountyId: Int? = null,
    val stats: GeneralCreationStatsDto? = null,
    val ideologyId: String? = null,
    val traitId: String? = null,
    val role: String? = null,
    val historicalGeneralId: Int? = null,
)

data class GeneralCreationRequestDto(
    val expectedWorldId: Int,
    val clientRequestId: String,
    val choice: GeneralCreationChoiceDto,
)

/** 202 acknowledges one durable admission key; it does not assert that a general exists. */
data class GeneralCreationAcceptedDto(
    val schemaVersion: Int = 1,
    val status: String = "ACCEPTED",
    val requestId: String,
    val worldId: Int,
)

data class GeneralCreationErrorDto(val code: String, val message: String)
data class GeneralCreationErrorResponseDto(val error: GeneralCreationErrorDto)

data class GeneralCreationResultDto(
    val schemaVersion: Int = 1,
    val requestId: String,
    val worldId: Int,
    val status: String,
    val generalId: Int?,
    val error: GeneralCreationErrorDto?,
) {
    init {
        require(status in setOf("PENDING", "CREATED", "REJECTED"))
        require((status == "CREATED") == (generalId != null))
        require((status == "REJECTED") == (error != null))
    }
}

data class HistoricalCreationPersonDto(
    val historicalGeneralId: Int,
    val name: String,
    val nameCh: String?,
    val portrait: String?,
    val stats: GeneralCreationStatsDto,
    val nationId: Int?,
    val appeared: Boolean,
    val available: Boolean,
    val unavailableCode: String?,
)

data class HistoricalCreationPageDto(
    val schemaVersion: Int = 1,
    val worldId: Int,
    val people: List<HistoricalCreationPersonDto>,
    val nextCursor: String?,
)

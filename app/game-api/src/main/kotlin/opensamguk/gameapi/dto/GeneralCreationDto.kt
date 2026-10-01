package opensamguk.gameapi.dto

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

data class GeneralCreationOptionDto(val id: String, val label: String)
data class GeneralCreationNameRuleDto(
    val minimumCodePoints: Int,
    val maximumCodePoints: Int,
    val normalization: String,
    val allowedCharacters: String,
    val uniquenessScope: String,
)
data class GeneralCreationModeDto(val kind: String, val allowed: Boolean, val reason: String? = null)
data class GeneralCreationStatRuleDto(val min: Int, val max: Int, val total: Int)
data class GeneralCreationPolicyDto(
    val customAllowed: Boolean,
    val historicalAllowed: Boolean,
    val reason: String? = null,
)
data class GeneralCreationCountyDto(
    val cityId: Int,
    val name: String,
    val commanderyId: String?,
    val commanderyName: String?,
    val provinceName: String?,
    val cellCol: Int?,
    val cellRow: Int?,
    val available: Boolean,
    val reason: String? = null,
)
data class GeneralCreationOptionsDto(
    val schemaVersion: Int = 1,
    val worldId: Int,
    val statRule: GeneralCreationStatRuleDto,
    val nameRule: GeneralCreationNameRuleDto,
    val policy: GeneralCreationPolicyDto,
    val modes: List<GeneralCreationModeDto>,
    val ideologies: List<GeneralCreationOptionDto>,
    val traits: List<GeneralCreationOptionDto>,
    val nativeCounties: List<GeneralCreationCountyDto>,
)

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

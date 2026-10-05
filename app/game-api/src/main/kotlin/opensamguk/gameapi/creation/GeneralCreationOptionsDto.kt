package opensamguk.gameapi.creation

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

/** K5-02 기본 선택지. 역할 정원·포상·태수 후보는 원천 계약이 확정되면 별도 확장한다. */
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

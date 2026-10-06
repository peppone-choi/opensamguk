package opensamguk.gameapi.creation

import com.fasterxml.jackson.annotation.JsonInclude

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
data class GeneralCreationPlayerCapDto(val used: Long, val max: Int)
data class GeneralCreationRoleDto(
    val path: String,
    val role: String,
    val allowed: Boolean,
    val reason: String?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val used: Long? = null,
) {
    /** D121: a role has no separate headcount limit. Keep null even under NON_NULL mappers. */
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val cap: Int? = null
}
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

/** K5-02 생성 선택지. 역할별 사용량은 아직 원천이 없어 null(UNKNOWN)이다. */
data class GeneralCreationOptionsDto(
    val schemaVersion: Int = 1,
    val worldId: Int,
    val statRule: GeneralCreationStatRuleDto,
    val nameRule: GeneralCreationNameRuleDto,
    val policy: GeneralCreationPolicyDto,
    val playerCap: GeneralCreationPlayerCapDto,
    val roles: List<GeneralCreationRoleDto>,
    val modes: List<GeneralCreationModeDto>,
    val ideologies: List<GeneralCreationOptionDto>,
    val traits: List<GeneralCreationOptionDto>,
    val nativeCounties: List<GeneralCreationCountyDto>,
)

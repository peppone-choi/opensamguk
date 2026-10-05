package opensamguk.gameapi.city

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import opensamguk.gameapi.dto.*

/** Null means unknown or withheld. Indicator values retain their storage units and numeric types. */
data class CountyGarrisonDto(val troops: Int, val training: Int, val morale: Int)
@JsonInclude(JsonInclude.Include.ALWAYS)
data class CountyIntegerIndicatorDto(val value: Int, val max: Int,
    @field:JsonProperty("trend") val trend: Nothing? = null)
@JsonInclude(JsonInclude.Include.ALWAYS)
data class CountyDecimalIndicatorDto(val value: Double, val max: Double,
    @field:JsonProperty("trend") val trend: Nothing? = null)
@JsonInclude(JsonInclude.Include.ALWAYS)
data class CountyIndicatorsDto(
    val population: CountyIntegerIndicatorDto? = null,
    val agriculture: CountyIntegerIndicatorDto? = null,
    val commerce: CountyIntegerIndicatorDto? = null,
    val security: CountyIntegerIndicatorDto? = null,
    val trust: CountyDecimalIndicatorDto? = null,
    val defence: CountyIntegerIndicatorDto? = null,
    val wall: CountyIntegerIndicatorDto? = null,
)
data class CountyGradeDto(val code: Int, val label: String)
data class CountyPersonHereDto(
    val generalId: Int, val name: String, val portrait: DirectoryPortrait,
    val affiliation: DirectoryAffiliation?, val relation: String,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class CountyDetailDto(
    val status: String,
    val cityId: Int,
    val name: String? = null,
    val nameCh: String? = null,
    val grade: CountyGradeDto? = null,
    val commandery: CountyDirectoryCommandery? = null,
    val owner: DirectoryAffiliation? = null,
    val visibility: String? = null,
    val intelAgeTurns: Int? = null,
    val indicators: CountyIndicatorsDto = CountyIndicatorsDto(),
    val specialties: List<SpecialtyDto>? = null,
    val garrison: CountyGarrisonDto? = null,
    val income: CountyIncomeDto? = null,
    val period: String = "GAME_MONTH",
    val basis: String = "CURRENT_STATE_FORECAST",
    val stamp: StampDto? = null,
    val peopleHere: List<CountyPersonHereDto>? = null,
    // Jackson skips JVM Void getters; explicit fields preserve the null-only wire.
    @field:JsonProperty("front") val front: Nothing? = null,
    @field:JsonProperty("seasonalEvent") val seasonalEvent: Nothing? = null,
    val unavailableReasons: Map<String, String> = emptyMap(),
)

package opensamguk.gameapi.people

import com.fasterxml.jackson.annotation.JsonProperty
import opensamguk.gameapi.dto.BondDto
import opensamguk.gameapi.dto.DirectoryAffiliation
import opensamguk.gameapi.dto.DirectoryAptitudes
import opensamguk.gameapi.dto.DirectoryPortrait
import opensamguk.gameapi.dto.DirectoryStats

/** Current location resolved in the selected world; never an invented name. */
data class PersonLocationDto(val cityId: Int, val name: String)

/** The actor's own retainer card for the target. Cost is the renown upkeep, not a salary. */
data class PersonRetinueCardDto(
    val retainerId: Int,
    val loyalty: Int,
    val cost: Int?,
    val roleLabel: String?,
    val taskLabel: String?,
    val departureOrder: Int?,
)

/**
 * K4-13 person detail (`GET /api/people/{targetGeneralId}`). Null means unknown or not authorized; the reason
 * for every null field is in [unavailableReasons] as a JSON pointer. Private fields open only for SELF and the
 * actor's direct RETINUE — SAME_NATION grants nothing, and OTHER is never turned into an enemy flag.
 */
data class PersonDetailDto(
    val status: String,
    val generalId: Int,
    val relation: String? = null,
    val name: String? = null,
    val portrait: DirectoryPortrait? = null,
    val affiliation: DirectoryAffiliation? = null,
    val stats: DirectoryStats? = null,
    val aptitudes: DirectoryAptitudes? = null,
    val role: String? = null,
    val lordGeneralId: Int? = null,
    val location: PersonLocationDto? = null,
    val bonds: List<BondDto>? = null,
    val injured: Boolean? = null,
    val retinue: PersonRetinueCardDto? = null,
    @field:JsonProperty("placement") val placement: Nothing? = null,
    @field:JsonProperty("offices") val offices: Nothing? = null,
    val unavailableReasons: Map<String, String> = emptyMap(),
)

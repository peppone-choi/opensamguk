package opensamguk.gameapi.dto

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Vision read contracts (docs/superpowers/specs/2026-09-23-hwiha-vision-contract.md §4).
 * Every DTO omits null fields: a field the viewer is not entitled to is absent from the bytes, never `null`.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class HwihaStampDto(val year: Int, val month: Int, val phase: Int)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class HwihaVisibilityCommanderyDto(
    val no: Int,
    val id: String,
    val name: String,
    val tier: String,
    val seenAtStamp: HwihaStampDto? = null,
    val ageTurns: Int? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class HwihaVisionSourceDto(
    val kind: String,
    val commanderyNo: Int,
    val radius: Int,
    val provinceId: String? = null,
    val refId: Int? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class HwihaVisibilityResponse(
    val status: String,
    val stamp: HwihaStampDto? = null,
    val commanderies: List<HwihaVisibilityCommanderyDto>? = null,
    val sources: List<HwihaVisionSourceDto>? = null,
    /** Unreadable optional source rows (scout posts / works) — counted, never turned into vision. */
    val invalidSourceRecords: Int? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class HwihaTroopBandDto(val code: String, val label: String)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class HwihaCorpsDto(
    val corpsId: String,
    val ownerGeneralId: Int,
    val ownerName: String? = null,
    val commanderGeneralId: Int,
    val commanderName: String? = null,
    val nationId: Int,
    val nationColor: String? = null,
    val provinceId: String,
    val commanderyNo: Int,
    val visibility: String,
    val own: Boolean,
    /** Own corps only. */
    val troops: Int? = null,
    /** Other corps only. */
    val troopsBand: HwihaTroopBandDto? = null,
    /** Own corps only: remaining land path from the current province (province ids, current first). */
    val marchPath: List<String>? = null,
    val destinationProvinceId: String? = null,
    /** INTEL only. */
    val lastSeenStamp: HwihaStampDto? = null,
    val ageTurns: Int? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class HwihaCorpsResponse(
    val status: String,
    val stamp: HwihaStampDto? = null,
    val corps: List<HwihaCorpsDto>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class HwihaResourceCostDto(val money: Long, val grain: Long, val iron: Long, val timber: Long, val horses: Long)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class HwihaScoutOptionDto(
    val no: Int,
    val id: String,
    val name: String,
    val tier: String,
    val available: Boolean,
    val code: String? = null,
    val reason: String? = null,
    val seenAtStamp: HwihaStampDto? = null,
    val ageTurns: Int? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class HwihaScoutOriginDto(val provinceId: String, val commanderyNo: Int, val id: String, val name: String)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class HwihaScoutOptionsResponse(
    val status: String,
    val inputId: String = "action.scout",
    val available: Boolean = false,
    val code: String? = null,
    val reason: String? = null,
    val origin: HwihaScoutOriginDto? = null,
    val cost: HwihaResourceCostDto? = null,
    val options: List<HwihaScoutOptionDto>? = null,
)

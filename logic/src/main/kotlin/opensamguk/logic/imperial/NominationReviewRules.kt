package opensamguk.logic.imperial

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.logic.content.ContentLedgerValidator
import opensamguk.logic.office.OfficeClaimOrigin

enum class NominationFactor {
    VACANCY_AND_CAPACITY, PROPOSER_AUTHORITY, CANDIDATE_MERIT, COURT_SUPPORT,
    TERRITORIAL_RELATION, CLEAR_OF_COMPETITOR, ENFORCEMENT_CAPACITY,
}

data class NominationReviewRules(
    val weights: Map<NominationFactor, Int>,
    val reviewTurnLimit: Int,
    val originStartRecognition: Map<OfficeClaimOrigin, Int>,
) {
    init {
        require(weights.keys == NominationFactor.entries.toSet() && weights.values.all { it > 0 })
        require(reviewTurnLimit > 0)
        require(originStartRecognition.keys == OfficeClaimOrigin.entries.toSet())
        require(originStartRecognition.values.all { it in 0..100 })
    }

    /** A court-specific review aid, never an automatic imperial appointment. */
    fun score(verifiedFactors: Set<NominationFactor>): Int = verifiedFactors.sumOf { weights.getValue(it) }

    companion object {
        fun fromJson(raw: String): NominationReviewRules {
            ContentLedgerValidator.requireValid(raw)
            val row = Json.parseToJsonElement(raw).jsonObject.getValue("rows").jsonArray.single().jsonObject
            require(row.getValue("id").jsonPrimitive.content == "nomination.review-rules")
            val values = row.getValue("values").jsonObject
            fun number(key: String) = values.getValue(key).jsonObject.getValue("value").jsonPrimitive.int
            return NominationReviewRules(
                weights = NominationFactor.entries.associateWith { number("factor.${it.name}") },
                reviewTurnLimit = number("reviewTurnLimit"),
                originStartRecognition = OfficeClaimOrigin.entries.associateWith { number("origin.${it.name}") },
            )
        }
    }
}

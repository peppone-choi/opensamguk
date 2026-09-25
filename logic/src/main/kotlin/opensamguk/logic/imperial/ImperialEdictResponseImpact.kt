package opensamguk.logic.imperial

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.logic.content.ContentLedgerValidator

data class EdictResponseRules(val courtFavorDelta: Map<EdictRecipientDecision, Int>) {
    init {
        require(courtFavorDelta.keys == EdictRecipientDecision.entries.toSet())
        require(courtFavorDelta.getValue(EdictRecipientDecision.ACCEPT) == 0)
        require(courtFavorDelta.getValue(EdictRecipientDecision.PARTIAL_ACCEPT) <= 0)
        require(courtFavorDelta.getValue(EdictRecipientDecision.DELAY) < 0)
        require(courtFavorDelta.getValue(EdictRecipientDecision.REFUSE) < courtFavorDelta.getValue(EdictRecipientDecision.DELAY))
        require(courtFavorDelta.getValue(EdictRecipientDecision.DENOUNCE) < courtFavorDelta.getValue(EdictRecipientDecision.REFUSE))
    }

    companion object {
        fun fromJson(raw: String): EdictResponseRules {
            ContentLedgerValidator.requireValid(raw)
            val row = Json.parseToJsonElement(raw).jsonObject.getValue("rows").jsonArray.single().jsonObject
            require(row.getValue("id").jsonPrimitive.content == "edict.response-rules")
            val values = row.getValue("values").jsonObject
            return EdictResponseRules(EdictRecipientDecision.entries.associateWith { decision ->
                values.getValue(decision.name).jsonObject.getValue("value").jsonPrimitive.int
            })
        }
    }
}

/** A single effect description keyed by the edict id; the event adapter applies it once. */
data class EdictResponseImpact(
    val requestId: String,
    val imperialLineCode: String,
    val recipientFactionId: Int,
    val courtFavorDelta: Int,
    val publiclyDenounced: Boolean,
)

object ImperialEdictResponseImpact {
    fun derive(edict: ImperialEdict, rules: EdictResponseRules): EdictResponseImpact {
        require(edict.stage == EdictStage.RESPONDED)
        val receipt = requireNotNull(edict.receipt)
        require(receipt.recipientFactionId == edict.proposal.recipientFactionId)
        return EdictResponseImpact(
            requestId = "edict-response:${edict.proposal.id}",
            imperialLineCode = edict.proposal.imperialLineCode,
            recipientFactionId = receipt.recipientFactionId,
            courtFavorDelta = rules.courtFavorDelta.getValue(receipt.decision),
            publiclyDenounced = receipt.decision == EdictRecipientDecision.DENOUNCE,
        )
    }
}

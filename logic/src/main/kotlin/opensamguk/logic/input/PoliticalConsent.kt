package opensamguk.logic.input

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The recipient acts on their own account. A later refusal replaces an earlier acceptance. */
data class PoliticalConsent(val issuerGeneralId: Int, val inputId: String, val accepted: Boolean) {
    fun toMetaValue(): Map<String, Any?> = mapOf("issuerGeneralId" to issuerGeneralId,
        "inputId" to inputId, "accepted" to accepted)

    companion object {
        const val META_KEY = "politicalConsent"
        const val COURT_INPUT_ID = "court.politicalConsent"
        fun parse(actorId: Int, raw: String?): PoliticalConsent? = try {
            if (actorId <= 0 || raw == null) null else {
                val fields = FlatArguments(raw).read()
                if (fields.keys != setOf("issuerGeneralId", "inputId", "accepted")) null else {
                    val issuer = (fields["issuerGeneralId"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                    val input = (fields["inputId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val accepted = (fields["accepted"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                    if (issuer == null || issuer <= 0 || issuer == actorId || input !in PoliticalInput.TARGET_IDS || accepted == null)
                        null else PoliticalConsent(issuer, input!!, accepted)
                }
            }
        } catch (_: IllegalArgumentException) { null }

        fun read(meta: Map<String, Any?>): PoliticalConsent? {
            val raw = meta[META_KEY] ?: return null
            require(raw is Map<*, *> && raw.keys == setOf("issuerGeneralId", "inputId", "accepted"))
            val issuer = (raw["issuerGeneralId"] as? Number)?.toInt()
            val input = raw["inputId"] as? String
            val accepted = raw["accepted"] as? Boolean
            require(issuer != null && issuer > 0 && input in PoliticalInput.TARGET_IDS && accepted != null)
            return PoliticalConsent(issuer, input!!, accepted)
        }

        fun canonicalJson(consent: PoliticalConsent): String = buildJsonObject {
            put("issuerGeneralId", consent.issuerGeneralId)
            put("inputId", consent.inputId)
            put("accepted", consent.accepted)
        }.toString()
    }
}

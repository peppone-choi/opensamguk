package opensamguk.logic.imperial

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import opensamguk.logic.office.OfficeClaimOrigin

/** Separate append-only history. A court confirmation never rewrites a local OfficeTenure. */
object OfficeClaimHistoryCodec {
    const val META_KEY = "officeClaimHistory"

    fun encode(history: List<OfficeClaimRecord>): String {
        validateHistory(history)
        return buildJsonObject {
            put("version", 1)
            put("claims", buildJsonArray {
                history.forEach { claim ->
                    add(buildJsonObject {
                        put("id", claim.id)
                        put("officeId", claim.officeId)
                        put("claimantId", claim.claimantId)
                        put("origin", claim.origin.name)
                        put("issuerId", claim.issuerId)
                        claim.previousClaimId?.let { put("previousClaimId", it) }
                        claim.nominationId?.let { put("nominationId", it) }
                        claim.edictId?.let { put("edictId", it) }
                        put("recognitionByPolity", buildJsonObject {
                            claim.recognitionByPolity.toSortedMap().forEach { (polityId, recognition) ->
                                put(polityId.toString(), recognition.name)
                            }
                        })
                    })
                }
            })
        }.toString()
    }

    fun decode(raw: String?): List<OfficeClaimRecord> {
        if (raw == null) return emptyList()
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: invalid()
        require(root.keys == setOf("version", "claims") && root.getValue("version").jsonPrimitive.int == 1)
        val claims = (root["claims"] as? JsonArray ?: invalid()).map { element ->
            val row = element as? JsonObject ?: invalid()
            require(row.keys.containsAll(setOf("id", "officeId", "claimantId", "origin", "issuerId", "recognitionByPolity")))
            require(row.keys.all { it in setOf("id", "officeId", "claimantId", "origin", "issuerId", "previousClaimId", "nominationId", "edictId", "recognitionByPolity") })
            val recognition = (row["recognitionByPolity"] as? JsonObject ?: invalid()).map { (key, value) ->
                val id = key.toIntOrNull() ?: invalid()
                id to ClaimRecognition.valueOf(value.jsonPrimitive.content)
            }.toMap()
            OfficeClaimRecord(
                id = row.requiredString("id"),
                officeId = row.requiredString("officeId"),
                claimantId = row.getValue("claimantId").jsonPrimitive.int,
                origin = OfficeClaimOrigin.valueOf(row.requiredString("origin")),
                issuerId = row.getValue("issuerId").jsonPrimitive.int,
                previousClaimId = row.optionalString("previousClaimId"),
                nominationId = row.optionalString("nominationId"),
                edictId = row.optionalString("edictId"),
                recognitionByPolity = recognition,
            )
        }
        validateHistory(claims)
        return claims
    }

    private fun validateHistory(history: List<OfficeClaimRecord>) {
        require(history.map { it.id }.toSet().size == history.size)
        val seen = mutableMapOf<String, OfficeClaimRecord>()
        history.forEach { claim ->
            claim.previousClaimId?.let { previousId ->
                val previous = requireNotNull(seen[previousId]) { "claim predecessor must precede confirmation" }
                require(previous.officeId == claim.officeId && previous.claimantId == claim.claimantId)
            }
            seen[claim.id] = claim
        }
    }

    private fun JsonObject.requiredString(key: String): String =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: invalid()

    private fun JsonObject.optionalString(key: String): String? = get(key)?.let {
        (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString && primitive.content.isNotBlank() }?.content ?: invalid()
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid office claim history")
}

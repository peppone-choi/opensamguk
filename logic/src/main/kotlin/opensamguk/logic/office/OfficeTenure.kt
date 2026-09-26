package opensamguk.logic.office

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
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import opensamguk.logic.content.ContentLedgerValidator

enum class OfficeClaimOrigin {
    IMPERIAL_GRANT, COURT_CONFIRMED, POLITY_APPOINTMENT, SELF_STYLED,
    ACTING_XING, CONCURRENT_LING, CAMPAIGN_COMMISSION, POSTHUMOUS,
}

enum class OfficeCredentialKind { ACTING_XING, CONCURRENT_LING, SEAL, TALLY, DOCUMENT }

data class OfficeCredential(
    val id: String,
    val kind: OfficeCredentialKind,
    val issuerId: Int,
    val effectiveFromTurn: Long,
    val expiresAtTurn: Long? = null,
) {
    init {
        require(id.isNotBlank() && issuerId > 0 && effectiveFromTurn >= 0)
        require(expiresAtTurn == null || expiresAtTurn >= effectiveFromTurn)
    }
}

/** Claim and physical assumption are distinct; holding a name never grants authority by itself. */
data class OfficeTenure(
    val id: String,
    val officeId: String,
    val jurisdictionId: String,
    val holderId: Int,
    val issuerId: Int,
    val nationId: Int,
    val origin: OfficeClaimOrigin,
    val appointedTurn: Long,
    val acceptedTurn: Long? = null,
    val assumedTurn: Long? = null,
    val seatCountyId: Int? = null,
    val credentialIds: List<String> = emptyList(),
    val endedTurn: Long? = null,
) {
    init {
        require(id.isNotBlank() && officeId.isNotBlank() && jurisdictionId.isNotBlank())
        require(holderId > 0 && issuerId > 0 && nationId > 0 && appointedTurn >= 0)
        require(acceptedTurn == null || acceptedTurn >= appointedTurn)
        if (assumedTurn != null) require(acceptedTurn != null && assumedTurn >= acceptedTurn)
        if (seatCountyId != null) require(assumedTurn != null && seatCountyId > 0)
        require(endedTurn == null || endedTurn >= (assumedTurn ?: acceptedTurn ?: appointedTurn))
        require(credentialIds.all { it.isNotBlank() } && credentialIds.size == credentialIds.toSet().size)
    }

    val isActive: Boolean get() = acceptedTurn != null && endedTurn == null
    val isAssumed: Boolean get() = assumedTurn != null && endedTurn == null
}

data class OfficeRules(val minimumOwnedPercent: Int, val maximumConcurrentLocalTenures: Int) {
    init {
        require(minimumOwnedPercent in 1..100)
        require(maximumConcurrentLocalTenures > 0)
    }

    companion object {
        fun fromJson(raw: String): OfficeRules {
            ContentLedgerValidator.requireValid(raw)
            val row = Json.parseToJsonElement(raw).jsonObject["rows"]!!.jsonArray.single().jsonObject
            require(row["id"]?.jsonPrimitive?.content == "office.actual-jurisdiction")
            val values = row["values"]!!.jsonObject
            return OfficeRules(
                minimumOwnedPercent = values["minimumOwnedPercent"]!!.jsonObject["value"]!!.jsonPrimitive.int,
                maximumConcurrentLocalTenures = values["maximumConcurrentLocalTenures"]!!.jsonObject["value"]!!.jsonPrimitive.int,
            )
        }

        fun loadClasspath(): OfficeRules = fromJson(requireNotNull(OfficeRules::class.java.getResourceAsStream("/office/office-rules.json")) {
            "office rules ledger missing"
        }.use { it.readBytes().toString(Charsets.UTF_8) })
    }
}

/** Strict meta codec; absent key means no office tenure, malformed records fail closed. */
object OfficeTenureCodec {
    const val META_KEY = "localOfficeTenures"

    fun encode(tenures: Collection<OfficeTenure>): String {
        require(tenures.map { it.id }.distinct().size == tenures.size)
        val root = buildJsonObject {
            put("version", 1)
            put("tenures", buildJsonArray {
                tenures.sortedBy { it.id }.forEach { tenure ->
                    add(buildJsonObject {
                        put("id", tenure.id)
                        put("officeId", tenure.officeId)
                        put("jurisdictionId", tenure.jurisdictionId)
                        put("holderId", tenure.holderId)
                        put("issuerId", tenure.issuerId)
                        put("nationId", tenure.nationId)
                        put("origin", tenure.origin.name)
                        put("appointedTurn", tenure.appointedTurn)
                        tenure.acceptedTurn?.let { put("acceptedTurn", it) }
                        tenure.assumedTurn?.let { put("assumedTurn", it) }
                        tenure.seatCountyId?.let { put("seatCountyId", it) }
                        put("credentialIds", buildJsonArray { tenure.credentialIds.sorted().forEach { add(JsonPrimitive(it)) } })
                        tenure.endedTurn?.let { put("endedTurn", it) }
                    })
                }
            })
        }
        return root.toString()
    }

    fun decode(raw: String?): List<OfficeTenure> {
        if (raw == null) return emptyList()
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: invalid()
        require(root.keys == setOf("version", "tenures") && root["version"]?.jsonPrimitive?.int == 1)
        val rows = root["tenures"] as? JsonArray ?: invalid()
        val tenures = rows.map { element ->
            val row = element as? JsonObject ?: invalid()
            require(row.keys.containsAll(setOf("id", "officeId", "jurisdictionId", "holderId", "issuerId", "nationId", "origin", "appointedTurn", "credentialIds")))
            require(row.keys.all { it in setOf("id", "officeId", "jurisdictionId", "holderId", "issuerId", "nationId", "origin", "appointedTurn", "acceptedTurn", "assumedTurn", "seatCountyId", "credentialIds", "endedTurn") })
            OfficeTenure(
                id = row.requiredString("id"),
                officeId = row.requiredString("officeId"),
                jurisdictionId = row.requiredString("jurisdictionId"),
                holderId = row.requiredInt("holderId"),
                issuerId = row.requiredInt("issuerId"),
                nationId = row.requiredInt("nationId"),
                origin = OfficeClaimOrigin.valueOf(row.requiredString("origin")),
                appointedTurn = row.requiredLong("appointedTurn"),
                acceptedTurn = row.optionalLong("acceptedTurn"),
                assumedTurn = row.optionalLong("assumedTurn"),
                seatCountyId = row.optionalInt("seatCountyId"),
                credentialIds = (row["credentialIds"] as? JsonArray ?: invalid()).map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid() },
                endedTurn = row.optionalLong("endedTurn"),
            )
        }
        require(tenures.map { it.id }.distinct().size == tenures.size) { "duplicate office tenure id" }
        return tenures
    }

    private fun JsonObject.requiredString(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: invalid()
    private fun JsonObject.requiredInt(key: String) = (get(key) as? JsonPrimitive)?.int ?: invalid()
    private fun JsonObject.requiredLong(key: String) = (get(key) as? JsonPrimitive)?.long ?: invalid()
    private fun JsonObject.optionalInt(key: String) = get(key)?.let { (it as? JsonPrimitive)?.int ?: invalid() }
    private fun JsonObject.optionalLong(key: String) = get(key)?.let { (it as? JsonPrimitive)?.long ?: invalid() }
    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid office tenure meta")
}

/** 行 and 領 are individual credentials with their own lifetime, never merged into a title string. */
object OfficeCredentialCodec {
    const val META_KEY = "officeCredentials"

    fun encode(credentials: Collection<OfficeCredential>): String {
        require(credentials.map { it.id }.distinct().size == credentials.size)
        return buildJsonObject {
            put("version", 1)
            put("credentials", buildJsonArray {
                credentials.sortedBy { it.id }.forEach { credential ->
                    add(buildJsonObject {
                        put("id", credential.id)
                        put("kind", credential.kind.name)
                        put("issuerId", credential.issuerId)
                        put("effectiveFromTurn", credential.effectiveFromTurn)
                        credential.expiresAtTurn?.let { put("expiresAtTurn", it) }
                    })
                }
            })
        }.toString()
    }

    fun decode(raw: String?): List<OfficeCredential> {
        if (raw == null) return emptyList()
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: invalid()
        require(root.keys == setOf("version", "credentials") && root["version"]?.jsonPrimitive?.int == 1)
        val rows = root["credentials"] as? JsonArray ?: invalid()
        val credentials = rows.map { element ->
            val row = element as? JsonObject ?: invalid()
            require(row.keys.containsAll(setOf("id", "kind", "issuerId", "effectiveFromTurn")))
            require(row.keys.all { it in setOf("id", "kind", "issuerId", "effectiveFromTurn", "expiresAtTurn") })
            OfficeCredential(
                id = (row["id"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: invalid(),
                kind = OfficeCredentialKind.valueOf((row["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()),
                issuerId = row["issuerId"]?.jsonPrimitive?.int ?: invalid(),
                effectiveFromTurn = row["effectiveFromTurn"]?.jsonPrimitive?.long ?: invalid(),
                expiresAtTurn = row["expiresAtTurn"]?.jsonPrimitive?.long,
            )
        }
        require(credentials.map { it.id }.distinct().size == credentials.size)
        return credentials
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid office credential meta")
}

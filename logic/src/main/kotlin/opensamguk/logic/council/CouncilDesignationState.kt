package opensamguk.logic.council

import kotlinx.serialization.json.*

/** 지정 이력은 회수 후에도 보존한다. 군주 교체 정책은 이 저장 모델이 결정하지 않는다. */
data class CouncilDesignation(
    val id: String,
    val nationId: Int,
    val issuerGeneralId: Int,
    val issuerRevision: String,
    val targetGeneralId: Int,
    val revision: String,
    val revokedByRequestId: String? = null,
) {
    init {
        require(listOf(id, issuerRevision, revision).all(::validCouncilReceipt))
        require(nationId > 0 && issuerGeneralId > 0 && targetGeneralId > 0)
        require(revokedByRequestId == null || validCouncilReceipt(revokedByRequestId))
    }
}

data class CouncilDesignationState(val revision: String, val grants: List<CouncilDesignation>) {
    init {
        require(validCouncilReceipt(revision))
        require(grants.map { it.id }.distinct().size == grants.size)
        require(grants.filter { it.revokedByRequestId == null }.map { it.nationId to it.targetGeneralId }.distinct().size ==
            grants.count { it.revokedByRequestId == null })
    }
}

object CouncilDesignationCodec {
    const val META_KEY = "councilDesignations"

    fun encode(state: CouncilDesignationState): String = buildJsonObject {
        put("version", 1)
        put("revision", state.revision)
        put("grants", buildJsonArray { state.grants.sortedBy { it.id }.forEach { row ->
            add(buildJsonObject {
                put("id", row.id); put("nationId", row.nationId); put("issuerGeneralId", row.issuerGeneralId)
                put("issuerRevision", row.issuerRevision); put("targetGeneralId", row.targetGeneralId)
                put("revision", row.revision)
                row.revokedByRequestId?.let { put("revokedByRequestId", it) }
            })
        } })
    }.toString()

    /** 부재는 정상 빈 명부와 구분한다. 호출자가 실제 원문 존재성을 보존해야 한다. */
    fun decode(raw: String?): CouncilDesignationState? {
        if (raw == null) return null
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: invalid()
        require(root.keys == setOf("version", "revision", "grants") && root["version"]?.jsonPrimitive?.int == 1)
        val rows = root["grants"] as? JsonArray ?: invalid()
        return CouncilDesignationState(root.string("revision"), rows.map { value ->
            val row = value as? JsonObject ?: invalid()
            val required = setOf("id", "nationId", "issuerGeneralId", "issuerRevision", "targetGeneralId", "revision")
            require(row.keys.containsAll(required) && row.keys.all { it in required || it == "revokedByRequestId" })
            CouncilDesignation(row.string("id"), row.integer("nationId"), row.integer("issuerGeneralId"),
                row.string("issuerRevision"), row.integer("targetGeneralId"), row.string("revision"),
                row["revokedByRequestId"]?.let { (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid() })
        })
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    private fun JsonObject.integer(key: String): Int =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: invalid()
    private fun invalid(): Nothing = throw IllegalArgumentException("저장된 기밀실 지정 이력을 확인할 수 없습니다.")
}

private fun validCouncilReceipt(value: String): Boolean = value.matches(Regex("[A-Za-z0-9._:-]{1,128}"))

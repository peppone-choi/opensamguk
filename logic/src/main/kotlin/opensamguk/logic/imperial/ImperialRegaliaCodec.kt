package opensamguk.logic.imperial

/** Strict, versioned world_state.meta payload. */
object ImperialRegaliaCodec {
    const val META_KEY = "imperialRegalia"

    fun read(meta: Map<String, Any?>): ImperialRegaliaState? {
        if (META_KEY !in meta) return null
        val root = meta[META_KEY].record(setOf("schemaVersion", "artifacts"))
        require(root.integer("schemaVersion") == 1) { "unsupported regalia schema" }
        return ImperialRegaliaState(root.list("artifacts").map { it.readArtifact() })
    }

    fun write(state: ImperialRegaliaState): Map<String, Any> = linkedMapOf(
        "schemaVersion" to 1,
        "artifacts" to state.artifacts.sortedBy { it.id }.map { it.toRecord() },
    )

    private fun RegaliaArtifact.toRecord(): Map<String, Any?> = linkedMapOf(
        "id" to id, "kind" to kind.name, "claimedIdentity" to claimedIdentity,
        "ownerGeneralId" to ownerGeneralId, "ownerNationId" to ownerNationId,
        "custodianGeneralId" to custodianGeneralId, "cityId" to cityId,
        "authenticityClaims" to authenticityClaims.sortedBy { it.id }.map { it.toRecord() },
        "officeScope" to officeScope?.toRecord(),
        "custodyHistory" to custodyHistory.map { it.toRecord() },
    )

    private fun AuthenticityClaim.toRecord(): Map<String, Any> = linkedMapOf(
        "id" to id, "assertedIdentity" to assertedIdentity,
        "assertedByGeneralId" to assertedByGeneralId, "assessment" to assessment.name,
        "evidenceIds" to evidenceIds,
    )

    private fun OfficeInstrumentScope.toRecord(): Map<String, Any?> = linkedMapOf(
        "credentialId" to credentialId, "tenureId" to tenureId,
        "jurisdictionId" to jurisdictionId, "validFromTurn" to validFromTurn,
        "expiresAtTurn" to expiresAtTurn,
    )

    private fun RegaliaCustodyEvent.toRecord(): Map<String, Any?> = linkedMapOf(
        "requestId" to requestId, "type" to type.name, "actorGeneralId" to actorGeneralId,
        "fromCustodianGeneralId" to fromCustodianGeneralId, "toCustodianGeneralId" to toCustodianGeneralId,
        "fromCityId" to fromCityId, "toCityId" to toCityId, "turn" to turn,
    )

    private fun Any?.readArtifact(): RegaliaArtifact {
        val r = record(setOf("id", "kind", "claimedIdentity", "ownerGeneralId", "ownerNationId",
            "custodianGeneralId", "cityId", "authenticityClaims", "officeScope", "custodyHistory"))
        return RegaliaArtifact(
            r.text("id"), enumValueOf(r.text("kind")), r.text("claimedIdentity"),
            r.optionalInt("ownerGeneralId"), r.optionalInt("ownerNationId"),
            r.optionalInt("custodianGeneralId"), r.optionalInt("cityId"),
            r.list("authenticityClaims").map { it.readClaim() },
            r["officeScope"]?.readScope(),
            r.list("custodyHistory").map { it.readCustodyEvent() },
        )
    }

    private fun Any?.readClaim(): AuthenticityClaim {
        val r = record(setOf("id", "assertedIdentity", "assertedByGeneralId", "assessment", "evidenceIds"))
        return AuthenticityClaim(r.text("id"), r.text("assertedIdentity"), r.integer("assertedByGeneralId"),
            enumValueOf(r.text("assessment")), r.list("evidenceIds").map {
                it as? String ?: throw IllegalArgumentException("invalid regalia evidence id")
            })
    }

    private fun Any?.readScope(): OfficeInstrumentScope {
        val r = record(setOf("credentialId", "tenureId", "jurisdictionId", "validFromTurn", "expiresAtTurn"))
        return OfficeInstrumentScope(r.text("credentialId"), r.text("tenureId"), r.text("jurisdictionId"),
            r.long("validFromTurn"), r.optionalLong("expiresAtTurn"))
    }

    private fun Any?.readCustodyEvent(): RegaliaCustodyEvent {
        val r = record(setOf("requestId", "type", "actorGeneralId", "fromCustodianGeneralId",
            "toCustodianGeneralId", "fromCityId", "toCityId", "turn"))
        return RegaliaCustodyEvent(
            r.text("requestId"), enumValueOf(r.text("type")), r.integer("actorGeneralId"),
            r.optionalInt("fromCustodianGeneralId"), r.optionalInt("toCustodianGeneralId"),
            r.optionalInt("fromCityId"), r.optionalInt("toCityId"), r.long("turn"),
        )
    }

    private fun Any?.record(fields: Set<String>): Map<*, *> {
        val value = this as? Map<*, *> ?: throw IllegalArgumentException("invalid regalia record")
        require(value.keys == fields) { "invalid regalia fields" }
        return value
    }

    private fun Map<*, *>.text(key: String): String =
        (this[key] as? String)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("invalid regalia $key")
    private fun Map<*, *>.integer(key: String): Int =
        this[key] as? Int ?: throw IllegalArgumentException("invalid regalia $key")
    private fun Map<*, *>.optionalInt(key: String): Int? =
        this[key]?.let { it as? Int ?: throw IllegalArgumentException("invalid regalia $key") }
    private fun Map<*, *>.long(key: String): Long = when (val value = this[key]) {
        is Long -> value
        is Int -> value.toLong()
        else -> throw IllegalArgumentException("invalid regalia $key")
    }
    private fun Map<*, *>.optionalLong(key: String): Long? =
        this[key]?.let { when (it) {
            is Long -> it
            is Int -> it.toLong()
            else -> throw IllegalArgumentException("invalid regalia $key")
        } }
    private fun Map<*, *>.list(key: String): List<*> =
        this[key] as? List<*> ?: throw IllegalArgumentException("invalid regalia $key")
}

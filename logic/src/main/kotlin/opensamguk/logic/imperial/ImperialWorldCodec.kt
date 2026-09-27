package opensamguk.logic.imperial

/**
 * Versioned world_state.meta payload. Missing means no imperial state was seeded;
 * malformed data never silently becomes an empty court.
 */
object ImperialWorldCodec {
    const val META_KEY = "imperialWorld"

    fun read(meta: Map<String, Any?>): ImperialWorldState? {
        if (META_KEY !in meta) return null
        val root = meta[META_KEY].record(setOf("schemaVersion", "houses", "allegiances", "transitions"))
        require(root.int("schemaVersion") == 1) { "unsupported imperial schema" }
        return ImperialWorldState(
            houses = root.list("houses").map { it.readHouse() },
            allegiances = root.list("allegiances").map { it.readAllegiance() },
            transitions = root.list("transitions").map { it.readTransition() },
        )
    }

    fun write(state: ImperialWorldState): Map<String, Any> = linkedMapOf(
        "schemaVersion" to 1,
        "houses" to state.houses.sortedBy { it.code }.map { it.toRecord() },
        "allegiances" to state.allegiances.sortedWith(compareBy({ it.lineCode }, { it.nationId })).map { it.toRecord() },
        "transitions" to state.transitions.map { it.toRecord() },
    )

    private fun ImperialHouse.toRecord(): Map<String, Any?> = linkedMapOf(
        "code" to code, "name" to name, "status" to status.name,
        "holderGeneralId" to holderGeneralId, "designatedHeirGeneralId" to designatedHeirGeneralId,
        "dynasticCandidateIds" to dynasticCandidateIds, "regentGeneralId" to regentGeneralId,
        "courtNationId" to courtNationId, "courtCityId" to courtCityId, "legitimacy" to legitimacy,
    )

    private fun ImperialAllegiance.toRecord(): Map<String, Any> = linkedMapOf(
        "lineCode" to lineCode, "nationId" to nationId, "relation" to relation.name,
        "recognition" to recognition.name, "favor" to favor,
    )

    private fun ImperialTransition.toRecord(): Map<String, Any?> = linkedMapOf(
        "requestId" to requestId, "lineCode" to lineCode, "type" to type.name,
        "fromHolderGeneralId" to fromHolderGeneralId, "toHolderGeneralId" to toHolderGeneralId,
        "actorGeneralId" to actorGeneralId, "year" to year, "month" to month,
        "reasonCode" to reasonCode, "successionSource" to successionSource?.name,
    )

    private fun Any?.readHouse(): ImperialHouse {
        val r = record(setOf("code", "name", "status", "holderGeneralId", "designatedHeirGeneralId",
            "dynasticCandidateIds", "regentGeneralId", "courtNationId", "courtCityId", "legitimacy"))
        return ImperialHouse(
            r.string("code"), r.string("name"), enumValueOf(r.string("status")),
            r.optionalInt("holderGeneralId"), r.optionalInt("designatedHeirGeneralId"),
            r.list("dynasticCandidateIds").map { it.integer() },
            r.optionalInt("regentGeneralId"), r.optionalInt("courtNationId"), r.optionalInt("courtCityId"),
            r.int("legitimacy"),
        )
    }

    private fun Any?.readAllegiance(): ImperialAllegiance {
        val r = record(setOf("lineCode", "nationId", "relation", "recognition", "favor"))
        return ImperialAllegiance(r.string("lineCode"), r.int("nationId"),
            enumValueOf(r.string("relation")), enumValueOf(r.string("recognition")), r.int("favor"))
    }

    private fun Any?.readTransition(): ImperialTransition {
        val r = record(setOf("requestId", "lineCode", "type", "fromHolderGeneralId",
            "toHolderGeneralId", "actorGeneralId", "year", "month", "reasonCode", "successionSource"))
        return ImperialTransition(
            r.string("requestId"), r.string("lineCode"), enumValueOf(r.string("type")),
            r.optionalInt("fromHolderGeneralId"), r.optionalInt("toHolderGeneralId"), r.optionalInt("actorGeneralId"),
            r.int("year"), r.int("month"), r.string("reasonCode"),
            r["successionSource"]?.let { enumValueOf<ImperialSuccessionSource>(it as? String
                ?: throw IllegalArgumentException("invalid imperial successionSource")) },
        )
    }

    private fun Any?.record(fields: Set<String>): Map<*, *> {
        val record = this as? Map<*, *> ?: throw IllegalArgumentException("invalid imperial record")
        require(record.keys == fields) { "invalid imperial fields" }
        return record
    }

    private fun Map<*, *>.string(key: String): String =
        (this[key] as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("invalid imperial $key")

    private fun Any?.integer(): Int = this as? Int ?: throw IllegalArgumentException("invalid imperial integer")
    private fun Map<*, *>.int(key: String): Int = this[key].integer()
    private fun Map<*, *>.optionalInt(key: String): Int? =
        this[key]?.integer()
    private fun Map<*, *>.list(key: String): List<*> =
        this[key] as? List<*> ?: throw IllegalArgumentException("invalid imperial $key")
}

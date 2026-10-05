package opensamguk.infra.seed

import opensamguk.logic.imperial.ImperialAllegiance
import opensamguk.logic.imperial.ImperialWorldCodec

/** Reads an explicit scenario declaration; omission never manufactures a seed. */
object ScenarioImperialSeedCodec {
    fun read(root: Map<String, Any?>): ScenarioImperialSeed? {
        if (ImperialWorldCodec.META_KEY !in root) return null
        val seed = root[ImperialWorldCodec.META_KEY].record(setOf("schemaVersion", "houses", "allegiances"))
        require(seed.integer("schemaVersion") == 1) { "unsupported scenario imperial schema" }
        return ScenarioImperialSeed(
            houses = seed.list("houses").map { raw ->
                val house = raw.record(setOf("code", "name", "status", "holderName", "designatedHeirName",
                    "dynasticCandidateNames", "regentName", "courtNationId", "courtCityId", "legitimacy"))
                ScenarioImperialHouse(house.string("code"), house.string("name"), enumValueOf(house.string("status")),
                    house.optionalName("holderName"), house.optionalName("designatedHeirName"),
                    house.list("dynasticCandidateNames").map { it.name() }, house.optionalName("regentName"),
                    house.optionalInteger("courtNationId"), house.optionalInteger("courtCityId"),
                    house.integer("legitimacy"))
            },
            allegiances = seed.list("allegiances").map { raw ->
                val allegiance = raw.record(setOf("lineCode", "nationId", "relation", "recognition", "favor"))
                ImperialAllegiance(allegiance.string("lineCode"), allegiance.integer("nationId"),
                    enumValueOf(allegiance.string("relation")), enumValueOf(allegiance.string("recognition")),
                    allegiance.integer("favor"))
            },
        )
    }

    private fun Any?.record(fields: Set<String>): Map<*, *> {
        val record = this as? Map<*, *> ?: throw IllegalArgumentException("invalid scenario imperial record")
        require(record.keys == fields) { "invalid scenario imperial fields" }
        return record
    }

    private fun Any?.name(): String =
        (this as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("invalid scenario imperial name")
    private fun Any?.integer(): Int = this as? Int
        ?: throw IllegalArgumentException("invalid scenario imperial integer")
    private fun Map<*, *>.string(key: String): String = this[key].name()
    private fun Map<*, *>.optionalName(key: String): String? = this[key]?.name()
    private fun Map<*, *>.integer(key: String): Int = this[key].integer()
    private fun Map<*, *>.optionalInteger(key: String): Int? = this[key]?.integer()
    private fun Map<*, *>.list(key: String): List<*> = this[key] as? List<*>
        ?: throw IllegalArgumentException("invalid scenario imperial $key")
}

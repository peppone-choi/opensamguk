package opensamguk.logic.creation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean

data class CreationStatRule(val minimum: Int, val maximum: Int, val exactTotal: Int)
data class CreationSelection(val id: String, val displayNameKo: String)
data class CreationModePolicy(val kind: CreationKind, val allowed: Boolean)
enum class CreationKind { CUSTOM, HISTORICAL }
enum class CreationEntryRole { RETAINER, PRE_LORD }

/** The executable 6+6 selection list. The RTK14 research catalogue is not a runtime policy. */
data class CreationSelectionPolicy(
    val statRule: CreationStatRule,
    val ideologies: List<CreationSelection>,
    val traits: List<CreationSelection>,
    val modes: List<CreationModePolicy>,
) {
    init {
        require(statRule == CreationStatRule(20, 85, 300))
        require(ideologies.map { it.id }.toSet() == IDEOLOGY_IDS && ideologies.size == IDEOLOGY_IDS.size)
        require(traits.map { it.id }.toSet() == TRAIT_IDS && traits.size == TRAIT_IDS.size)
        require((ideologies + traits).all { it.displayNameKo.isNotBlank() })
        require(modes.map { it.kind }.toSet() == CreationKind.entries.toSet() &&
            modes.size == CreationKind.entries.size)
    }

    companion object {
        private val IDEOLOGY_IDS = setOf("WANGDO", "PAEDO", "ADO", "HALGEO", "MYEONGRI", "YEGYO")
        private val TRAIT_IDS = setOf("DISCIPLINE", "WATER_COMBAT", "RENOWN", "DEBATER", "STRATEGIST", "SINGLE_RIDER")
        private const val RESOURCE = "campaign/general-creation-selection-v1.json"

        /** Callers treat null or parse failure as CREATION_POLICY_UNAVAILABLE; never widen choices. */
        fun load(): CreationSelectionPolicy? =
            CreationSelectionPolicy::class.java.classLoader.getResource(RESOURCE)?.readText()?.let(::parse)

        fun parse(payload: String): CreationSelectionPolicy {
            val root = Json.parseToJsonElement(payload).jsonObject
            require(root.getValue("schemaVersion").jsonPrimitive.content == "1")
            require(root.getValue("policyId").jsonPrimitive.content == "general-creation-selection-v1")
            require(root.getValue("effectState").jsonPrimitive.content == "DISPLAY_ONLY")
            require(root.getValue("ruleProfile").jsonPrimitive.content == "HWIHA")
            require(root.getValue("requiredServerStatus").jsonPrimitive.content == "OPEN")
            require(root.getValue("requiredSeasonState").jsonPrimitive.content == "RUNNING")
            val stat = root.getValue("statRule").jsonObject
            fun selections(key: String) = root.getValue(key).jsonArray.map { node ->
                val row = node.jsonObject
                CreationSelection(row.getValue("id").jsonPrimitive.content,
                    row.getValue("displayNameKo").jsonPrimitive.content)
            }
            return CreationSelectionPolicy(
                CreationStatRule(stat.getValue("minimum").jsonPrimitive.content.toInt(),
                    stat.getValue("maximum").jsonPrimitive.content.toInt(),
                    stat.getValue("exactTotal").jsonPrimitive.content.toInt()),
                selections("ideologies"), selections("traits"),
                root.getValue("modes").jsonArray.map { node ->
                    val row = node.jsonObject
                    CreationModePolicy(CreationKind.valueOf(row.getValue("kind").jsonPrimitive.content),
                        row.getValue("allowed").jsonPrimitive.boolean)
                },
            )
        }
    }
}

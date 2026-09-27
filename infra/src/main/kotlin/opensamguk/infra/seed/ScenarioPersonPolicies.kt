package opensamguk.infra.seed

import opensamguk.logic.input.PersonPolicyState
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.renown.RenownRules

/** Explicit, source-bound person policies for synthetic QA and the reviewed 190 pilot. */
internal object ScenarioPersonPolicies {
    private const val RTK14_190_SOURCE = "rtk14-wikiwiki:190.1"
    private const val RTK14_190_REVISION = "sha256:5f511438e36bd5b673370928365c8cef78d464a7683ec78105280d310e4a68fd"
    private val statKeys = listOf("leadership", "strength", "intelligence", "politics", "charm")
    private val tupleIndices = listOf(5, 6, 7, 14, 15)
    private val fields = setOf("name", "statSourceId", "statSourceRevision", "officerId", "acceptsEnlistment", "stats")

    data class Declaration(val state: PersonPolicyState, val stats: List<Int>) {
        fun bind(general: ScenarioGeneral): PersonPolicyState {
            require(stats == explicitStats(general)) { "Declared five stats disagree with scenario person ${general.name}" }
            require(general.officerNumber == null || general.officerNumber == state.officerId) { "Scenario officer identity mismatch" }
            return state
        }
    }

    fun decode(root: Map<String, Any?>, profile: RuleProfile?): Map<String, Declaration> {
        if ("personPolicies" !in root) return emptyMap()
        require(profile == RuleProfile.HWIHA) { "personPolicies requires HWIHA" }
        val entries = root["personPolicies"] as? List<*>
            ?: throw IllegalArgumentException("personPolicies must be an array")
        val result = linkedMapOf<String, Declaration>()
        val identities = mutableSetOf<Triple<String, String, Int>>()
        for (raw in entries) {
            val row = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid person policy declaration")
            require(row.keys == fields) { "Unexpected person policy fields" }
            fun text(key: String): String = (row[key] as? String)?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Missing person policy $key")
            val name = text("name")
            val state = PersonPolicyState(RenownRules.INITIAL_CAPACITY,
                row["acceptsEnlistment"] as? Boolean ?: throw IllegalArgumentException("Explicit acceptance required"),
                text("statSourceId"), text("statSourceRevision"), nonnegative(row["officerId"]))
            requireApprovedSource(state)
            require(identities.add(Triple(state.statSourceId, state.statSourceRevision, state.officerId))) { "Duplicate person source identity" }
            val stats = row["stats"] as? Map<*, *> ?: throw IllegalArgumentException("Explicit five stats required")
            require(stats.keys == statKeys.toSet()) { "Exactly five stats required" }
            require(result.put(name, Declaration(state, statKeys.map { nonnegative(stats[it]) })) == null) { "Duplicate person policy name" }
        }
        return result
    }

    /** Also checks manually constructed Scenario objects before the importer writes anything. */
    fun validate(general: ScenarioGeneral) {
        val state = general.personPolicy ?: return
        requireApprovedSource(state)
        require(state.renownCapacity == RenownRules.INITIAL_CAPACITY) { "Seed capacity must use the new-person policy" }
        explicitStats(general)
        require(general.officerNumber == null || general.officerNumber == state.officerId) { "Scenario officer identity mismatch" }
        if (state.statSourceId == RTK14_190_SOURCE) {
            require(general.picture?.toIntOrNull() == state.officerId) {
                "Historical person policy must match its stable officer picture id"
            }
        }
    }

    private fun explicitStats(general: ScenarioGeneral): List<Int> {
        val raw = tupleIndices.map { nonnegative(general.rawTuple.getOrNull(it)) }
        require(raw == listOf(general.leadership, general.strength, general.intel, general.politics, general.charm)) {
            "Scenario defaults or changed stats cannot supply person policy"
        }
        return raw
    }
    private fun requireApprovedSource(state: PersonPolicyState) {
        val synthetic = state.statSourceId.startsWith("synthetic-qa:") &&
            state.statSourceId.length > "synthetic-qa:".length
        val historical = state.statSourceId == RTK14_190_SOURCE &&
            state.statSourceRevision == RTK14_190_REVISION && state.officerId in 10001..11000
        require(synthetic || historical) { "Person policy requires an approved source and revision" }
    }
    private fun nonnegative(value: Any?): Int = (value as? Int)?.takeIf { it >= 0 }
        ?: throw IllegalArgumentException("Explicit nonnegative integer required")
}

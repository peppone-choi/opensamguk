package opensamguk.infra.seed

import opensamguk.logic.input.HwihaPersonPolicyState
import opensamguk.logic.input.HwihaRenownRules
import opensamguk.logic.input.RuleProfile

/** Explicit synthetic QA inputs. This is not an authority for unverified historical datasets. */
internal object HwihaScenarioPersonPolicies {
    private val statKeys = listOf("leadership", "strength", "intelligence", "politics", "charm")
    private val tupleIndices = listOf(5, 6, 7, 14, 15)
    private val fields = setOf("name", "statSourceId", "statSourceRevision", "officerId", "acceptsEnlistment", "stats")

    data class Declaration(val state: HwihaPersonPolicyState, val stats: List<Int>) {
        fun bind(general: ScenarioGeneral): HwihaPersonPolicyState {
            require(stats == explicitStats(general)) { "Declared five stats disagree with scenario person ${general.name}" }
            require(general.officerNumber == null || general.officerNumber == state.officerId) { "Scenario officer identity mismatch" }
            return state
        }
    }

    fun decode(root: Map<String, Any?>, profile: RuleProfile?): Map<String, Declaration> {
        if ("hwihaPersonPolicies" !in root) return emptyMap()
        require(profile == RuleProfile.HWIHA) { "hwihaPersonPolicies requires HWIHA" }
        val entries = root["hwihaPersonPolicies"] as? List<*>
            ?: throw IllegalArgumentException("hwihaPersonPolicies must be an array")
        val result = linkedMapOf<String, Declaration>()
        val identities = mutableSetOf<Triple<String, String, Int>>()
        for (raw in entries) {
            val row = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid person policy declaration")
            require(row.keys == fields) { "Unexpected person policy fields" }
            fun text(key: String): String = (row[key] as? String)?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Missing person policy $key")
            val name = text("name")
            val state = HwihaPersonPolicyState(HwihaRenownRules.INITIAL_CAPACITY,
                row["acceptsEnlistment"] as? Boolean ?: throw IllegalArgumentException("Explicit acceptance required"),
                text("statSourceId"), text("statSourceRevision"), nonnegative(row["officerId"]))
            requireSynthetic(state)
            require(identities.add(Triple(state.statSourceId, state.statSourceRevision, state.officerId))) { "Duplicate person source identity" }
            val stats = row["stats"] as? Map<*, *> ?: throw IllegalArgumentException("Explicit five stats required")
            require(stats.keys == statKeys.toSet()) { "Exactly five stats required" }
            require(result.put(name, Declaration(state, statKeys.map { nonnegative(stats[it]) })) == null) { "Duplicate person policy name" }
        }
        return result
    }

    /** Also checks manually constructed Scenario objects before the importer writes anything. */
    fun validate(general: ScenarioGeneral) {
        val state = general.hwihaPersonPolicy ?: return
        requireSynthetic(state)
        require(state.renownCapacity == HwihaRenownRules.INITIAL_CAPACITY) { "Seed capacity must use the new-person policy" }
        explicitStats(general)
        require(general.officerNumber == null || general.officerNumber == state.officerId) { "Scenario officer identity mismatch" }
    }

    private fun explicitStats(general: ScenarioGeneral): List<Int> {
        val raw = tupleIndices.map { nonnegative(general.rawTuple.getOrNull(it)) }
        require(raw == listOf(general.leadership, general.strength, general.intel, general.politics, general.charm)) {
            "Scenario defaults or changed stats cannot supply person policy"
        }
        return raw
    }
    private fun requireSynthetic(state: HwihaPersonPolicyState) {
        require(state.statSourceId.startsWith("synthetic-qa:") && state.statSourceId.length > "synthetic-qa:".length) {
            "Historical person policy requires a separately verified dataset; only synthetic-qa sources are accepted here"
        }
    }
    private fun nonnegative(value: Any?): Int = (value as? Int)?.takeIf { it >= 0 }
        ?: throw IllegalArgumentException("Explicit nonnegative integer required")
}

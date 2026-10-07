package opensamguk.infra.seed

import opensamguk.common.constants.GameConst
import opensamguk.logic.input.PersonPolicyState
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.renown.RenownAssessment
import opensamguk.logic.renown.RenownRules

/** Explicit, source-bound person policies for synthetic QA and the RTK14 190 roster. */
internal object ScenarioPersonPolicies {
    private const val RTK14_190_SOURCE = "rtk14-workbook:190.1"
    private const val RTK14_190_REVISION = "sha256:bb8f6db3b5afe732cb5d019cd16e15b92dc1296530ab265f1f7577a04de34e7f"
    private val statKeys = listOf("leadership", "strength", "intelligence", "politics", "charm")
    private val tupleIndices = listOf(5, 6, 7, 14, 15)
    private val fields = setOf("name", "statSourceId", "statSourceRevision", "officerId", "acceptsEnlistment", "stats")

    data class Declaration(val state: PersonPolicyState, val stats: List<Int>) {
        fun bind(general: ScenarioGeneral): PersonPolicyState {
            require(stats == explicitStats(general)) { "Declared five stats disagree with scenario person ${general.name}" }
            require(matchesOfficerNumber(general, state)) { "Scenario officer identity mismatch" }
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
        require(state.renownCapacity in RenownRules.INITIAL_CAPACITY..RenownAssessment.CANON.ceiling) {
            "Seed capacity must fit the reviewed monthly assessment curve"
        }
        if (general.lord != true) {
            require(state.renownCapacity == RenownRules.INITIAL_CAPACITY) {
                "Non-ruler seed capacity must use the new-person policy"
            }
        }
        explicitStats(general)
        require(matchesOfficerNumber(general, state)) { "Scenario officer identity mismatch" }
        if (state.statSourceId == RTK14_190_SOURCE) {
            require(scenarioOfficerId(general.picture) == state.officerId) {
                "Historical person policy must match its stable officer picture id"
            }
        }
    }

    /** Reserve the complete starting retinue plus one new-person allowance for each ruler. */
    fun startingRulerCapacities(
        roster: List<ScenarioGeneral>,
        retainers: List<ScenarioRetainer>,
        startYear: Int,
    ): Map<String, Int> {
        val active = roster.filter { general ->
            val death = general.deadYear ?: 300
            val appearance = general.appearanceYear
            if (appearance != null) appearance <= startYear && startYear <= death
            else death > startYear && (general.bornYear ?: 180) + GameConst.adultAge.toInt() <= startYear
        }.associateBy { it.name }
        val byMaster = retainers.filter { it.general in active }.groupBy { it.master }
        return roster.filter { it.lord == true && it.personPolicy != null }.associate { lord ->
            val cost = byMaster[lord.name].orEmpty().sumOf { relation ->
                val subject = active.getValue(relation.general)
                // Cost is a five-stat rule. A legacy three-stat tuple cannot silently borrow the
                // parser's politics/charm defaults and be labelled a reviewed seed budget.
                require(listOf(5, 6, 7, 14, 15).all { subject.rawTuple.getOrNull(it) is Int }) {
                    "Starting retainer ${subject.name} needs five source-validated stats"
                }
                RenownRules.personCost(subject.leadership, subject.strength, subject.intel,
                    subject.politics, subject.charm).toLong()
            }
            val required = cost + RenownRules.INITIAL_CAPACITY
            require(required <= RenownAssessment.CANON.ceiling) {
                "Starting retinue for ${lord.name} requires capacity $required above the reviewed ceiling"
            }
            lord.name to required.toInt()
        }
    }

    private fun explicitStats(general: ScenarioGeneral): List<Int> {
        val raw = tupleIndices.map { nonnegative(general.rawTuple.getOrNull(it)) }
        require(raw == listOf(general.leadership, general.strength, general.intel, general.politics, general.charm)) {
            "Scenario defaults or changed stats cannot supply person policy"
        }
        return raw
    }
    private fun matchesOfficerNumber(general: ScenarioGeneral, state: PersonPolicyState): Boolean {
        val number = general.officerNumber ?: return true
        return if (state.statSourceId == RTK14_190_SOURCE) {
            // Workbook officer numbers and portrait stable IDs are independent
            // namespaces. The picture-to-policy check in validate() binds identity.
            number in 1..1000
        } else number == state.officerId
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

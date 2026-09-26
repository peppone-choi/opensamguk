package opensamguk.infra.seed

import opensamguk.logic.content.PersonBondKind
import opensamguk.logic.input.RuleProfile

/** A source-backed directed bond; the target officer ID is resolved to a world general ID at seed time. */
data class ScenarioPersonBond(val kind: PersonBondKind, val targetOfficerId: Int, val evidenceIds: Set<String>)

object ScenarioPersonBonds {
    private val rowFields = setOf("name", "kind", "targetOfficerId", "evidenceIds")

    fun decode(root: Map<String, Any?>, profile: RuleProfile, roster: List<ScenarioGeneral>): Map<String, List<ScenarioPersonBond>> {
        if ("personBonds" !in root) return emptyMap()
        require(profile == RuleProfile.HWIHA) { "personBonds requires HWIHA" }
        val rows = root["personBonds"] as? List<*> ?: invalid()
        val officers = roster.mapNotNull { it.picture?.toIntOrNull()?.let { id -> id to it } }.toMap()
        require(officers.size == roster.size) { "personBonds requires stable officer IDs on every roster member" }
        val names = roster.map { it.name }.toSet()
        val grouped = linkedMapOf<String, MutableList<ScenarioPersonBond>>()
        for (raw in rows) {
            val row = raw as? Map<*, *> ?: invalid()
            require(row.keys == rowFields) { "Unexpected personBonds fields" }
            val name = row["name"] as? String ?: invalid()
            require(name in names) { "personBonds owner is absent: $name" }
            val kind = (row["kind"] as? String)?.let { value ->
                PersonBondKind.entries.firstOrNull { it.name == value }
            } ?: invalid()
            val target = (row["targetOfficerId"] as? Int)?.takeIf { it in officers } ?: invalid()
            require(officers.getValue(target).name != name) { "personBonds cannot target self" }
            val evidence = (row["evidenceIds"] as? List<*>)?.map { it as? String ?: invalid() } ?: invalid()
            require(evidence.isNotEmpty() && evidence.distinct().size == evidence.size &&
                evidence.all { it.matches(Regex("(history|novel):[^:]+:[^:]+")) }) {
                "personBonds requires distinct book-and-volume evidence"
            }
            val bond = ScenarioPersonBond(kind, target, evidence.toSet())
            val owner = grouped.getOrPut(name) { mutableListOf() }
            require(owner.none { it.kind == kind && it.targetOfficerId == target }) { "Duplicate person bond" }
            owner += bond
        }
        return grouped
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid personBonds declaration")
}

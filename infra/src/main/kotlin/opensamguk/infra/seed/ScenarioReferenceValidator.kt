package opensamguk.infra.seed

/** Validate explicit references before importing; omitted locations retain their RNG semantics. */
object ScenarioReferenceValidator {
    fun validate(scenario: Scenario, cities: List<ScenarioCity>) {
        val cityIds = cities.mapTo(hashSetOf()) { it.id }
        val cityIdByName = cities.associate { it.name to it.id }
        // Match the importer's numeric-ID-first resolution, including logical map aliases.
        fun resolveCity(ref: String): Int? =
            ref.toIntOrNull()?.takeIf { it in cityIds } ?: cityIdByName[ref]

        val owners = mutableMapOf<Int, String>()
        for (nation in scenario.nations) {
            for (ref in nation.cities) {
                val cityId = requireNotNull(resolveCity(ref)) {
                    "scenario nation ${nation.name} references unknown city: $ref"
                }
                val previous = owners.putIfAbsent(cityId, nation.name)
                require(previous == null) {
                    "scenario city $cityId has duplicate ownership declarations: $previous and ${nation.name}"
                }
            }
        }
        // Import selection uses the separate rosters; validate those even for constructed Scenarios.
        val roster = (scenario.generals + scenario.baseGenerals + scenario.generalEx + scenario.generalNeutral).distinct()
        for (general in roster) {
            val ref = general.locatedCity ?: continue
            require(resolveCity(ref) != null) {
                "scenario general ${general.name} references unknown locatedCity: $ref"
            }
        }

        val nationIds = scenario.nations.mapTo(hashSetOf()) { it.id }
        for (relation in scenario.diplomacy) {
            require(relation.me in nationIds && relation.you in nationIds && relation.me != relation.you) {
                "scenario diplomacy references nonexistent ordered pair: ${relation.me} -> ${relation.you}"
            }
        }
    }
}

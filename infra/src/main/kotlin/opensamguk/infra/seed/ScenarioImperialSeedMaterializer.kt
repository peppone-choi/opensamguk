package opensamguk.infra.seed

import opensamguk.logic.imperial.ImperialWorldCodec

/** Pure payload preparation. The importer owns actual row creation and persistence. */
object ScenarioImperialSeedMaterializer {
    fun materialize(
        seed: ScenarioImperialSeed?,
        activeGeneralIdsByName: Collection<Pair<String, Int>>,
        seededNationIds: Set<Int>,
        seededCityIds: Set<Int>,
    ): Map<String, Any>? {
        if (seed == null) return null
        require(seededNationIds.all { it > 0 } && seededCityIds.all { it > 0 })
        require(activeGeneralIdsByName.map { it.second }.distinct().size == activeGeneralIdsByName.size) {
            "one seeded general ID cannot identify multiple roster rows"
        }
        val state = ScenarioImperialSeedResolver.resolve(seed, activeGeneralIdsByName)
        require(state.houses.all { house ->
            (house.courtNationId == null || house.courtNationId in seededNationIds) &&
                (house.courtCityId == null || house.courtCityId in seededCityIds)
        }) { "imperial court references must identify actual seeded rows in the target world" }
        require(state.allegiances.all { it.nationId in seededNationIds }) {
            "imperial allegiance must identify an actual seeded nation in the target world"
        }
        return ImperialWorldCodec.write(state)
    }
}

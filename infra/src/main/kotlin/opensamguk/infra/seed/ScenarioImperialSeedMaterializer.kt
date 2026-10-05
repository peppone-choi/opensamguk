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
        require(seededNationIds.all { it > 0 } && seededCityIds.all { it > 0 })
        return seed?.let { ImperialWorldCodec.write(ScenarioImperialSeedResolver.resolve(it, activeGeneralIdsByName)) }
    }
}

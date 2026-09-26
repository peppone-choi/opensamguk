package opensamguk.infra.seed

import opensamguk.logic.imperial.ImperialAllegiance
import opensamguk.logic.imperial.ImperialHouse
import opensamguk.logic.imperial.ImperialLineStatus
import opensamguk.logic.imperial.ImperialWorldState

/** Scenario names are resolved only after the active general roster receives stable world IDs. */
data class ScenarioImperialHouse(
    val code: String,
    val name: String,
    val status: ImperialLineStatus,
    val holderName: String?,
    val designatedHeirName: String?,
    val dynasticCandidateNames: List<String>,
    val regentName: String?,
    val courtNationId: Int?,
    val courtCityId: Int?,
    val legitimacy: Int,
) {
    init {
        require(holderName == null || holderName.isNotBlank())
        require(designatedHeirName == null || designatedHeirName.isNotBlank())
        require(regentName == null || regentName.isNotBlank())
        require(dynasticCandidateNames.all(String::isNotBlank))
        require(dynasticCandidateNames.distinct().size == dynasticCandidateNames.size)
    }
}

data class ScenarioImperialSeed(
    val houses: List<ScenarioImperialHouse>,
    val allegiances: List<ImperialAllegiance>,
)

object ScenarioImperialSeedResolver {
    fun resolve(
        seed: ScenarioImperialSeed,
        activeGeneralIdsByName: Collection<Pair<String, Int>>,
    ): ImperialWorldState {
        require(activeGeneralIdsByName.all { (name, id) -> name.isNotBlank() && id > 0 })
        val idsByName = activeGeneralIdsByName.groupBy({ it.first }, { it.second })
        fun id(name: String?): Int? {
            if (name == null) return null
            return idsByName[name]?.singleOrNull()
                ?: throw IllegalArgumentException("imperial person must identify one active seeded general: $name")
        }
        return ImperialWorldState(
            houses = seed.houses.map { house ->
                ImperialHouse(house.code, house.name, house.status, id(house.holderName),
                    id(house.designatedHeirName), house.dynasticCandidateNames.map { name ->
                        requireNotNull(id(name))
                    }, id(house.regentName), house.courtNationId, house.courtCityId,
                    house.legitimacy)
            },
            allegiances = seed.allegiances,
            transitions = emptyList(),
        )
    }
}

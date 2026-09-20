package opensamguk.logic.war.hwiha

import java.util.Collections

/** HWIHA game-design values; not inherited SAMMO combat coefficients. */
data class HwihaUnitProfile(val crewTypeId: Int, val movementSteps: Int, val attackRange: Int,
    val attackPower: Int, val defencePower: Int, val initiative: Int) {
    init {
        require(crewTypeId > 0 && movementSteps > 0 && attackRange > 0)
        require(attackPower > 0 && defencePower > 0 && initiative >= 0)
    }
}

class HwihaUnitProfiles(val version: Int, val contentHash: String, profiles: List<HwihaUnitProfile>,
    unsupportedCrewTypeIds: Set<Int>) {
    val profiles: List<HwihaUnitProfile> = Collections.unmodifiableList(profiles.sortedBy { it.crewTypeId })
    val unsupportedCrewTypeIds: Set<Int> = Collections.unmodifiableSet(java.util.TreeSet(unsupportedCrewTypeIds))
    private val byId = this.profiles.associateBy { it.crewTypeId }
    init {
        require(version == 1 && contentHash.matches(Regex("[0-9a-f]{64}")))
        require(this.profiles.isNotEmpty() && byId.size == this.profiles.size)
        require(this.unsupportedCrewTypeIds.all { it > 0 && it !in byId })
    }
    fun find(id: Int): HwihaUnitProfile? = byId[id]
}

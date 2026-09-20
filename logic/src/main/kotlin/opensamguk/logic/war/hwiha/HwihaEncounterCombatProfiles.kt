package opensamguk.logic.war.hwiha

import java.util.Collections
import opensamguk.logic.input.HwihaEncounterForces

/** Pinned combat definitions for the frozen forces, never a mutable live unit ledger. */
class HwihaEncounterCombatProfiles private constructor(
    val encounterId: String,
    val forcesSnapshotId: String,
    val rulesVersion: Int,
    val rulesContentHash: String,
    profiles: List<HwihaUnitProfile>,
    unavailable: List<UnavailableType>,
) {
    enum class Reason { UNKNOWN_CREW_TYPE, UNSUPPORTED_CREW_TYPE }
    data class UnavailableType(val crewTypeId: Int, val reason: Reason)
    val profiles: List<HwihaUnitProfile> = Collections.unmodifiableList(ArrayList(profiles))
    val unavailable: List<UnavailableType> = Collections.unmodifiableList(ArrayList(unavailable))
    val ready: Boolean get() = unavailable.isEmpty()

    fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "version" to 1,
        "encounterId" to encounterId,
        "forcesSnapshotId" to forcesSnapshotId,
        "rulesVersion" to rulesVersion,
        "rulesContentHash" to rulesContentHash,
        "status" to if (ready) "READY" else "UNAVAILABLE",
        "profiles" to profiles.map { linkedMapOf(
            "crewTypeId" to it.crewTypeId, "movementSteps" to it.movementSteps,
            "attackRange" to it.attackRange, "attackPower" to it.attackPower,
            "defencePower" to it.defencePower, "initiative" to it.initiative,
        ) },
        "unavailable" to unavailable.map { linkedMapOf("crewTypeId" to it.crewTypeId, "reason" to it.reason.name) },
    )

    companion object {
        const val META_KEY = "hwihaEncounterCombatProfiles"

        fun capture(forces: HwihaEncounterForces, rules: HwihaUnitProfiles): HwihaEncounterCombatProfiles {
            val profiles = mutableListOf<HwihaUnitProfile>()
            val unavailable = mutableListOf<UnavailableType>()
            for (id in forces.units.map { it.crewTypeId }.distinct().sorted()) {
                val profile = rules.find(id)
                if (profile != null) profiles.add(profile)
                else unavailable.add(UnavailableType(id, if (id in rules.unsupportedCrewTypeIds)
                    Reason.UNSUPPORTED_CREW_TYPE else Reason.UNKNOWN_CREW_TYPE))
            }
            return HwihaEncounterCombatProfiles(forces.encounterId, forces.snapshotId,
                rules.version, rules.contentHash, profiles, unavailable)
        }

        /** Caller must resolve the recorded catalog pin; a newer catalog is not a substitute. */
        fun read(meta: Map<String, Any?>, forces: HwihaEncounterForces,
                 rules: HwihaUnitProfiles): HwihaEncounterCombatProfiles? {
            if (META_KEY !in meta) return null
            val expected = capture(forces, rules)
            require(meta[META_KEY] == expected.toMetaValue()) { "Combat profiles differ from frozen forces or pinned rules" }
            return expected
        }
    }
}

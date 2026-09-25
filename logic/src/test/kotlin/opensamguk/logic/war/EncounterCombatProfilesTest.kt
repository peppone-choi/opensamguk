package opensamguk.logic.war

import kotlin.test.*
import opensamguk.logic.input.*

class EncounterCombatProfilesTest {
    private val profile = UnitProfile(1100, 1, 1, 100, 120, 20)
    private val rules = UnitProfiles(1, "a".repeat(64), listOf(profile), setOf(1000))
    private fun forces(vararg types: Int) = EncounterForces("b".repeat(64), types.mapIndexed { i, type ->
        EncounterUnitForce(i+1, 1, 1, type, 500, 50, 50, 0, 500, null)
    }, listOf(EncounterCommanderForce(1, 70, 70, 70, 70, 70)))
    private fun read(row: Any?, forces: EncounterForces, catalog: UnitProfiles = rules) =
        EncounterCombatProfiles.read(mapOf(EncounterCombatProfiles.META_KEY to row), forces, catalog)

    @Test fun `captures distinct profiles bound to all force observations and exact catalog pin`() {
        val force = forces(1100, 1100)
        val sealed = EncounterCombatProfiles.capture(force, rules)
        assertTrue(sealed.ready)
        assertEquals(listOf(profile), sealed.profiles)
        assertEquals(force.snapshotId, sealed.forcesSnapshotId)
        assertEquals(sealed.toMetaValue(), read(sealed.toMetaValue(), force)!!.toMetaValue())
        assertFailsWith<UnsupportedOperationException> { (sealed.profiles as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (sealed.unavailable as MutableList<*>).clear() }
    }
    @Test fun `unknown and known unsupported types retain distinct reasons without unit substitution`() {
        val force = forces(1100, 1000, 1)
        val sealed = EncounterCombatProfiles.capture(force, rules)
        assertFalse(sealed.ready)
        assertEquals(listOf(1, 1000), sealed.unavailable.map { it.crewTypeId })
        assertEquals(listOf(EncounterCombatProfiles.Reason.UNKNOWN_CREW_TYPE,
            EncounterCombatProfiles.Reason.UNSUPPORTED_CREW_TYPE), sealed.unavailable.map { it.reason })
        assertEquals(listOf(profile), sealed.profiles)
        assertEquals(3, force.units.size)
        assertEquals(sealed.toMetaValue(), read(sealed.toMetaValue(), force)!!.toMetaValue())
    }
    @Test fun `different force snapshot or new catalog cannot rewrite history`() {
        val force = forces(1100)
        val row = EncounterCombatProfiles.capture(force, rules).toMetaValue()
        val changed = EncounterForces(force.encounterId,
            force.units.map { it.copy(morale=49) }, force.commanders)
        assertFailsWith<IllegalArgumentException> { read(row, changed) }
        assertFailsWith<IllegalArgumentException> { read(row, force,
            UnitProfiles(1, "c".repeat(64), listOf(profile), setOf(1000))) }
        assertFailsWith<IllegalArgumentException> { read(row, force,
            UnitProfiles(1, rules.contentHash, listOf(profile.copy(attackPower=101)), setOf(1000))) }
    }
    @Test fun `missing is absent while null extra keys coercions and numerical tampering reject`() {
        val force = forces(1100)
        val row = EncounterCombatProfiles.capture(force, rules).toMetaValue()
        assertNull(EncounterCombatProfiles.read(emptyMap(), force, rules))
        for (bad in listOf(null, row+mapOf("extra" to 1), row+mapOf("version" to 1L),
            row+mapOf("status" to "UNAVAILABLE"), row+mapOf("profiles" to emptyList<Any>()),
            row+mapOf("profiles" to listOf(mapOf("crewTypeId" to 1100, "movementSteps" to 2,
                "attackRange" to 1, "attackPower" to 100, "defencePower" to 120, "initiative" to 20))))) {
            assertFailsWith<IllegalArgumentException> { read(bad, force) }
        }
    }
}

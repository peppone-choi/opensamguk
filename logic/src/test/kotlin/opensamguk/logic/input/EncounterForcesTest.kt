package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.world.StrategicNodeRef.LandProvince

class EncounterForcesTest {
    private val encounter = CorpsEncounter(EncounterParticipant("a",1,1,1,listOf(11)),
        listOf(EncounterParticipant("b",2,3,0,listOf(22))), LandProvince("B"),LandProvince("A"),
        Phase(200,1,1),"qa","a".repeat(64))
    private val units = listOf(EncounterUnitForce(11,1,1,1,500,20,30,40,600,null),
        EncounterUnitForce(22,2,3,1100,800,100,0,100,0,9))
    private val commanders = listOf(EncounterCommanderForce(1,110,0,55,60,70),EncounterCommanderForce(3,1,2,3,4,5))
    private fun force() = EncounterForces(encounter.encounterId,units,commanders)
    private fun read(row: Map<String,Any?>) = EncounterForces.read(mapOf(EncounterForces.META_KEY to row),encounter)

    @Test fun `canonical immutable snapshot preserves exact past values and nullable commander link`() {
        val mutable = units.reversed().toMutableList()
        val f = EncounterForces(encounter.encounterId,mutable,commanders.reversed())
        mutable.clear()
        assertEquals(force().snapshotId,f.snapshotId)
        assertEquals(force().toMetaValue(),read(f.toMetaValue())!!.toMetaValue())
        assertEquals(110,read(f.toMetaValue())!!.commanders.first().leadership)
        assertFailsWith<UnsupportedOperationException> { (f.units as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (f.commanders as MutableList<*>).clear() }
        assertNull(EncounterForces.read(emptyMap(),encounter))
        assertFailsWith<IllegalArgumentException> { EncounterForces.read(mapOf(EncounterForces.META_KEY to null),encounter) }
    }
    @Test fun `ranges and duplicate identities reject without clamps`() {
        for (bad in listOf(units[0].let { { it.copy(troops=0) } }, { units[0].copy(crewTypeId=0) },
            { units[0].copy(training=101) }, { units[0].copy(morale=-1) }, { units[0].copy(fatigue=101) },
            { units[0].copy(provisions=-1) }, { units[0].copy(commanderRetainerId=0) })) assertFailsWith<IllegalArgumentException> { bad() }
        assertFailsWith<IllegalArgumentException> { commanders[0].copy(charm=-1) }
        assertFailsWith<IllegalArgumentException> { EncounterForces(encounter.encounterId,units+units[0],commanders) }
        assertFailsWith<IllegalArgumentException> { EncounterForces(encounter.encounterId,units,commanders+commanders[0]) }
    }
    @Test fun `exact encounter unit owner commander and commander set binding required`() {
        val invalid = listOf(EncounterForces("b".repeat(64),units,commanders),
            EncounterForces(encounter.encounterId,units.drop(1),commanders),
            EncounterForces(encounter.encounterId,listOf(units[0].copy(ownerGeneralId=2),units[1]),commanders),
            EncounterForces(encounter.encounterId,listOf(units[0].copy(commanderGeneralId=3),units[1]),commanders),
            EncounterForces(encounter.encounterId,units,commanders.drop(1)))
        invalid.forEach { assertFailsWith<IllegalArgumentException> { it.requireBinding(encounter) } }
    }
    @Test fun `strict keys numeric types ordering and tamper hashes reject`() {
        val row = force().toMetaValue()
        for (bad in listOf(row+mapOf("extra" to true),row+mapOf("version" to 1L),row+mapOf("snapshotId" to "bad"),
            row+mapOf("units" to units.reversed().map { it.toMetaValue() }),
            row+mapOf("commanders" to commanders.reversed().map { it.toMetaValue() })))
            assertFailsWith<IllegalArgumentException> { read(bad) }
        for (value in listOf<Any>(500L,500.0,"500",true,-1,501)) {
            assertFailsWith<IllegalArgumentException> { read(row+mapOf("units" to listOf(units[0].toMetaValue()+mapOf("troops" to value),units[1].toMetaValue()))) }
        }
        assertFailsWith<IllegalArgumentException> { read(row+mapOf("units" to listOf(units[0].toMetaValue()-"commanderRetainerId",units[1].toMetaValue()))) }
        assertFailsWith<IllegalArgumentException> { read(row+mapOf("commanders" to listOf(commanders[0].toMetaValue()+mapOf("extra" to 1),commanders[1].toMetaValue()))) }
        assertNotEquals(force().snapshotId,EncounterForces(encounter.encounterId,listOf(units[0].copy(provisions=601),units[1]),commanders).snapshotId)
    }
    @Test fun `rehashed metadata cannot change self or deputy commander card binding`() {
        val extended = encounter.copy(defenders=listOf(encounter.defenders.single().copy(bugokIds=listOf(22,23))))
        val deputyUnits = listOf(units[1], units[1].copy(bugokId=23))
        val invalidUnits = listOf(
            listOf(units[0].copy(commanderRetainerId=9)) + deputyUnits,
            listOf(units[0]) + deputyUnits.map { it.copy(commanderRetainerId=null) },
            listOf(units[0], deputyUnits[0], deputyUnits[1].copy(commanderRetainerId=10)),
        )
        invalidUnits.forEach { observations ->
            // Recompute the hash so rejection proves semantic binding, not hash tampering.
            val raw = EncounterForces(extended.encounterId,observations,commanders).toMetaValue()
            assertFailsWith<IllegalArgumentException> {
                EncounterForces.read(mapOf(EncounterForces.META_KEY to raw),extended)
            }
        }
        val valid = EncounterForces(extended.encounterId,listOf(units[0])+deputyUnits,commanders)
        assertEquals(valid.toMetaValue(),EncounterForces.read(
            mapOf(EncounterForces.META_KEY to valid.toMetaValue()),extended)!!.toMetaValue())
    }

}

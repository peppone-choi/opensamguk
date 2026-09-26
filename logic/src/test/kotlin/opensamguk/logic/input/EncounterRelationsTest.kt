package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.world.StrategicNodeRef

class EncounterRelationsTest {
    private val phase = Phase(200,1,1)
    private val node = StrategicNodeRef.LandProvince("B")
    private fun state(nations: List<Int> = listOf(1,0,0)) = DeploymentProjection(RuleProfile.HWIHA,
        nations.mapIndexed { index, nation -> DeploymentPerson(index+1,nation,true,node,true) },
        (1..3).map { DeploymentUnit(it*10,it,100,null) }, emptyList(),
        nations.mapIndexed { index, nation -> DeployedCorps("order-${index+1}",index+1,index+1,null,nation,listOf((index+1)*10),phase) })
    private fun encounter(state: DeploymentProjection) = CorpsEncounter(EncounterParticipant.from(state.deployed[0]),
        state.deployed.drop(1).map(EncounterParticipant::from),node,StrategicNodeRef.LandProvince("A"),phase,"qa","a".repeat(64))

    @Test fun `independent neutral defenders remain hostile to each other and can fight back`() {
        val state = state();val snapshot = EncounterRelations.capture(encounter(state),state,emptySet())
        assertTrue(snapshot.isHostile(1,2));assertTrue(snapshot.isHostile(2,1));assertTrue(snapshot.isHostile(2,3))
        assertEquals(3,snapshot.pairs.size)
    }

    @Test fun `two nations at war with attacker are not silently allied or made mutually hostile`() {
        val state = state(listOf(1,2,3));val encounter = encounter(state)
        val snapshot = EncounterRelations.capture(encounter,state,setOf(1 to 2,3 to 1))
        assertTrue(snapshot.isHostile(1,2));assertTrue(snapshot.isHostile(1,3));assertFalse(snapshot.isHostile(2,3))
        val changed = EncounterRelations.capture(encounter,state,setOf(1 to 2,1 to 3,2 to 3))
        assertNotEquals(snapshot.snapshotId,changed.snapshotId)
        assertFalse(EncounterRelations.read(mapOf(EncounterRelations.META_KEY to snapshot.toMetaValue()),encounter)!!.isHostile(2,3))
    }

    @Test fun `same neutral personal root remains non hostile`() {
        val state = state().copy(retainers=listOf(DeploymentRetainer(4,2,3,true)))
        val snapshot = EncounterRelations.capture(encounter(state),state,emptySet())
        assertFalse(snapshot.isHostile(2,3));assertTrue(snapshot.isHostile(1,3))
    }

    @Test fun `input ordering cannot change canonical relation identity`() {
        val state = state();val encounter = encounter(state)
        val first = EncounterRelations.capture(encounter,state,emptySet())
        val second = EncounterRelations.capture(encounter.copy(defenders=encounter.defenders.reversed()),
            state.copy(people=state.people.reversed(),units=state.units.reversed(),deployed=state.deployed.reversed()),emptySet())
        assertEquals(first.toMetaValue(),second.toMetaValue())
        assertFailsWith<UnsupportedOperationException> { (first.pairs as MutableList).clear() }
    }

    @Test fun `missing corps stale position or no actual attacker hostility rejects`() {
        val state = state();val encounter = encounter(state)
        assertFailsWith<IllegalArgumentException> { EncounterRelations.capture(encounter,state.copy(deployed=state.deployed.dropLast(1)),emptySet()) }
        assertFailsWith<IllegalArgumentException> { EncounterRelations.capture(encounter,state.copy(people=state.people.map { it.copy(node=StrategicNodeRef.LandProvince("elsewhere")) }),emptySet()) }
        val peaceful = state(listOf(1,2,3))
        assertFailsWith<IllegalArgumentException> { EncounterRelations.capture(encounter(peaceful),peaceful,emptySet()) }
    }

    @Test fun `strict codec rejects changed hostility incomplete pairs invalid types and stale event`() {
        val state = state();val encounter = encounter(state)
        val snapshot = EncounterRelations.capture(encounter,state,emptySet());val raw = snapshot.toMetaValue()
        fun read(value: Any?) = EncounterRelations.read(mapOf(EncounterRelations.META_KEY to value),encounter)
        assertEquals(raw,assertNotNull(read(raw)).toMetaValue())
        @Suppress("UNCHECKED_CAST") val pairs = raw["pairs"] as List<Map<String,Any>>
        for (bad in listOf(null,raw+("version" to 2),raw+("version" to 1L),raw+("extra" to true),
            raw+("pairs" to pairs.dropLast(1)),raw+("pairs" to pairs.reversed()),
            raw+("pairs" to (pairs.dropLast(1)+listOf(pairs.last()+("hostile" to false)))),
            raw+("pairs" to (listOf(pairs[0]+("hostile" to "true"))+pairs.drop(1))),raw+("snapshotId" to "b".repeat(64)))) {
            assertFailsWith<IllegalArgumentException> { read(bad) }
        }
        assertFailsWith<IllegalArgumentException> { snapshot.requireBinding(encounter.copy(phase=phase.plus(1))) }
        assertFailsWith<IllegalArgumentException> { snapshot.isHostile(1,99) }
        assertNull(EncounterRelations.read(emptyMap(),encounter))
    }
}

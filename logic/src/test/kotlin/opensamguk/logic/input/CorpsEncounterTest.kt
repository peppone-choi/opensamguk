package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.world.*

class CorpsEncounterTest {
    private val topology = StrategicTopologySnapshot("qa",setOf("A","B","C"),emptyList(),emptyList(),emptyList(),
        mapOf("qa.json" to "a".repeat(64)))
    private val phase = Phase(200,1,2)
    private val attackingCorps = DeployedCorps("attack",1,2,3,1,listOf(10,11),phase)
    private val attacker = EncounterParticipant.from(attackingCorps)
    private val defender = EncounterParticipant("defend",4,5,0,listOf(20,21))
    private val second = EncounterParticipant("third",6,7,3,listOf(30))
    private val state = CorpsEncounter(attacker,listOf(defender,second),StrategicNodeRef.LandProvince("B"),
        StrategicNodeRef.LandProvince("A"),phase,topology.topologyRevision,topology.contentHash)
    private fun read(raw: Any?) = CorpsEncounter.read(mapOf(CorpsEncounter.META_KEY to raw),topology)

    @Test fun `canonical identity and stored participant order ignore caller list order`() {
        val reversed = state.copy(attacker=attacker.copy(bugokIds=listOf(11,10)),
            defenders=listOf(second,defender.copy(bugokIds=listOf(21,20))))
        assertEquals(state.encounterId,reversed.encounterId)
        assertEquals(state.toMetaValue(),reversed.toMetaValue())
        assertEquals(state.toMetaValue(),assertNotNull(read(reversed.toMetaValue())).toMetaValue())
        assertTrue(state.encounterId.matches(Regex("[0-9a-f]{64}")))
        assertNull(CorpsEncounter.read(emptyMap(),topology))
    }

    @Test fun `participants bind exact live corps and storage accepts commanders only`() {
        attacker.requireBinding(attackingCorps)
        for (changed in listOf(attackingCorps.copy(orderId="other"),attackingCorps.copy(ownerGeneralId=9),
            attackingCorps.copy(commanderGeneralId=8),attackingCorps.copy(nationId=2),attackingCorps.copy(bugokIds=listOf(10)))) {
            assertFailsWith<IllegalArgumentException> { attacker.requireBinding(changed) }
        }
        for (id in listOf(2,5,7)) state.requireParticipant(id)
        for (id in listOf(0,1,4,6,99)) assertFailsWith<IllegalArgumentException> { state.requireParticipant(id) }
    }

    @Test fun `identity includes role phase approach province pins and participant boundaries`() {
        val variants = listOf(state.copy(phase=phase.plus(1)),state.copy(approachFrom=StrategicNodeRef.LandProvince("C")),
            state.copy(province=StrategicNodeRef.LandProvince("C")),state.copy(topologyRevision="other"),
            state.copy(topologyHash="b".repeat(64)),state.copy(attacker=defender,defenders=listOf(attacker,second)),
            state.copy(attacker=attacker.copy(orderId="attack|defend")),
            state.copy(defenders=listOf(defender.copy(orderId="defend|third"),second)))
        assertEquals(variants.size+1,(variants+state).map { it.encounterId }.distinct().size)
        val left = state.copy(attacker=attacker.copy(orderId="a|b"),defenders=listOf(defender.copy(orderId="c")))
        val right = state.copy(attacker=attacker.copy(orderId="a"),defenders=listOf(defender.copy(orderId="b|c")))
        assertNotEquals(left.encounterId,right.encounterId)
    }

    @Test fun `duplicate commanders orders units and invalid participant identities reject`() {
        for (bad in listOf(attacker, defender.copy(orderId=attacker.orderId),defender.copy(commanderGeneralId=2),
            defender.copy(bugokIds=listOf(10)),second.copy(bugokIds=listOf(20)))) {
            assertFailsWith<IllegalArgumentException> { state.copy(defenders=listOf(defender,bad)) }
        }
        assertFailsWith<IllegalArgumentException> { state.copy(defenders=emptyList()) }
        assertFailsWith<IllegalArgumentException> { state.copy(approachFrom=state.province) }
        assertFailsWith<IllegalArgumentException> { attacker.copy(bugokIds=listOf(10,10)) }
        assertFailsWith<IllegalArgumentException> { attacker.copy(bugokIds=emptyList()) }
        assertFailsWith<IllegalArgumentException> { attacker.copy(ownerGeneralId=0) }
        assertFailsWith<IllegalArgumentException> { attacker.copy(commanderGeneralId=0) }
        assertFailsWith<IllegalArgumentException> { attacker.copy(nationId=-1) }
    }

    @Test fun `strict schema rejects unknown null wrong types stale pins and tampered identity`() {
        val raw = state.toMetaValue()
        for (bad in listOf(null,emptyMap<String,Any>(),raw-"phase",raw+("extra" to true),raw+("version" to 1L),
            raw+("version" to 2),raw+("encounterId" to "0".repeat(64)),raw+("defenders" to emptyList<Any>()),
            raw+("province" to "unknown"),raw+("approachFrom" to "B"),raw+("topologyHash" to "b".repeat(64)),
            raw+("phase" to mapOf("year" to 200,"month" to 1,"phase" to 2.0)))) {
            assertFailsWith<IllegalArgumentException> { read(bad) }
        }
        val participant = attacker.toMetaValue()
        for (bad in listOf(participant+("ownerGeneralId" to "1"),participant+("nationId" to true),
            participant+("bugokIds" to listOf(10L,11L)),participant+("bugokIds" to listOf(11,10)),
            participant+("privatePlan" to "hidden"))) {
            assertFailsWith<IllegalArgumentException> { read(raw+("attacker" to bad)) }
        }
        assertFailsWith<IllegalArgumentException> { read(raw+("defenders" to listOf(second.toMetaValue(),defender.toMetaValue()))) }
        val wrong = StrategicTopologySnapshot("stale",setOf("A","B","C"),emptyList(),emptyList(),emptyList(),
            mapOf("qa.json" to "a".repeat(64)))
        assertFailsWith<IllegalArgumentException> { CorpsEncounter.read(mapOf(CorpsEncounter.META_KEY to raw),wrong) }
    }
}

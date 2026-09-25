package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.world.*

class HwihaEncounterDeploymentTest {
    private val attacker = HwihaEncounterParticipant("attack", 1, 1, 1, listOf(11,12,13))
    private val defender = HwihaEncounterParticipant("defend", 2, 2, 0, listOf(21,22,23))
    private val third = HwihaEncounterParticipant("third", 3, 3, 0, listOf(31,32))
    private fun encounter(defenders: List<HwihaEncounterParticipant> = listOf(defender, third)) = HwihaCorpsEncounter(
        attacker, defenders, StrategicNodeRef.LandProvince("B"), StrategicNodeRef.LandProvince("A"),
        HwihaPhase(200,1,1), "qa", "a".repeat(64))
    private fun index(width: Int = 7, terrain: Char = '1') = HanProvinceCellIndex("qa", "a".repeat(64),
        "b".repeat(64), 10, 3, mapOf('1' to "PLAIN", '2' to "MOUNTAIN"),
        mapOf("A" to listOf(HanProvinceCell(0,1,'1')), "B" to (1..width).map { HanProvinceCell(it,1,terrain) }))
    private fun ready(state: HwihaCorpsEncounter = encounter(), index: HanProvinceCellIndex = index()) =
        assertIs<HwihaEncounterDeployment.Result.Ready>(HwihaEncounterDeployment.prepareDefault(state,index)).deployment

    @Test fun `every actual bugok is retained as one token with excess in reserve`() {
        val state = encounter()
        val setup = ready(state)
        assertEquals(state.encounterId,setup.encounterId)
        assertEquals(listOf(11,12,13,21,22,23,31,32),setup.tokens.map { it.bugokId })
        assertEquals(setOf(11,12,21,31),setup.tokens.filter { it.position != null }.map { it.bugokId }.toSet())
        assertEquals(setOf(13,22,23,32),setup.tokens.filter { it.position == null }.map { it.bugokId }.toSet())
        assertEquals(setOf(1,2,3),setup.tokens.filter { it.position != null }.map { it.commanderGeneralId }.toSet())
        assertEquals(4,setup.tokens.mapNotNull { it.position }.distinct().size)
        for (token in setup.tokens) {
            if (token.position == null) continue
            assertTrue(token.position in if (token.commanderGeneralId == 1) setup.layout.attackerZone else setup.layout.defenderZone)
        }
        assertEquals(setOf("defend","third"),setup.tokens.filter { it.commanderGeneralId != 1 }.map { it.orderId }.toSet())
    }

    @Test fun `input participant and unit ordering do not change default deployment`() {
        val ordered = ready()
        val reversed = ready(encounter(listOf(third.copy(bugokIds=listOf(32,31)),
            defender.copy(bugokIds=listOf(23,21,22)))).copy(attacker=attacker.copy(bugokIds=listOf(13,11,12))))
        assertEquals(ordered.encounterId,reversed.encounterId)
        assertEquals(ordered.tokens,reversed.tokens)
    }

    @Test fun `too few defender cells never excludes an actual participant`() {
        assertEquals(HwihaEncounterDeployment.Result.InsufficientDefenderCapacity,
            HwihaEncounterDeployment.prepareDefault(encounter(),index(width=2)))
        val single = ready(encounter(listOf(defender)),index(width=2))
        assertEquals(setOf(11,21),single.tokens.filter { it.position != null }.map { it.bugokId }.toSet())
        assertEquals(6,single.tokens.size)
    }

    @Test fun `terrain failures stay explicit and stale encounter pins cannot prepare`() {
        assertEquals(HwihaEncounterDeployment.Result.TerrainUnavailable(HwihaBattlefieldLayout.Reason.NO_PASSABLE_CELLS),
            HwihaEncounterDeployment.prepareDefault(encounter(),index(terrain='2')))
        assertFailsWith<IllegalArgumentException> {
            HwihaEncounterDeployment.prepareDefault(encounter().copy(topologyHash="c".repeat(64)),index())
        }
        assertFailsWith<IllegalArgumentException> {
            HwihaEncounterDeployment.prepareDefault(encounter().copy(topologyRevision="old"),index())
        }
    }

    @Test fun `prepared identities and token placement are immutable snapshots`() {
        val ids = mutableListOf(11,12,13)
        val participants = mutableListOf(defender,third)
        val state = encounter(participants).copy(attacker=attacker.copy(bugokIds=ids))
        val setup = ready(state)
        val id = setup.encounterId
        ids.clear();participants.clear()
        assertEquals(8,setup.tokens.size)
        assertEquals(id,setup.encounterId)
        assertFailsWith<UnsupportedOperationException> { (setup.tokens as MutableList).clear() }
    }
    @Test fun `sealed metadata rejects tampering extra keys stale versions and preserves unavailability`() {
        for (source in listOf(index(),index(width=2),index(terrain='2'))) {
            val state = encounter()
            val raw = HwihaEncounterDeployment.defaultMetaValue(state,source)
            assertNotNull(HwihaEncounterDeployment.read(mapOf(HwihaEncounterDeployment.META_KEY to raw),state,source))
            for (bad in listOf(null,raw+("version" to 2),raw+("layoutVersion" to 2),raw+("geometryVersion" to 2),
                raw+("encounterId" to "changed"),raw+("tilesContentHash" to "c".repeat(64)),raw+("unknown" to true))) {
                assertFailsWith<IllegalArgumentException> {
                    HwihaEncounterDeployment.read(mapOf(HwihaEncounterDeployment.META_KEY to bad),state,source)
                }
            }
        }
        val state = encounter()
        val raw = HwihaEncounterDeployment.defaultMetaValue(state,index())
        assertFailsWith<IllegalArgumentException> {
            HwihaEncounterDeployment.read(mapOf(HwihaEncounterDeployment.META_KEY to (raw+("tokens" to emptyList<Any>()))),state,index())
        }
        @Suppress("UNCHECKED_CAST")
        val tokens = raw["tokens"] as List<Map<String, Any?>>
        for (changedPosition in listOf(null, mapOf("col" to 1, "row" to 0))) {
            val changed = listOf(tokens.first() + ("position" to changedPosition)) + tokens.drop(1)
            assertFailsWith<IllegalArgumentException> {
                HwihaEncounterDeployment.read(mapOf(HwihaEncounterDeployment.META_KEY to (raw+("tokens" to changed))),state,index())
            }
        }
        assertNull(HwihaEncounterDeployment.read(emptyMap(),state,index()))
    }

}

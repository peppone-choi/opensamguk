package opensamguk.logic.war

import kotlin.test.*
import opensamguk.logic.input.*
import opensamguk.logic.world.*

class EncounterResolutionTest {
    private fun resolve(attacker: Int, defender: Int, width: Int = 5): EncounterResolution.Result {
        val phase = Phase(200, 1, 1); val node = StrategicNodeRef.LandProvince("B")
        val state = DeploymentProjection(RuleProfile.HWIHA, (1..2).map { DeploymentPerson(it, it, true, node, true) },
            listOf(DeploymentUnit(10, 1, attacker, null), DeploymentUnit(20, 2, defender, null)), emptyList(),
            (1..2).map { DeployedCorps("order-$it", it, it, null, it, listOf(it * 10), phase) })
        val encounter = CorpsEncounter(EncounterParticipant.from(state.deployed[0]),
            listOf(EncounterParticipant.from(state.deployed[1])), node, StrategicNodeRef.LandProvince("A"), phase, "qa", "a".repeat(64))
        val forces = EncounterForces(encounter.encounterId, listOf(
            EncounterUnitForce(10, 1, 1, 1100, attacker, 50, 50, 0, 100, null),
            EncounterUnitForce(20, 2, 2, 1100, defender, 50, 50, 0, 100, null)),
            (1..2).map { EncounterCommanderForce(it, 70, 70, 70, 70, 70) })
        val relations = EncounterRelations.capture(encounter, state, setOf(1 to 2))
        val combat = EncounterCombatProfiles.capture(forces, UnitProfiles(1, "c".repeat(64),
            listOf(UnitProfile(1100, 1, 1, 100, 120, 20)), emptySet()))
        val index = HanProvinceCellIndex("qa", "a".repeat(64), "b".repeat(64), width + 1, 1, mapOf('1' to "PLAIN"),
            mapOf("A" to listOf(HanProvinceCell(0, 0, '1')), "B" to (1..width).map { HanProvinceCell(it, 0, '1') }))
        val deployment = assertIs<EncounterDeployment.Result.Ready>(EncounterDeployment.prepareDefault(encounter, index)).deployment
        val plans = BattlePlans.defaultFor(encounter)
        val journal = BattlePlayback(encounter, forces, relations, combat, plans, deployment).initialJournal()
        return EncounterResolution.resolve(encounter, forces, relations, combat, plans, deployment, journal)
    }

    @Test fun `stronger attacker breaks the defender and wins`() {
        val result = resolve(1000, 100)
        assertEquals(EncounterResolution.Outcome.ATTACKER_VICTORY, result.outcome)
        assertEquals(listOf(1), result.winners); assertEquals(listOf(2), result.losers)
        assertNotEquals(BattlePlayback.Barrier.NONE, result.barrier)
        assertTrue(result.rounds in 1..BattlePlans.MAX_ROUNDS)
    }

    @Test fun `weaker attacker withdraws and the holding defender wins`() {
        val result = resolve(100, 1000)
        assertEquals(EncounterResolution.Outcome.DEFENDER_VICTORY, result.outcome)
        assertEquals(listOf(2), result.winners); assertEquals(listOf(1), result.losers)
        assertEquals(EncounterResolution.CommanderStatus.RETREATED, result.statuses[1])
    }

    @Test fun `annihilated defender is captured by the victorious attacker`() {
        val result = resolve(100_000, 1)
        assertEquals(EncounterResolution.CommanderStatus.DESTROYED, result.statuses[2])
        assertEquals(mapOf(2 to 1), result.captives)
    }

    @Test fun `resolution is deterministic and its journal replays from storage`() {
        val first = resolve(1000, 900); val second = resolve(1000, 900)
        assertEquals(first.replayHash, second.replayHash)
        assertEquals(first.journal.toMetaValue(), second.journal.toMetaValue())
        val cold = assertNotNull(BattleJournal.read(mapOf(BattleJournal.META_KEY to first.journal.toMetaValue())))
        assertEquals(first.journal.snapshotId, cold.snapshotId)
    }

    @Test fun `even armies at the round limit both withdraw and the attacker does not win`() {
        val result = resolve(1000, 1000, width = 40)
        assertEquals(EncounterResolution.Outcome.DEFENDER_VICTORY, result.outcome)
        assertTrue(1 in result.losers)
    }
}

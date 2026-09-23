package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.*
import opensamguk.logic.war.hwiha.HwihaBattleJournal
import opensamguk.logic.world.LandMarchStop

/** Real pinned map, in-memory world: an entered encounter is sealed, then resolved on the attacker's next turn. */
class HwihaEncounterResolverTest {
    private val fixture = HwihaCampaignWorldFixture()
    private val route = fixture.route()

    /** Attacker 1 (nation 1) marches from start toward destination; defender 100 (nation 2) holds the first province. */
    private fun sealed(attackerTroops: Int, defenderTroops: Int): Pair<InMemoryTurnWorld, ChangeRecorder> {
        val world = fixture.world(
            listOf(fixture.person(1, 1, route.startCity) to route.start,
                fixture.person(100, 2, route.startCity) to route.first),
            bugoks = listOf(fixture.unit(7, 1, attackerTroops), fixture.unit(1100, 100, defenderTroops)))
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, 1, listOf(7), route.destination)
        fixture.deploy(world, recorder, 100, listOf(1100), route.first)
        fixture.nextPhase(world)
        assertTrue(HwihaCorpsMarchTurn(world, recorder, fixture.topology, fixture.metrics, fixture.cells).onTurn(1))
        for (id in listOf(1, 100)) assertNotNull(HwihaCorpsEncounter.read(world.getGeneralById(id)!!.meta, fixture.topology))
        assertNotNull(HwihaBattleJournal.read(world.getGeneralById(1)!!.meta), "combat was prepared at approach")
        assertEquals(route.first, world.positionOf(1))
        return world to recorder
    }

    private val outcomes = HwihaCampaignWorldFixture.RecordingOutcomes()

    private fun resolveNextTurn(world: InMemoryTurnWorld, recorder: ChangeRecorder) {
        fixture.nextPhase(world)
        fixture.movement(world, recorder, outcomes).onTurn(1, HwihaCampaignWorldFixture.NO_INPUT)
    }

    @Test fun `strong attacker wins clears both sides and resumes its march next turn`() {
        val (world, recorder) = sealed(1000, 100)
        resolveNextTurn(world, recorder)
        for (id in listOf(1, 100)) {
            val meta = world.getGeneralById(id)!!.meta
            assertTrue(HwihaEncounterResolver.SEALED_KEYS.none { it in meta }, "sealed records cleared on $id")
            assertEquals("ATTACKER_VICTORY", (meta[HwihaEncounterResolver.BATTLE_RECORD_KEY] as Map<*, *>)["outcome"])
        }
        assertEquals(route.first, world.positionOf(1), "the battle ends this turn's movement")
        assertNull(HwihaDeploymentState.read(world.getGeneralById(100)!!.meta), "loser defender's corps is dissolved")
        assertTrue(world.getBugokById(1100)!!.troops < 100, "defender took casualties")
        val march = HwihaCorpsMarchState.read(world.getGeneralById(1)!!.meta, fixture.topology, fixture.metrics)!!
        assertNotEquals(LandMarchStop.ENCOUNTER, march.checkpoint.stop)
        val projection = HwihaDeploymentExecutor(world, recorder, fixture.topology, fixture.metrics).projection()!!
        assertFalse(projection.people.single { it.id == 1 }.inBattle, "BATTLE_PENDING cleared")
        assertEquals(listOf(listOf(1) to listOf(100)), outcomes.encounters, "renown boundary called exactly once")
        // The march continues on the attacker's following turn.
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(1, HwihaCampaignWorldFixture.NO_INPUT)
        assertEquals(route.destination, world.positionOf(1), "resumed march reaches the destination")
    }

    @Test fun `weak attacker withdraws to its approach and ends its expedition`() {
        val (world, recorder) = sealed(100, 1000)
        resolveNextTurn(world, recorder)
        val record = world.getGeneralById(1)!!.meta[HwihaEncounterResolver.BATTLE_RECORD_KEY] as Map<*, *>
        assertEquals("DEFENDER_VICTORY", record["outcome"])
        assertEquals(route.start, world.positionOf(1), "the loser withdraws to where it came from")
        val attacker = world.getGeneralById(1)!!.meta
        assertNull(HwihaDeploymentState.read(attacker))
        assertFalse(HwihaCorpsOrder.META_KEY in attacker || HwihaCorpsMarchState.META_KEY in attacker)
        assertNotNull(HwihaDeploymentState.read(world.getGeneralById(100)!!.meta), "winner defender keeps its corps")
        assertTrue(world.getBugokById(7)!!.troops < 100)
        assertEquals(listOf(listOf(100) to listOf(1)), outcomes.encounters)
    }

    @Test fun `same sealed battle resolves byte-identically and the renown writer is not called twice`() {
        val records = List(2) {
            val (world, recorder) = sealed(1000, 100)
            resolveNextTurn(world, recorder)
            world.getGeneralById(1)!!.meta[HwihaEncounterResolver.BATTLE_RECORD_KEY]
        }
        assertEquals(records[0], records[1])
        assertEquals(2, outcomes.encounters.size, "one call per resolved battle")
        val meta = mapOf<String, Any?>()
        val once = HwihaRenownEvents.record(meta, HwihaRenownEvents.Kind.REWARD_RECEIVED, 200, 1)!!
        assertNull(HwihaRenownEvents.record(once, HwihaRenownEvents.Kind.REWARD_RECEIVED, 200, 1))
    }

    @Test fun `an unprepared encounter stays pending instead of fabricating a result`() {
        val (world, recorder) = sealed(1000, 100)
        for (id in listOf(1, 100)) {
            val general = world.getGeneralById(id)!!
            world.applyGeneralDirtyFree(general.copy(meta = general.meta - HwihaBattleJournal.META_KEY))
        }
        fixture.nextPhase(world)
        assertEquals(HwihaEncounterResolver.Resolution.Unavailable("BATTLE_NOT_READY"),
            HwihaEncounterResolver(world, recorder, fixture.topology, fixture.metrics, fixture.cells).resolvePending(1))
        assertNotNull(HwihaCorpsEncounter.read(world.getGeneralById(1)!!.meta, fixture.topology))
        assertEquals(HwihaEncounterResolver.Resolution.NotAttacker,
            HwihaEncounterResolver(world, recorder, fixture.topology, fixture.metrics, fixture.cells).resolvePending(100))
    }

}

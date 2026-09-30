package opensamguk.engine.campaign

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.*
import opensamguk.logic.renown.RenownEventSource
import opensamguk.logic.renown.RenownEvents
import opensamguk.logic.war.BattleJournal
import opensamguk.logic.world.ProvinceCellIndex
import opensamguk.logic.world.LandMarchStop

/** Real pinned map, in-memory world: an entered encounter is sealed, then resolved on the attacker's next turn. */
class EncounterResolverTest {
    private val fixture = CampaignWorldFixture()
    private val route = fixture.route()

    /** Attacker 1 (nation 1) marches from start toward destination; defender 100 (nation 2) holds the first province. */
    private fun sealed(attackerTroops: Int, defenderTroops: Int, attackerCrewTypeId: Int = 1100): Pair<InMemoryTurnWorld, ChangeRecorder> {
        val world = fixture.world(
            listOf(fixture.person(1, 1, route.startCity) to route.start,
                fixture.person(100, 2, route.startCity) to route.first),
            bugoks = listOf(fixture.unit(7, 1, attackerTroops, crewTypeId = attackerCrewTypeId), fixture.unit(1100, 100, defenderTroops)))
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, 1, listOf(7), route.destination)
        fixture.deploy(world, recorder, 100, listOf(1100), route.first)
        fixture.nextPhase(world)
        assertTrue(CorpsMarchTurn(world, recorder, fixture.topology, fixture.metrics, fixture.cells).onTurn(1))
        for (id in listOf(1, 100)) assertNotNull(CorpsEncounter.read(world.getGeneralById(id)!!.meta, fixture.topology))
        if (attackerCrewTypeId == 1100)
            assertNotNull(BattleJournal.read(world.getGeneralById(1)!!.meta), "combat was prepared at approach")
        assertEquals(route.first, world.positionOf(1))
        return world to recorder
    }

    private val outcomes = CampaignWorldFixture.RecordingOutcomes()

    private fun resolveNextTurn(world: InMemoryTurnWorld, recorder: ChangeRecorder) {
        fixture.nextPhase(world)
        fixture.movement(world, recorder, outcomes).onTurn(1, CampaignWorldFixture.NO_INPUT)
    }

    @Test fun `strong attacker wins clears both sides and resumes its march next turn`() {
        val (world, recorder) = sealed(1000, 100)
        resolveNextTurn(world, recorder)
        for (id in listOf(1, 100)) {
            val meta = world.getGeneralById(id)!!.meta
            assertTrue(EncounterResolver.SEALED_KEYS.none { it in meta }, "sealed records cleared on $id")
            assertEquals("ATTACKER_VICTORY", (meta[EncounterResolver.BATTLE_RECORD_KEY] as Map<*, *>)["outcome"])
        }
        assertEquals(route.first, world.positionOf(1), "the battle ends this turn's movement")
        assertNull(DeploymentState.read(world.getGeneralById(100)!!.meta), "loser defender's corps is dissolved")
        assertTrue(world.getBugokById(1100)!!.troops < 100, "defender took casualties")
        val march = CorpsMarchState.read(world.getGeneralById(1)!!.meta, fixture.topology, fixture.metrics)!!
        assertNotEquals(LandMarchStop.ENCOUNTER, march.checkpoint.stop)
        val projection = DeploymentExecutor(world, recorder, fixture.topology, fixture.metrics).projection()!!
        assertFalse(projection.people.single { it.id == 1 }.inBattle, "BATTLE_PENDING cleared")
        assertEquals(listOf(listOf(1) to listOf(100)), outcomes.encounters, "renown boundary called exactly once")
        // The march continues on the attacker's following turn.
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(1, CampaignWorldFixture.NO_INPUT)
        assertEquals(route.destination, world.positionOf(1), "resumed march reaches the destination")
    }

    @Test fun `weak attacker withdraws to its approach and ends its expedition`() {
        val (world, recorder) = sealed(100, 1000)
        resolveNextTurn(world, recorder)
        val record = world.getGeneralById(1)!!.meta[EncounterResolver.BATTLE_RECORD_KEY] as Map<*, *>
        assertEquals("DEFENDER_VICTORY", record["outcome"])
        assertEquals(route.start, world.positionOf(1), "the loser withdraws to where it came from")
        val attacker = world.getGeneralById(1)!!.meta
        assertNull(DeploymentState.read(attacker))
        assertFalse(CorpsOrder.META_KEY in attacker || CorpsMarchState.META_KEY in attacker)
        assertNotNull(DeploymentState.read(world.getGeneralById(100)!!.meta), "winner defender keeps its corps")
        assertTrue(world.getBugokById(7)!!.troops < 100)
        assertEquals(listOf(listOf(100) to listOf(1)), outcomes.encounters)
    }

    @Test fun `same sealed battle resolves byte-identically and the renown writer is not called twice`() {
        val records = List(2) {
            val (world, recorder) = sealed(1000, 100)
            resolveNextTurn(world, recorder)
            world.getGeneralById(1)!!.meta[EncounterResolver.BATTLE_RECORD_KEY]
        }
        assertEquals(records[0], records[1])
        assertEquals(2, outcomes.encounters.size, "one call per resolved battle")
        val once = RenownEvents.recordRenownEvent(emptyMap(), RenownEventSource.REWARD, "0200-01")
        assertTrue(once.recorded)
        assertFalse(RenownEvents.recordRenownEvent(once.meta, RenownEventSource.REWARD, "0200-01").recorded)
    }

    @Test fun `outcome observation carries sealed pins and observer failure leaves battle unchanged`() {
        var observed: BattleOutcomeObservation? = null
        var observationCount = 0
        val deliveredOutcomes = CampaignWorldFixture.RecordingOutcomes()
        val (normalWorld, normalRecorder) = sealed(1000, 100)
        fixture.nextPhase(normalWorld)
        AssignmentMarchTurn(normalWorld, normalRecorder, fixture.topology, fixture.metrics, fixture.cells,
            outcomes = deliveredOutcomes,
            observations = BattleOutcomeObserver {
                observed = it
                observationCount++
            })
            .onTurn(1, CampaignWorldFixture.NO_INPUT)
        assertEquals(1, observationCount)
        val observation = assertNotNull(observed)
        val normalRecord = normalWorld.getGeneralById(1)!!.meta[EncounterResolver.BATTLE_RECORD_KEY] as Map<*, *>
        assertEquals(normalWorld.worldId.value, observation.worldId)
        assertEquals(normalWorld.getState().currentYear, observation.resolvedYear)
        assertEquals(normalWorld.getState().currentMonth, observation.resolvedMonth)
        assertEquals(normalWorld.getState().currentPhase, observation.resolvedPhase)
        assertEquals(normalWorld.getState().worldMapVariant?.name, observation.worldMapVariant)
        assertEquals(fixture.cells.topologyRevision, observation.topologyRevision)
        assertEquals(fixture.cells.topologyHash, observation.topologyHash)
        assertEquals(fixture.cells.tilesContentHash, observation.tilesContentHash)
        assertEquals(normalRecord["encounterId"], observation.encounterId)
        assertEquals(normalRecord["outcome"], observation.outcome)
        assertEquals(normalRecord["barrier"], observation.barrier)
        assertEquals(normalRecord["rounds"], observation.rounds)
        assertEquals(normalRecord["replayHash"], observation.replayHash)
        assertEquals(normalRecord["ruleVersion"], observation.resolutionRuleVersion)
        assertEquals(listOf(1), observation.winners)
        assertEquals(listOf(1, 100), observation.statuses.map { it.generalId })
        assertTrue(observation.initialSeparationSteps!! > 0)
        assertTrue(observation.callbackInvoked)
        assertEquals(listOf(listOf(1) to listOf(100)), deliveredOutcomes.encounters)

        val (failureWorld, failureRecorder) = sealed(1000, 100)
        fixture.nextPhase(failureWorld)
        AssignmentMarchTurn(failureWorld, failureRecorder, fixture.topology, fixture.metrics, fixture.cells,
            observations = BattleOutcomeObserver { throw AssertionError("QA sink unavailable") })
            .onTurn(1, CampaignWorldFixture.NO_INPUT)
        assertEquals(normalRecord, failureWorld.getGeneralById(1)!!.meta[EncounterResolver.BATTLE_RECORD_KEY])
        assertEquals(normalWorld.positionOf(1), failureWorld.positionOf(1))
        assertEquals(normalWorld.getBugokById(7), failureWorld.getBugokById(7))
    }

    @Test fun `fatal observer errors escape instead of hiding a broken JVM`() {
        val (outOfMemoryWorld, outOfMemoryRecorder) = sealed(1000, 100)
        fixture.nextPhase(outOfMemoryWorld)
        assertFailsWith<OutOfMemoryError> {
            AssignmentMarchTurn(outOfMemoryWorld, outOfMemoryRecorder, fixture.topology, fixture.metrics, fixture.cells,
                observations = BattleOutcomeObserver { throw OutOfMemoryError("synthetic QA failure") })
                .onTurn(1, CampaignWorldFixture.NO_INPUT)
        }
        val (threadDeathWorld, threadDeathRecorder) = sealed(1000, 100)
        fixture.nextPhase(threadDeathWorld)
        assertFailsWith<ThreadDeath> {
            AssignmentMarchTurn(threadDeathWorld, threadDeathRecorder, fixture.topology, fixture.metrics, fixture.cells,
                observations = BattleOutcomeObserver { throw ThreadDeath() })
                .onTurn(1, CampaignWorldFixture.NO_INPUT)
        }
        val (linkageWorld, linkageRecorder) = sealed(1000, 100)
        fixture.nextPhase(linkageWorld)
        assertFailsWith<LinkageError> {
            AssignmentMarchTurn(linkageWorld, linkageRecorder, fixture.topology, fixture.metrics, fixture.cells,
                observations = BattleOutcomeObserver { throw LinkageError("synthetic QA failure") })
                .onTurn(1, CampaignWorldFixture.NO_INPUT)
        }
    }

    @Test fun `post flush evidence is immutable retryable and quarantines uncommitted observations`() {
        fun resolveInto(observer: BattleOutcomeObserver) {
            val (world, recorder) = sealed(1000, 100)
            fixture.nextPhase(world)
            AssignmentMarchTurn(world, recorder, fixture.topology, fixture.metrics, fixture.cells,
                observations = observer).onTurn(1, CampaignWorldFixture.NO_INPUT)
        }

        val published = mutableListOf<CommittedBattleOutcomeBatch>()
        val normal = BattleOutcomePostFlush { published.add(it) }
        resolveInto(normal)
        assertTrue(published.isEmpty(), "resolution alone must not publish")
        normal.afterSuccessfulFlush(1, 7)
        assertEquals(1, published.size)
        assertEquals(7, published.single().generation)
        assertEquals(1, published.single().observations.size)
        assertFailsWith<UnsupportedOperationException> {
            (published.single().observations as MutableList).clear()
        }
        normal.afterSuccessfulFlush(1, 8)
        assertEquals(1, published.size, "an empty later flush must not repeat the result")

        val quarantined = BattleOutcomePostFlush { published.add(it) }
        resolveInto(quarantined)
        quarantined.quarantineUncommitted()
        quarantined.afterSuccessfulFlush(1, 9)
        assertEquals(1, published.size, "a stale uncommitted result must stay private")

        var failFirst = true
        val retried = BattleOutcomePostFlush { batch ->
            if (failFirst) { failFirst = false; throw IllegalStateException("QA export unavailable") }
            published.add(batch)
        }
        resolveInto(retried)
        assertFailsWith<IllegalStateException> { retried.afterSuccessfulFlush(1, 10) }
        retried.afterSuccessfulFlush(1, 11)
        assertEquals(2, published.size)
        assertEquals(10, published.last().generation, "retry keeps the original committed batch")
    }

    @Test fun `an unprepared encounter retries two phases then disbands without battle`() {
        val (world, recorder) = sealed(1000, 100)
        for (id in listOf(1, 100)) {
            val general = world.getGeneralById(id)!!
            world.applyGeneralDirtyFree(general.copy(meta = general.meta - BattleJournal.META_KEY))
        }
        fixture.nextPhase(world)
        assertEquals(EncounterResolver.Resolution.Unavailable("BATTLE_NOT_READY"),
            EncounterResolver(world, recorder, fixture.topology, fixture.metrics, fixture.cells).resolvePending(1))
        assertNotNull(CorpsEncounter.read(world.getGeneralById(1)!!.meta, fixture.topology))
        assertEquals(EncounterResolver.Resolution.NotAttacker,
            EncounterResolver(world, recorder, fixture.topology, fixture.metrics, fixture.cells).resolvePending(100))
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(1, CampaignWorldFixture.NO_INPUT)
        for (id in listOf(1, 100)) {
            val meta = world.getGeneralById(id)!!.meta
            assertTrue(EncounterResolver.SEALED_KEYS.none { it in meta })
            assertEquals("BATTLE_NOT_READY", (meta[EncounterResolver.DISBAND_RECORD_KEY] as Map<*, *>)["reason"])
            assertNull(DeploymentState.read(meta), "both corps stop in their current province")
            assertFalse(EncounterResolver.BATTLE_RECORD_KEY in meta, "no battle result is fabricated")
        }
        assertEquals(route.first, world.positionOf(1))
    }

    @Test fun `unsupported crew type disbands immediately and does not stop the personal turn loop`() {
        val (world, recorder) = sealed(1000, 100, attackerCrewTypeId = 9999)
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(1, CampaignWorldFixture.NO_INPUT)
        for (id in listOf(1, 100)) {
            val meta = world.getGeneralById(id)!!.meta
            assertTrue(EncounterResolver.SEALED_KEYS.none { it in meta })
            assertEquals("UNIT_PROFILE_UNAVAILABLE", (meta[EncounterResolver.DISBAND_RECORD_KEY] as Map<*, *>)["reason"])
            assertNull(DeploymentState.read(meta))
        }
        assertEquals(route.first, world.positionOf(1))
    }

    @Test fun `battlefield geometry unavailable disbands without a battle`() {
        val world = fixture.world(
            listOf(fixture.person(1, 1, route.startCity) to route.start,
                fixture.person(100, 2, route.startCity) to route.first),
            bugoks = listOf(fixture.unit(7, 1, 1000), fixture.unit(1100, 100, 100)))
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, 1, listOf(7), route.destination)
        fixture.deploy(world, recorder, 100, listOf(1100), route.first)
        val emptyTerrain = ProvinceCellIndex(fixture.topology.topologyRevision, fixture.topology.contentHash,
            fixture.cells.tilesContentHash, 1, 1, mapOf('0' to "plain"),
            mapOf(route.start.id to emptyList(), route.first.id to emptyList()))
        fixture.nextPhase(world)
        assertTrue(CorpsMarchTurn(world, recorder, fixture.topology, fixture.metrics, emptyTerrain).onTurn(1))
        fixture.nextPhase(world)
        AssignmentMarchTurn(world, recorder, fixture.topology, fixture.metrics, emptyTerrain)
            .onTurn(1, CampaignWorldFixture.NO_INPUT)
        val meta = world.getGeneralById(1)!!.meta
        assertEquals("BATTLEFIELD_UNAVAILABLE", (meta[EncounterResolver.DISBAND_RECORD_KEY] as Map<*, *>)["reason"])
        assertFalse(EncounterResolver.BATTLE_RECORD_KEY in meta)
    }

}

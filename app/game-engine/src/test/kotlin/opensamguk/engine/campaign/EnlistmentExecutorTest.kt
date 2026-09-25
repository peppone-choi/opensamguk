package opensamguk.engine.campaign

import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef

class EnlistmentExecutorTest {
    private fun general(id: Int, nation: Int = 0, lord: Boolean = false, human: Boolean = false) = TurnGeneral(
        id = id, name = "G$id", nationId = nation, cityId = 10, troopId = 0,
        stats = GeneralStats(80, 70, 60), experience = 300, dedication = 400, officerLevel = if (nation > 0 && lord) 12 else 0,
        npcState = if (human) 0 else 2, userId = if (human) "100" else null,
        gold = 1000, rice = 2000, crew = 300, turnTime = Instant.EPOCH,
        meta = mapOf("lord" to lord, "unrelated" to "preserve"),
    )
    private fun world(profile: String = "HWIHA", cards: List<Retainer> = emptyList()): InMemoryTurnWorld {
        val generals = listOf(general(1, lord = true, human = true), general(2), general(3),
            general(10, nation = 1, lord = true), general(20, nation = 2, lord = true),
            general(11, nation = 1).copy(npcState = 5))
        val hash = "b".repeat(64)
        val positions = generals.fold(GeneralPositionSnapshot("r1", hash, setOf("p1", "p2"), emptySet())) { acc, g ->
            acc.withState(GeneralPositionState("r1", hash, g.id, StrategicNodeRef.LandProvince("p2"), 1))
        }
        return InMemoryTurnWorld(WorldSnapshot(
            state = TurnWorldState(1, 200, 1, 3600, Instant.EPOCH,
                config = mapOf("mapName" to "han-world-v3", "ruleProfile" to profile)),
            worldId = WorldId(1), generals = generals,
            nations = listOf(Nation(1, "N1", "#000", gold = 500, meta = mapOf("gennum" to 1, "keep" to 42)),
                Nation(2, "N2", "#fff", gold = 600, meta = mapOf("gennum" to 1))),
            retainers = cards, bugoks = listOf(Bugok(7, 1, "personal", 100, 1, 50, 50, provisions = 200)),
            generalPositionSnapshot = positions, cityLandProvinceById = mapOf(10 to "p1"),
        ))
    }
    private fun policy() = EnlistmentPolicy(setOf(10, 20), mapOf(10 to 30, 20 to 30), 7)
    private val request = EnlistmentRequest(1, EnlistmentMode.NATION, 1)
    private val noDraw: (Int) -> Int = { error("direct enlistment must not draw") }

    @Test fun `enlistment moves the personal subtree without moving bodies or property`() {
        val child = Retainer(4, 1, "EXISTING", 2, "G2", "lieutenant")
        val recruited = Retainer(5, 1, "RECRUITED", null, "personal recruit", "guest")
        val world = world(cards = listOf(child, recruited))
        val recorder = ChangeRecorder()
        val before = world.listGenerals().associateBy { it.id }
        val armies = world.listBugoks()
        val positions = before.keys.associateWith(world::positionOf)
        val result = assertIs<EnlistmentExecution.Applied>(EnlistmentExecutor(world, recorder) { policy() }.execute(request, noDraw))
        assertEquals(listOf(1, 2), result.plan.joiningGeneralIds)
        assertEquals(before.getValue(1).copy(nationId = 1, meta = LordStatus.afterEnlistment(before.getValue(1).meta)), world.getGeneralById(1))
        assertEquals(before.getValue(2).copy(nationId = 1), world.getGeneralById(2))
        for (id in listOf(3, 10, 11, 20)) assertEquals(before[id], world.getGeneralById(id))
        assertEquals(positions, before.keys.associateWith(world::positionOf))
        assertEquals(armies, world.listBugoks())
        assertEquals(child, world.getRetainerById(4))
        assertEquals(recruited, world.getRetainerById(5))
        assertEquals(1, world.getRetainerById(result.retainerId)!!.generalId)
        assertEquals(10, world.getRetainerById(result.retainerId)!!.masterGeneralId)
        assertEquals(mapOf("gennum" to 3, "keep" to 42), world.getNationById(1)!!.meta)
        assertEquals(setOf(1, 2), recorder.generalPatches().map { it.id }.toSet())
        assertEquals(1, recorder.nationPatches().size)
        val again = assertIs<EnlistmentExecution.Rejected>(EnlistmentExecutor(world, recorder) { policy() }.execute(request, noDraw))
        assertEquals(EnlistmentFailure.ALREADY_SERVING, again.reason)
        assertEquals(3, world.listRetainers().size)
        assertEquals(result.retainerId, world.getState().meta["maxRetainerId"])
        val payload = opensamguk.engine.flush.DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        assertEquals(setOf(1, 2), payload.updatedGenerals.map { it.id }.toSet())
        assertTrue(payload.updatedGenerals.all { it.nationId == 1 })
        assertEquals(3, payload.updatedNations.single().gennum)
        assertEquals(result.retainerId, payload.createdRetainers.single().id)
        assertEquals(result.retainerId, payload.worldStateUpdate["max_retainer_id"])
    }

    @Test fun `current capacity observes earlier enlistment in the same turn`() {
        val world = world()
        val recorder = ChangeRecorder()
        val executor = EnlistmentExecutor(world, recorder) {
            policy().copy(freeRenownByLord = mapOf(10 to (7 - world.retainersOf(10).size * 7)))
        }
        assertIs<EnlistmentExecution.Applied>(executor.execute(request, noDraw))
        val second = assertIs<EnlistmentExecution.Rejected>(executor.execute(request.copy(actorId = 3), noDraw))
        assertEquals(EnlistmentFailure.INSUFFICIENT_RENOWN, second.reason)
        assertEquals(0, world.getGeneralById(3)!!.nationId)
        assertEquals(1, world.listRetainers().size)
    }

    @Test fun `recruited card name collision rejects before any write and random excludes it`() {
        val collision = Retainer(4, 10, "RECRUITED", null, "G1", "guest")
        val world = world(cards = listOf(collision))
        val recorder = ChangeRecorder()
        val before = world.listGenerals()
        val initialState = world.getState()
        val executor = EnlistmentExecutor(world, recorder) { policy() }
        assertEquals(EnlistmentFailure.DUPLICATE_RETAINER_NAME,
            assertIs<EnlistmentExecution.Rejected>(executor.execute(request, noDraw)).reason)
        assertEquals(before, world.listGenerals())
        assertTrue(recorder.generalPatches().isEmpty())
        assertTrue(recorder.nationPatches().isEmpty())
        assertEquals(initialState, world.getState())
        val selected = assertIs<EnlistmentExecution.Applied>(executor.execute(EnlistmentRequest(1, EnlistmentMode.RANDOM), noDraw))
        assertEquals(20, selected.plan.masterId)
    }

    @Test fun `lost lord status is rechecked and does not allocate a card`() {
        val world = world()
        val recorder = ChangeRecorder()
        val executor = EnlistmentExecutor(world, recorder) { policy() }
        val lord = world.getGeneralById(10)!!
        world.applyGeneralDirtyFree(lord.copy(meta = LordStatus.afterEnlistment(lord.meta)))
        assertEquals(EnlistmentFailure.TARGET_NOT_LORD,
            assertIs<EnlistmentExecution.Rejected>(executor.execute(request, noDraw)).reason)
        assertTrue(world.listRetainers().isEmpty())
        assertTrue(recorder.generalPatches().isEmpty())
        assertNull(world.getState().meta["maxRetainerId"])
    }

    @Test fun `sammo is rejected before reading hwiha policy`() {
        val world = world(profile = "SAMMO")
        val executor = EnlistmentExecutor(world, ChangeRecorder()) { error("SAMMO policy read") }
        assertEquals(EnlistmentFailure.WRONG_RULE_PROFILE,
            assertIs<EnlistmentExecution.Rejected>(executor.execute(request, noDraw)).reason)
        assertEquals(0, world.getGeneralById(1)!!.nationId)
    }
    @Test fun `general target follows its explicit lord without teleporting`() {
        val world = world(cards = listOf(Retainer(4, 10, "EXISTING", 2, "G2", "guest")))
        world.applyGeneralDirtyFree(world.getGeneralById(2)!!.copy(nationId = 1))
        val executor = EnlistmentExecutor(world, ChangeRecorder()) { policy() }
        val result = assertIs<EnlistmentExecution.Applied>(executor.execute(
            EnlistmentRequest(1, EnlistmentMode.GENERAL, 2), noDraw))
        assertEquals(10, result.plan.masterId)
        assertEquals(1, result.plan.nationId)
        assertEquals(StrategicNodeRef.LandProvince("p2"), world.positionOf(1))
    }

    @Test fun `random consumes one external draw in stable nation order`() {
        fun run(): Pair<Int, Int> {
            val world = world()
            var draws = 0
            val result = assertIs<EnlistmentExecution.Applied>(EnlistmentExecutor(world, ChangeRecorder()) { policy() }
                .execute(EnlistmentRequest(1, EnlistmentMode.RANDOM)) { bound ->
                    assertEquals(2, bound)
                    draws++
                    1
                })
            return result.plan.masterId to draws
        }
        assertEquals(20 to 1, run())
        assertEquals(run(), run())
    }

    @Test fun `missing or ambiguous sovereign office cannot select an arbitrary general`() {
        for (duplicate in listOf(false, true)) {
            val world = world()
            if (duplicate) world.applyGeneralDirtyFree(world.getGeneralById(2)!!.copy(nationId = 1, officerLevel = 12))
            else world.applyGeneralDirtyFree(world.getGeneralById(10)!!.copy(officerLevel = 1))
            val recorder = ChangeRecorder()
            assertEquals(EnlistmentFailure.TARGET_NOT_FOUND,
                assertIs<EnlistmentExecution.Rejected>(EnlistmentExecutor(world, recorder) { policy() }
                    .execute(request, noDraw)).reason)
            assertTrue(world.listRetainers().isEmpty())
            assertTrue(recorder.generalPatches().isEmpty())
        }
    }

    @Test fun `explicit vassal lord can accept general mode without sovereign office`() {
        val world = world()
        world.applyGeneralDirtyFree(world.getGeneralById(10)!!.copy(officerLevel = 1))
        val result = assertIs<EnlistmentExecution.Applied>(EnlistmentExecutor(world, ChangeRecorder()) { policy() }
            .execute(EnlistmentRequest(1, EnlistmentMode.GENERAL, 10), noDraw))
        assertEquals(10, result.plan.masterId)
    }

}

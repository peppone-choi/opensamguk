package opensamguk.engine.hwiha

import java.time.Instant
import kotlin.test.*
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef

class HwihaEnlistmentHandlerTest {
    private fun person(id: Int, nation: Int = 0) = TurnGeneral(
        id = id, name = "G$id", nationId = nation, cityId = 10, troopId = 0,
        stats = GeneralStats(70, 70, 70, politics = 70, charm = 70),
        experience = 0, dedication = 0, officerLevel = if (nation > 0) 12 else 0,
        npcState = 2, userId = null, gold = 100, rice = 200, crew = 0, turnTime = Instant.EPOCH,
        meta = mapOf("hwihaLord" to (nation > 0), PersonPolicyState.META_KEY to
            PersonPolicyState(30, true, "synthetic-test", "v1", id).toMetaValue()),
    )
    private fun world(profile: String = "HWIHA", reverse: Boolean = false): InMemoryTurnWorld {
        val people = listOf(person(1), person(10, 1), person(20, 2))
        return InMemoryTurnWorld(WorldSnapshot(
            state = TurnWorldState(1, 200, 1, 3600, Instant.EPOCH, config = mapOf("mapName" to "han-world-v3", "ruleProfile" to profile)),
            worldId = WorldId(1), generals = if (reverse) people.reversed() else people,
            nations = listOf(Nation(2, "N2", "#fff"), Nation(1, "N1", "#000")),
            generalPositionSnapshot = people.fold(GeneralPositionSnapshot("r1", "a".repeat(64), setOf("p1"), emptySet())) { positions, person ->
                positions.withState(GeneralPositionState("r1", "a".repeat(64), person.id, StrategicNodeRef.LandProvince("p1"), 1))
            },
            cityLandProvinceById = mapOf(10 to "p1"),
        ))
    }
    @Test fun `direct success and repeated rejection never instantiate RNG`() {
        val world = world()
        val handler = HwihaEnlistmentHandler(world, ChangeRecorder(), "fixture") { error("unexpected RNG") }
        val args = """{"mode":"NATION","targetId":1}"""
        assertEquals(HwihaTurnOutcome.Applied("action.enlist"), handler.handle(1, args, 200, 1))
        assertEquals(1, world.getGeneralById(1)!!.nationId)
        assertEquals(10, world.listRetainers().single().masterGeneralId)
        val rejected = assertIs<HwihaTurnOutcome.Rejected>(handler.handle(1, args, 200, 1))
        assertEquals(EnlistmentFailure.ALREADY_SERVING.name, rejected.code)
        assertEquals(EnlistmentFailure.ALREADY_SERVING.message, rejected.reason)
        assertEquals(1, world.listRetainers().size)
    }
    @Test fun `malformed and wrong profile inputs reject before state mutation or RNG`() {
        for ((profile, args, failure) in listOf(
            Triple("HWIHA", """{"mode":"RANDOM","actorId":10}""", EnlistmentFailure.INVALID_REQUEST),
            Triple("SAMMO", """{"mode":"RANDOM"}""", EnlistmentFailure.WRONG_RULE_PROFILE))) {
            val world = world(profile)
            val before = world.listGenerals()
            val recorder = ChangeRecorder()
            val result = HwihaEnlistmentHandler(world, recorder, "fixture") { error("unexpected RNG") }
                .handle(1, args, 200, 1)
            assertEquals(failure.name, assertIs<HwihaTurnOutcome.Rejected>(result).code)
            assertEquals(before, world.listGenerals())
            assertTrue(world.listRetainers().isEmpty())
            assertTrue(recorder.generalPatches().isEmpty())
        }
    }
    @Test fun `random uses scoped seed and exclusive bound independent of insertion order`() {
        for (reverse in listOf(false, true)) {
            val world = world(reverse = reverse)
            val seeds = mutableListOf<String>()
            val bounds = mutableListOf<Pair<Int, Int>>()
            val handler = HwihaEnlistmentHandler(world, ChangeRecorder(), "fixture") { seed ->
                seeds += seed
                object : RandUtil(LiteHashDrbg(seed)) {
                    override fun nextInt(minInclusive: Int, maxExclusive: Int): Int {
                        bounds += minInclusive to maxExclusive
                        return maxExclusive - 1
                    }
                }
            }
            assertIs<HwihaTurnOutcome.Applied>(handler.handle(1, """{"mode":"RANDOM"}""", 200, 1))
            assertEquals(listOf(0 to 2), bounds)
            assertEquals(listOf(world.personalTurnSeed("fixture", "generalCommand", 200, 1, 1, "action.enlist")), seeds)
            assertEquals(20, world.listRetainers().single().masterGeneralId)
        }
    }
    @Test fun `random zero or single candidate never instantiates RNG and stale capacity rejects`() {
        for (candidateCount in 0..1) {
            val world = world()
            for (id in if (candidateCount == 0) listOf(10, 20) else listOf(20)) {
                val lord = world.getGeneralById(id)!!
                world.applyGeneralDirtyFree(lord.copy(meta = lord.meta + (PersonPolicyState.META_KEY to
                    PersonPolicyState(0, true, "synthetic-test", "v1", id).toMetaValue())))
            }
            val result = HwihaEnlistmentHandler(world, ChangeRecorder(), "fixture") { error("unexpected RNG") }
                .handle(1, """{"mode":"RANDOM"}""", 200, 1)
            if (candidateCount == 0) {
                assertEquals(EnlistmentFailure.NO_ELIGIBLE_NATION.name, assertIs<HwihaTurnOutcome.Rejected>(result).code)
                assertTrue(world.listRetainers().isEmpty())
            } else {
                assertIs<HwihaTurnOutcome.Applied>(result)
                assertEquals(10, world.listRetainers().single().masterGeneralId)
            }
        }
        val world = world()
        val recorder = ChangeRecorder()
        val handler = HwihaEnlistmentHandler(world, recorder, "fixture") { error("unexpected RNG") }
        val lord = world.getGeneralById(10)!!
        world.applyGeneralDirtyFree(lord.copy(meta = lord.meta + (PersonPolicyState.META_KEY to
            PersonPolicyState(6, true, "synthetic-test", "v1", 10).toMetaValue())))
        val result = handler.handle(1, """{"mode":"NATION","targetId":1}""", 200, 1)
        assertEquals(EnlistmentFailure.INSUFFICIENT_RENOWN.name, assertIs<HwihaTurnOutcome.Rejected>(result).code)
        assertEquals(0, world.getGeneralById(1)!!.nationId)
        assertTrue(recorder.generalPatches().isEmpty())
        assertTrue(world.listRetainers().isEmpty())
    }
    @Test fun `identical seed input and snapshot reproduce chosen lord`() {
        fun run(): Pair<List<TurnGeneral>, List<Retainer>> {
            val world = world()
            assertIs<HwihaTurnOutcome.Applied>(HwihaEnlistmentHandler(world, ChangeRecorder(), "fixed")
                .handle(1, """{"mode":"RANDOM"}""", 200, 1))
            return world.listGenerals() to world.listRetainers()
        }
        assertEquals(run(), run())
    }
}

package opensamguk.engine.hwiha

import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.input.*
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef

class HwihaNpcEnlistmentSelectorTest {
    private val missing = ReservedTurn("휴식", "{}", rowExists = false)
    private fun person(id: Int, nation: Int = 0) = TurnGeneral(
        id = id, name = "G$id", nationId = nation, cityId = 1, troopId = 0,
        stats = GeneralStats(70, 70, 70, politics = 70, charm = 70),
        experience = 0, dedication = 0, officerLevel = if (nation > 0) 12 else 0,
        npcState = 2, userId = null, gold = 100, rice = 200, crew = 0, turnTime = Instant.EPOCH,
        meta = mapOf("hwihaLord" to (nation > 0), HwihaPersonPolicyState.META_KEY to
            HwihaPersonPolicyState(30, true, "synthetic-test", "v1", id).toMetaValue()))
    private fun world(actor: TurnGeneral = person(1), lords: Int = 1, reverse: Boolean = false): InMemoryTurnWorld {
        val persons = listOf(actor) + (1..lords).map { person(it * 10, it).copy(turnTime = Instant.EPOCH.plusSeconds(99999)) }
        return InMemoryTurnWorld(WorldSnapshot(
            state = TurnWorldState(1, 200, 1, 3600, Instant.EPOCH, config = mapOf("mapName" to "han-world-v3", "ruleProfile" to "HWIHA")),
            worldId = WorldId(1), generals = if (reverse) persons.reversed() else persons,
            nations = (1..lords).map { Nation(it, "N$it", "#000") },
            generalPositionSnapshot = persons.fold(GeneralPositionSnapshot("r1", "a".repeat(64), setOf("p1"), emptySet())) { positions, person ->
                positions.withState(GeneralPositionState("r1", "a".repeat(64), person.id, StrategicNodeRef.LandProvince("p1"), 1))
            }, cityLandProvinceById = mapOf(1 to "p1")))
    }
    @Test fun `explicit rows without request ids never become AI inputs`() {
        for (row in listOf(ReservedTurn("휴식", "{}"), ReservedTurn("action.enlist", "{}"))) {
            assertSame(row, HwihaNpcEnlistmentSelector.select(world(), 1, row))
        }
        val chosen = HwihaNpcEnlistmentSelector.select(world(), 1, missing)
        assertEquals("action.enlist", chosen.actionCode)
        assertNull(chosen.requestId)
        assertFalse(chosen.rowExists)
    }
    @Test fun `humans special NPCs lords and absent lord declaration are untouched`() {
        val actors = listOf(0, 1, 3, 4, 5, 6, 9).map { person(1).copy(npcState = it) } +
            listOf("42", "opaque-owner").map { person(1).copy(userId = it) } +
            listOf(person(1).copy(meta = person(1).meta + ("hwihaLord" to true)),
                person(1).copy(meta = person(1).meta - "hwihaLord"), person(1, 1))
        for (actor in actors) assertSame(missing, HwihaNpcEnlistmentSelector.select(world(actor), 1, missing))
        for (owner in listOf(null, "", "0", "-1"))
            assertEquals("action.enlist", HwihaNpcEnlistmentSelector.select(world(person(1).copy(userId = owner)), 1, missing).actionCode)
    }
    @Test fun `bound NPC and no eligible nation are untouched`() {
        val bound = world()
        bound.createRetainer(Retainer(id = 1, masterGeneralId = 10, generalId = 1, name = "G1", origin = "EXISTING", relation = "guest"))
        assertSame(missing, HwihaNpcEnlistmentSelector.select(bound, 1, missing))
        assertSame(missing, HwihaNpcEnlistmentSelector.select(world(lords = 0), 1, missing))
    }
    @Test fun `one candidate consumes no RNG and overdue lifecycle selects only once per phase`() {
        val world = world()
        val handler = ReservedTurnHandler(world, CommandRegistry(GeneralActionPipeline()), "fixture", 200,
            actionRngFactory = { error("single candidate must not consume RNG") })
        var reads = 0
        var pulls = 0
        val lifecycle = TurnDaemonLifecycle(world, handler, pullGeneralTurnOf = { pulls++ },
            reservedActionOf = { reads++; missing })
        val first = lifecycle.runTick(Instant.EPOCH.plusSeconds(10801)).single()
        assertIs<HwihaTurnOutcome.Applied>(first.hwihaOutcome)
        assertNull(first.requestId)
        assertEquals(1, world.getGeneralById(1)!!.nationId)
        assertTrue(lifecycle.runTick(Instant.EPOCH.plusSeconds(10801)).isEmpty())
        assertEquals(1, reads)
        assertEquals(1, pulls)
    }
    @Test fun `multiple candidates preserve deterministic existing handler choice`() {
        fun run(reverse: Boolean): Int {
            val world = world(lords = 2, reverse = reverse)
            val selected = HwihaNpcEnlistmentSelector.select(world, 1, missing)
            assertIs<HwihaTurnOutcome.Applied>(HwihaEnlistmentHandler(world, ChangeRecorder(), "fixed")
                .handle(1, selected.argJson, 200, 1))
            return world.getGeneralById(1)!!.nationId
        }
        assertEquals(run(false), run(true))
    }
    @Test fun `runtime recorder preserves sparse inputs and rejects invalid creation before mutation`() {
        val valid = GeneralTurnSeed("action.enlist", """{"mode":"RANDOM"}""", "출사")
        for (turns in listOf(emptyList(), listOf(valid), List(12) { valid })) {
            val world = world()
            val created = ChangeRecorder().recordGeneralCreate(world, person(99), turns)
            assertEquals(turns, created.initialTurns)
            assertNotNull(world.generalPositionSnapshot()!!.stateFor(99))
        }
        for (turns in listOf(List(13) { valid }, listOf(GeneralTurnSeed("휴식", "{}", "휴식")),
            listOf(GeneralTurnSeed("action.enlist", """{"mode":"RANDOM","targetId":1}""", "출사")))) {
            val world = world()
            assertFailsWith<IllegalArgumentException> { ChangeRecorder().recordGeneralCreate(world, person(99), turns) }
            assertNull(world.getGeneralById(99))
            assertNull(world.generalPositionSnapshot()!!.stateFor(99))
        }
    }

}

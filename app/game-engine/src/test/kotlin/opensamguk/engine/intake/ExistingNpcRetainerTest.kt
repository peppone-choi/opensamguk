package opensamguk.engine.intake

import opensamguk.common.wire.GeneralBoolResult
import opensamguk.common.wire.RetainerActionResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.world.WorldId
import opensamguk.engine.retainer.RetainerMonthlyService
import opensamguk.engine.turn.*
import opensamguk.logic.retainer.RetainerRules
import java.time.Instant
import kotlin.test.*

class ExistingNpcRetainerTest {
    private val now = Instant.parse("0200-01-01T00:00:00Z")
    private fun general(id: Int, npc: Int = 2, nation: Int = 1) = TurnGeneral(
        id = id, name = "장수$id", nationId = nation, cityId = 1, troopId = 0,
        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0, officerLevel = 5,
        npcState = npc, gold = 5000, rice = 5000, crew = 400, turnTime = now,
    )
    private fun world(candidates: List<TurnGeneral> = listOf(general(20), general(21), general(22, nation = 0)), seed: String = "retainer-test") = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = now, meta = mapOf("hiddenSeed" to seed)),
            worldId = WorldId(1), generals = listOf(general(10, 0), general(11, 0)) + candidates,
            nations = listOf(Nation(id = 1, name = "촉", color = "#000", gold = 0)),
        ),
    )
    private fun pledge(world: InMemoryTurnWorld, target: Int? = 20, random: Boolean = false, master: Int = 10) =
        RetainerHandler(world, ChangeRecorder(), { now }).handlePledge(
            TurnDaemonCommand.RetainerPledge(generalId = master, targetGeneralId = target, random = random, relation = "lieutenant"),
        ) as RetainerActionResult

    @Test fun `pledge preserves actual character and its army and AI`() {
        val world = world()
        val before = world.getGeneralById(20)!!
        val result = pledge(world)
        assertTrue(result.ok)
        val relation = world.getRetainerById(result.id!!)!!
        assertEquals(20, relation.generalId)
        assertEquals("EXISTING", relation.origin)
        assertTrue(relation.hasOwnBugok)
        assertEquals("MUTUAL", relation.releasePolicy)
        assertEquals(before, world.getGeneralById(20))
        assertEquals(5, world.listGenerals().size)
        assertTrue(world.bugoksOf(20).isEmpty())
        val recorder = ChangeRecorder()
        RetainerMonthlyService().settle(world, recorder)
        assertTrue(recorder.generalPatches().isEmpty(), "EXISTING has no recruited upkeep")
    }

    @Test fun `random uses stable pool ordering and deterministic state`() {
        val candidates = listOf(general(20), general(21), general(22, nation = 0))
        fun selected(list: List<TurnGeneral>): Int? {
            val world = world(list)
            val result = pledge(world, target = null, random = true)
            assertTrue(result.ok)
            return world.getRetainerById(result.id!!)!!.generalId
        }
        assertEquals(selected(candidates), selected(candidates.reversed()))
        assertEquals(selected(candidates), selected(candidates))
        assertTrue(selected(candidates) in candidates.map { it.id })
    }

    @Test fun `random uses the world seed and empty pool cannot consume resources or ids`() {
        val selected = (1..20).map { seed ->
            val world = world(seed = "seed-$seed")
            val result = pledge(world, target = null, random = true)
            world.getRetainerById(result.id!!)!!.generalId
        }.toSet()
        assertTrue(selected.size > 1, "random selection must not always choose the first candidate")
        val empty = world(emptyList())
        assertFalse(pledge(empty, target = null, random = true).ok)
        assertEquals(5000, empty.getGeneralById(10)!!.gold)
        assertNull(empty.getState().meta["maxRetainerId"])
    }

    @Test fun `stale or invalid target cannot consume funds or create relation`() {
        val invalid = listOf(general(20, 0), general(20, 1), general(20, 3), general(20, nation = 2),
            general(20).copy(userId = "7"), general(20).copy(userId = "malformed"), general(20).copy(officerLevel = 12))
        for (candidate in invalid) {
            val world = world(listOf(candidate))
            assertFalse(pledge(world).ok, candidate.toString())
            assertEquals(5000, world.getGeneralById(10)!!.gold)
            assertTrue(world.listRetainers().isEmpty())
        }
        val world = world()
        world.removeGeneral(20)
        assertFalse(pledge(world).ok)
        assertFalse(pledge(world, target = 10).ok)
        assertFalse(pledge(world, target = 21, random = true).ok)
    }

    @Test fun `same tick only one master can bind target and release preserves NPC`() {
        val world = world()
        val first = pledge(world)
        assertTrue(first.ok)
        assertFalse(pledge(world, master = 11).ok)
        val npc = world.getGeneralById(20)
        RetainerHandler(world, ChangeRecorder(), { now }).handleRelease(TurnDaemonCommand.RetainerRelease(generalId = 10, retainerId = first.id))
        assertEquals(npc, world.getGeneralById(20))
        assertTrue(pledge(world, master = 11).ok)
    }

    @Test fun `linked NPC death immediately removes relation and clears surviving commander`() {
        val world = world()
        val first = pledge(world)
        world.createBugok(Bugok(id = 1, masterGeneralId = 10, name = "부곡", troops = 100, crewTypeId = 1100, training = 50, morale = 50, fatigue = 0, provisions = 0, commanderRetainerId = first.id))
        world.consumeDirtyState()
        assertTrue(world.removeGeneral(20))
        assertNull(world.getRetainerById(first.id!!))
        assertNull(world.getBugokById(1)!!.commanderRetainerId)
        assertTrue(world.consumeDirtyState().bugoks.any { it.id == 1 })
    }

    @Test fun `bound NPC cannot be possessed and possessed NPC cannot be pledged`() {
        val world = world()
        assertTrue(pledge(world).ok)
        val claim = ClaimNpcHandler(world, ChangeRecorder(), { now }).handle(TurnDaemonCommand.ClaimNpc(generalId = 20, userId = 7, userNick = "유저")) as GeneralBoolResult
        assertFalse(claim.ok)
        assertEquals(2, world.getGeneralById(20)!!.npcState)
        val other = world()
        assertTrue((ClaimNpcHandler(other, ChangeRecorder(), { now }).handle(TurnDaemonCommand.ClaimNpc(generalId = 20, userId = 7, userNick = "유저")) as GeneralBoolResult).ok)
        assertFalse(pledge(other).ok)
    }

    @Test fun `loyalty departure and master death leave independent NPC intact`() {
        val world = world()
        val first = pledge(world)
        world.updateRetainer(world.getRetainerById(first.id!!)!!.copy(loyalty = 0))
        RetainerMonthlyService().settle(world, ChangeRecorder())
        assertTrue(world.listRetainers().isEmpty())
        assertNotNull(world.getGeneralById(20))
        assertTrue(pledge(world).ok)
        world.removeGeneral(10)
        assertTrue(world.listRetainers().isEmpty())
        assertNotNull(world.getGeneralById(20))
    }
}

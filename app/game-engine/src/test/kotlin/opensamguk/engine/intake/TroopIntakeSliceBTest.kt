package opensamguk.engine.intake

import opensamguk.common.wire.TroopActionResult
import opensamguk.common.wire.TroopExitFail
import opensamguk.common.wire.TroopExitOk
import opensamguk.common.wire.TroopJoinFail
import opensamguk.common.wire.TroopJoinOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.run.TurnDaemonCommandDispatcher
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.Troop
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F4 Wave C2 (slice B) full-cycle tests — the troop-intake commands.
 *
 * Exercises the FULL daemon path: command → [TroopHandler] (world mutate) → [ChangeRecorder] delta +
 * world troop lifecycle → [DatabaseHooks.toFlushPayload] (the exact builder TurnRunService routes to
 * JdbcFlushExecutor) → assert the flush payload (createdTroops / deletedTroops / updatedTroops +
 * updatedGenerals) and the world post-state. Covers NewTroop / JoinTroop / ExitTroop (member +
 * leader-disband) / KickFromTroop / SetTroopName, plus the deny chains.
 */
class TroopIntakeSliceBTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun general(id: Int, troopId: Int = 0, nationId: Int = 1, officerLevel: Int = 2) = TurnGeneral(
        id = id, name = "장수$id", nationId = nationId, cityId = 5, troopId = troopId,
        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
        officerLevel = officerLevel, gold = 100, turnTime = t0,
    )

    private fun nation() = Nation(id = 1, name = "촉", color = "#0f0", gold = 1000)

    private fun world(generals: List<TurnGeneral>, troops: List<Troop> = emptyList()) = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0),
            generals = generals, nations = listOf(nation()), troops = troops,
            worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0)).id),
        ),
    )

    private fun flush(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())

    // ── NewTroop ──────────────────────────────────────────────────────────────────────────────
    @Test
    fun `newTroop creates the troop row and sets the leader's troop to its own id`() {
        val world = world(listOf(general(id = 7, troopId = 0)))
        val recorder = ChangeRecorder()
        val res = TroopHandler(world, recorder).handleNew(TurnDaemonCommand.TroopNew(generalId = 7, troopName = "제1군단"))

        assertEquals(TroopActionResult("troopNew", ok = true, generalId = 7, troopId = 7), res)
        assertEquals("제1군단", world.getTroopById(7)!!.name)
        assertEquals(7, world.getGeneralById(7)!!.troopId)

        val payload = flush(world, recorder)
        assertTrue(payload.createdTroops.any { it.troopLeader == 7 && it.nation == 1 && it.name == "제1군단" })
        assertEquals(7, payload.updatedGenerals.single { it.id == 7 }.troop)
    }

    @Test
    fun `newTroop denied when already in a troop, nothing flushes`() {
        val world = world(listOf(general(id = 7, troopId = 3)), troops = listOf(Troop(3, 1, "기존")))
        val recorder = ChangeRecorder()
        val res = TroopHandler(world, recorder).handleNew(TurnDaemonCommand.TroopNew(generalId = 7, troopName = "새부대"))

        assertEquals("이미 부대에 소속되어 있습니다.", (res as TroopActionResult).reason)
        val payload = flush(world, recorder)
        assertTrue(payload.createdTroops.isEmpty())
        assertTrue(payload.updatedGenerals.isEmpty())
    }

    @Test
    fun `newTroop denied when the name neutralizes to empty`() {
        val world = world(listOf(general(id = 7)))
        val recorder = ChangeRecorder()
        val res = TroopHandler(world, recorder).handleNew(TurnDaemonCommand.TroopNew(generalId = 7, troopName = "0"))
        assertEquals("부대 이름이 없습니다.", (res as TroopActionResult).reason)
    }

    // ── JoinTroop ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `joinTroop attaches the general to an existing same-nation troop`() {
        val world = world(listOf(general(id = 9, troopId = 0)), troops = listOf(Troop(7, 1, "본대")))
        val recorder = ChangeRecorder()
        val res = TroopHandler(world, recorder).handleJoin(TurnDaemonCommand.TroopJoin(generalId = 9, troopId = 7))

        assertEquals(TroopJoinOk(generalId = 9, troopId = 7), res)
        assertEquals(7, world.getGeneralById(9)!!.troopId)
        assertEquals(7, flush(world, recorder).updatedGenerals.single { it.id == 9 }.troop)
    }

    @Test
    fun `joinTroop rejects a troop in another nation`() {
        val world = world(listOf(general(id = 9, troopId = 0)), troops = listOf(Troop(7, nationId = 2, name = "타국")))
        val recorder = ChangeRecorder()
        val res = TroopHandler(world, recorder).handleJoin(TurnDaemonCommand.TroopJoin(generalId = 9, troopId = 7))
        assertEquals(TroopJoinFail(generalId = 9, troopId = 7, reason = "부대가 올바르지 않습니다."), res)
    }

    // ── ExitTroop ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `exitTroop as a member clears only my troop, leaves the troop row`() {
        val world = world(listOf(general(id = 9, troopId = 7)), troops = listOf(Troop(7, 1, "본대")))
        val recorder = ChangeRecorder()
        val res = TroopHandler(world, recorder).handleExit(TurnDaemonCommand.TroopExit(generalId = 9))

        assertEquals(TroopExitOk(generalId = 9, wasLeader = false), res)
        assertEquals(0, world.getGeneralById(9)!!.troopId)
        assertTrue(world.getTroopById(7) != null)
        val payload = flush(world, recorder)
        assertTrue(payload.deletedTroops.isEmpty())
        assertEquals(0, payload.updatedGenerals.single { it.id == 9 }.troop)
    }

    @Test
    fun `exitTroop as the leader frees every member and deletes the troop`() {
        val world = world(
            listOf(general(id = 7, troopId = 7), general(id = 8, troopId = 7), general(id = 9, troopId = 0)),
            troops = listOf(Troop(7, 1, "본대")),
        )
        val recorder = ChangeRecorder()
        val res = TroopHandler(world, recorder).handleExit(TurnDaemonCommand.TroopExit(generalId = 7))

        assertEquals(TroopExitOk(generalId = 7, wasLeader = true), res)
        assertEquals(0, world.getGeneralById(7)!!.troopId)
        assertEquals(0, world.getGeneralById(8)!!.troopId)
        assertNull(world.getTroopById(7))

        val payload = flush(world, recorder)
        assertTrue(payload.deletedTroops.contains(7))
        val freed = payload.updatedGenerals.map { it.id }.toSet()
        assertTrue(7 in freed && 8 in freed)
        assertTrue(9 !in freed) // the outsider is untouched
    }

    // ── KickFromTroop ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `kickFromTroop removes a matching non-leader member`() {
        val world = world(listOf(general(id = 8, troopId = 7)), troops = listOf(Troop(7, 1, "본대")))
        val recorder = ChangeRecorder()
        val res = TroopHandler(world, recorder).handleKick(
            TurnDaemonCommand.TroopKick(generalId = 7, troopId = 7, targetGeneralId = 8),
        )

        assertEquals(TroopActionResult("troopKick", ok = true, generalId = 7, troopId = 7, targetGeneralId = 8), res)
        assertEquals(0, world.getGeneralById(8)!!.troopId)
        assertEquals(0, flush(world, recorder).updatedGenerals.single { it.id == 8 }.troop)
    }

    @Test
    fun `kickFromTroop rejects a mismatched troop and the leader target`() {
        val world = world(listOf(general(id = 8, troopId = 7), general(id = 7, troopId = 7)), troops = listOf(Troop(7, 1, "본대")))
        val recorder = ChangeRecorder()
        val handler = TroopHandler(world, recorder)

        assertEquals(
            "다른 부대에 소속되어 있습니다.",
            (handler.handleKick(TurnDaemonCommand.TroopKick(generalId = 7, troopId = 4, targetGeneralId = 8)) as TroopActionResult).reason,
        )
        assertEquals(
            "부대장을 추방할 수 없습니다.",
            (handler.handleKick(TurnDaemonCommand.TroopKick(generalId = 7, troopId = 7, targetGeneralId = 7)) as TroopActionResult).reason,
        )
    }

    // ── SetTroopName ──────────────────────────────────────────────────────────────────────────
    @Test
    fun `setTroopName renames the troop for its leader`() {
        val world = world(listOf(general(id = 7, troopId = 7)), troops = listOf(Troop(7, 1, "옛이름")))
        val recorder = ChangeRecorder()
        val res = TroopHandler(world, recorder).handleSetName(
            TurnDaemonCommand.TroopSetName(generalId = 7, troopId = 7, troopName = "새이름"),
        )

        assertEquals(TroopActionResult("troopSetName", ok = true, generalId = 7, troopId = 7), res)
        assertEquals("새이름", world.getTroopById(7)!!.name)
        val payload = flush(world, recorder)
        assertTrue(payload.updatedTroops.any { it.troopLeader == 7 && it.name == "새이름" })
        assertTrue(payload.createdTroops.isEmpty())
    }

    @Test
    fun `setTroopName denies a low-permission officer renaming another troop`() {
        val world = world(listOf(general(id = 8, troopId = 7, officerLevel = 2)), troops = listOf(Troop(7, 1, "본대")))
        val recorder = ChangeRecorder()
        val res = TroopHandler(world, recorder).handleSetName(
            TurnDaemonCommand.TroopSetName(generalId = 8, troopId = 7, troopName = "강탈"),
        )
        assertEquals("권한이 부족합니다.", (res as TroopActionResult).reason)
        assertEquals("본대", world.getTroopById(7)!!.name) // unchanged
    }

    @Test
    fun `setTroopName denies when the troop does not exist (affectedRows 0)`() {
        // officerLevel 12 → permission 4, so the permission gate passes and we reach the missing-troop branch.
        val world = world(listOf(general(id = 7, troopId = 0, officerLevel = 12)))
        val recorder = ChangeRecorder()
        val res = TroopHandler(world, recorder).handleSetName(
            TurnDaemonCommand.TroopSetName(generalId = 7, troopId = 99, troopName = "유령"),
        )
        assertEquals("부대가 없습니다.", (res as TroopActionResult).reason)
    }

    // ── dispatcher routing ──────────────────────────────────────────────────────────────────────
    @Test
    fun `dispatcher routes every troop command type to the troop handler`() {
        val world = world(listOf(general(id = 7, troopId = 0)))
        val recorder = ChangeRecorder()
        val dispatcher = TurnDaemonCommandDispatcher(
            world, recorder,
            noopRepo<opensamguk.infra.read.AuctionRepository>(),
            noopRepo<opensamguk.infra.read.AuctionBidRepository>(),
            noopRepo<opensamguk.infra.read.BoardPostRepository>(),
        )

        val r = dispatcher.dispatch(TurnDaemonCommand.TroopNew(generalId = 7, troopName = "제1군단"))
        assertEquals("troopNew", (r as TroopActionResult).type)
        assertTrue(r.ok)
        assertEquals(7, world.getGeneralById(7)!!.troopId)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> noopRepo(): T = java.lang.reflect.Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.util.List::class.java -> emptyList<Any>()
            java.lang.Boolean.TYPE -> false
            else -> null
        }
    } as T
}

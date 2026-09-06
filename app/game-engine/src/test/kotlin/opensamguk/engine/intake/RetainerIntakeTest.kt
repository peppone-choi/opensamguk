package opensamguk.engine.intake

import opensamguk.common.wire.RetainerActionResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.retainer.RetainerMonthlyService
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.retainer.RetainerRules
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4X-A spec v3 §8 `RetainerIntakeTest` — 6 명령이 기록하는 것은 (a) 주인 장수의 gold/crew/rice **패치**
 * (`recorder.generalPatches()`, 메모리 값이 아니라 — F1) 와 (b) world DirtyState 의 created/updated/deleted 다.
 * 같은 틱 시나리오 5종과 「만들고 같은 틱에 지우면 DB 작업 0」 을 함께 핀한다.
 */
class RetainerIntakeTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun general(id: Int = 10, gold: Int = 2000, rice: Int = 3000, crew: Int = 1000, crewTypeId: Int = 1100) = TurnGeneral(
        id = id, name = "유비", nationId = 1, cityId = 5, troopId = 0,
        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
        officerLevel = 5, gold = gold, rice = rice, crew = crew, crewTypeId = crewTypeId, train = 70, atmos = 60,
        turnTime = t0, role = GeneralRole(),
    )

    private fun state() = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0)

    private fun world(vararg generals: TurnGeneral): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = state(),
            generals = generals.toList().ifEmpty { listOf(general()) },
            nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", gold = 1000)),
            worldId = opensamguk.common.world.WorldId(1),
        ),
    )

    private fun flush(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())

    private fun patchColumns(recorder: ChangeRecorder, generalId: Int): Set<String> =
        recorder.generalPatches().first { it.id == generalId }.columns.keys

    @Test
    fun `pledge deducts gold through the recorder patch and creates a RECRUITED retainer with an allocated id`() {
        val world = world(); val recorder = ChangeRecorder(); val h = RetainerHandler(world, recorder)
        val res = h.handlePledge(TurnDaemonCommand.RetainerPledge(generalId = 10, name = " 홍길동 ", relation = "lieutenant", role = "GUARD")) as RetainerActionResult
        assertTrue(res.ok); assertNotNull(res.id)
        assertEquals(2000 - RetainerRules.PLEDGE_COST_GOLD, world.getGeneralById(10)!!.gold)
        assertTrue("gold" in patchColumns(recorder, 10), "gold 는 recorder 패치로 기록돼야 한다(F1)")
        val r = world.getRetainerById(res.id!!)!!
        assertEquals("홍길동", r.name); assertEquals(RetainerRules.ORIGIN_RECRUITED, r.origin); assertEquals(50, r.loyalty)
        val payload = flush(world, recorder)
        assertEquals(1, payload.createdRetainers.size); assertEquals(0, payload.updatedRetainers.size)
        assertEquals(res.id, payload.worldStateUpdate["max_retainer_id"])
        assertEquals(null, payload.worldStateUpdate["max_bugok_id"], "부곡이 없으면 meta 키를 싣지 않는다")
    }

    @Test
    fun `pledge gates are ordered input then full then duplicate then gold`() {
        val world = world(general(gold = 10)); val recorder = ChangeRecorder(); val h = RetainerHandler(world, recorder)
        assertEquals(RetainerRules.REASON_INPUT, (h.handlePledge(TurnDaemonCommand.RetainerPledge(generalId = 10, name = "x", relation = "lieutenant")) as RetainerActionResult).reason)
        assertEquals(RetainerRules.REASON_INPUT, (h.handlePledge(TurnDaemonCommand.RetainerPledge(generalId = 10, name = "홍길동", relation = "boss")) as RetainerActionResult).reason)
        assertEquals(RetainerRules.REASON_NO_GOLD, (h.handlePledge(TurnDaemonCommand.RetainerPledge(generalId = 10, name = "홍길동", relation = "guest")) as RetainerActionResult).reason)
        assertEquals("장수가 존재하지 않습니다.", (h.handlePledge(TurnDaemonCommand.RetainerPledge(generalId = 99, name = "홍길동", relation = "guest")) as RetainerActionResult).reason)
    }

    @Test
    fun `release then pledge the same name in one tick works in memory and yields DELETE plus CREATE`() {
        val world = world(); val recorder = ChangeRecorder(); val h = RetainerHandler(world, recorder)
        val first = h.handlePledge(TurnDaemonCommand.RetainerPledge(generalId = 10, name = "홍길동", relation = "guest")) as RetainerActionResult
        world.consumeDirtyState() // 이전 틱에 flush 됐다고 치자
        assertTrue((h.handleRelease(TurnDaemonCommand.RetainerRelease(generalId = 10, retainerId = first.id)) as RetainerActionResult).ok)
        val second = h.handlePledge(TurnDaemonCommand.RetainerPledge(generalId = 10, name = "홍길동", relation = "guest")) as RetainerActionResult
        assertTrue(second.ok); assertTrue(second.id!! > first.id!!, "삭제된 최대 id 재사용 없음")
        val payload = flush(world, recorder)
        assertEquals(listOf(first.id), payload.deletedRetainerIds)
        assertEquals(listOf(second.id), payload.createdRetainers.map { it.id })
    }

    @Test
    fun `create then remove in the same tick emits no DB work`() {
        val world = world(); val recorder = ChangeRecorder(); val h = RetainerHandler(world, recorder)
        val r = h.handlePledge(TurnDaemonCommand.RetainerPledge(generalId = 10, name = "홍길동", relation = "guest")) as RetainerActionResult
        h.handleRelease(TurnDaemonCommand.RetainerRelease(generalId = 10, retainerId = r.id))
        val payload = flush(world, recorder)
        assertTrue(payload.createdRetainers.isEmpty()); assertTrue(payload.updatedRetainers.isEmpty()); assertTrue(payload.deletedRetainerIds.isEmpty())
    }

    @Test
    fun `bugok form moves crew and rice through the recorder and disband returns them with crew type check`() {
        val world = world(); val recorder = ChangeRecorder(); val h = RetainerHandler(world, recorder)
        assertEquals(RetainerRules.REASON_INPUT, (h.handleBugokForm(TurnDaemonCommand.BugokForm(generalId = 10, troops = 50, rice = 0)) as RetainerActionResult).reason)
        assertEquals(RetainerRules.REASON_NO_TROOPS, (h.handleBugokForm(TurnDaemonCommand.BugokForm(generalId = 10, troops = 1001, rice = 0)) as RetainerActionResult).reason)
        assertEquals(RetainerRules.REASON_NO_RICE, (h.handleBugokForm(TurnDaemonCommand.BugokForm(generalId = 10, troops = 300, rice = 3001)) as RetainerActionResult).reason)
        val res = h.handleBugokForm(TurnDaemonCommand.BugokForm(generalId = 10, troops = 300, rice = 900)) as RetainerActionResult
        assertTrue(res.ok)
        val me = world.getGeneralById(10)!!
        assertEquals(700, me.crew); assertEquals(2100, me.rice)
        assertTrue(setOf("crew", "rice").all { it in patchColumns(recorder, 10) }, "crew/rice 는 recorder 패치(F1)")
        val b = world.getBugokById(res.id!!)!!
        assertEquals(300, b.troops); assertEquals(900, b.provisions); assertEquals(1100, b.crewTypeId); assertEquals(70, b.training); assertEquals(60, b.morale)
        assertEquals("부곡 1", b.name)
        // 합 보존: crew + troops == 1000
        assertEquals(1000, me.crew + b.troops)
        // 두 번째 편성 → 「부곡 2」, 세 번째 → 상한
        val second = h.handleBugokForm(TurnDaemonCommand.BugokForm(generalId = 10, troops = 100, rice = 0)) as RetainerActionResult
        assertEquals("부곡 2", world.getBugokById(second.id!!)!!.name)
        assertEquals(RetainerRules.REASON_BUGOK_FULL, (h.handleBugokForm(TurnDaemonCommand.BugokForm(generalId = 10, troops = 100, rice = 0)) as RetainerActionResult).reason)
        // 병종이 다르면 해산 거부
        val changed = world.getGeneralById(10)!!.copy(crewTypeId = 1200)
        world.applyGeneralDirtyFree(changed)
        assertEquals(RetainerRules.REASON_CREW_TYPE, (h.handleBugokDisband(TurnDaemonCommand.BugokDisband(generalId = 10, bugokId = res.id)) as RetainerActionResult).reason)
        world.applyGeneralDirtyFree(changed.copy(crewTypeId = 1100))
        assertTrue((h.handleBugokDisband(TurnDaemonCommand.BugokDisband(generalId = 10, bugokId = res.id)) as RetainerActionResult).ok)
        val after = world.getGeneralById(10)!!
        assertEquals(900, after.crew); assertEquals(3000, after.rice); assertEquals(70, after.train, "해산은 train/atmos 를 바꾸지 않는다")
        assertNull(world.getBugokById(res.id!!))
    }

    @Test
    fun `assign commander requires an owned lieutenant, adds morale once, and release clears the commander`() {
        val world = world(); val recorder = ChangeRecorder(); val h = RetainerHandler(world, recorder)
        val bugok = h.handleBugokForm(TurnDaemonCommand.BugokForm(generalId = 10, troops = 300, rice = 0)) as RetainerActionResult
        val staff = h.handlePledge(TurnDaemonCommand.RetainerPledge(generalId = 10, name = "막료갑", relation = "staff")) as RetainerActionResult
        val lieutenant = h.handlePledge(TurnDaemonCommand.RetainerPledge(generalId = 10, name = "부장을", relation = "lieutenant")) as RetainerActionResult
        assertEquals(RetainerRules.REASON_NOT_LIEUTENANT, (h.handleBugokAssignCommander(TurnDaemonCommand.BugokAssignCommander(generalId = 10, bugokId = bugok.id, retainerId = staff.id)) as RetainerActionResult).reason)
        assertEquals(RetainerRules.REASON_NO_RETAINER, (h.handleBugokAssignCommander(TurnDaemonCommand.BugokAssignCommander(generalId = 10, bugokId = bugok.id, retainerId = 999)) as RetainerActionResult).reason)
        assertTrue((h.handleBugokAssignCommander(TurnDaemonCommand.BugokAssignCommander(generalId = 10, bugokId = bugok.id, retainerId = lieutenant.id)) as RetainerActionResult).ok)
        assertEquals(60 + RetainerRules.COMMANDER_MORALE_BONUS, world.getBugokById(bugok.id!!)!!.morale)
        // 같은 배정을 반복해도 +6 이 또 붙지 않는다
        h.handleBugokAssignCommander(TurnDaemonCommand.BugokAssignCommander(generalId = 10, bugokId = bugok.id, retainerId = lieutenant.id))
        assertEquals(66, world.getBugokById(bugok.id!!)!!.morale)
        // 부장 해제 → 부곡 commander NULL
        h.handleRelease(TurnDaemonCommand.RetainerRelease(generalId = 10, retainerId = lieutenant.id))
        assertNull(world.getBugokById(bugok.id!!)!!.commanderRetainerId)
        // 같은 틱 flush: 부곡은 created(이번 틱) 라 updated 에 없고, 가신은 created-then-removed 라 어디에도 없다
        val payload = flush(world, recorder)
        assertEquals(listOf(bugok.id), payload.createdBugoks.map { it.id }); assertTrue(payload.updatedBugoks.isEmpty())
        assertEquals(listOf(staff.id), payload.createdRetainers.map { it.id }); assertTrue(payload.deletedRetainerIds.isEmpty())
    }

    @Test
    fun `assign plus settle in one tick keeps the morale bonus`() {
        val world = world(); val recorder = ChangeRecorder(); val h = RetainerHandler(world, recorder)
        val bugok = h.handleBugokForm(TurnDaemonCommand.BugokForm(generalId = 10, troops = 300, rice = 0)) as RetainerActionResult
        val lieutenant = h.handlePledge(TurnDaemonCommand.RetainerPledge(generalId = 10, name = "부장을", relation = "lieutenant")) as RetainerActionResult
        h.handleBugokAssignCommander(TurnDaemonCommand.BugokAssignCommander(generalId = 10, bugokId = bugok.id, retainerId = lieutenant.id))
        RetainerMonthlyService().settle(world, recorder)
        // 군량 0 → 부족 −5 이 +6 뒤에 적용: 66 − 5 = 61(유실 없음)
        assertEquals(61, world.getBugokById(bugok.id!!)!!.morale)
    }

    @Test
    fun `owner death prunes retinue immediately leaving no pending work`() {
        val world = world(); val recorder = ChangeRecorder(); val h = RetainerHandler(world, recorder)
        h.handleBugokForm(TurnDaemonCommand.BugokForm(generalId = 10, troops = 300, rice = 0))
        h.handlePledge(TurnDaemonCommand.RetainerPledge(generalId = 10, name = "홍길동", relation = "guest"))
        world.consumeDirtyState()
        assertTrue(world.removeGeneral(10))
        assertTrue(world.listRetainers().isEmpty()); assertTrue(world.listBugoks().isEmpty())
        val dirty = world.consumeDirtyState()
        assertTrue(dirty.retainers.isEmpty() && dirty.createdRetainers.isEmpty() && dirty.deletedRetainers.isEmpty())
        assertTrue(dirty.bugoks.isEmpty() && dirty.createdBugoks.isEmpty() && dirty.deletedBugoks.isEmpty())
        assertEquals(listOf(10), dirty.deletedGenerals)
        // 정산도 고아 행을 만나지 않는다(새 recorder 로 돌려 이번 정산이 낸 패치만 본다)
        val settleRecorder = ChangeRecorder()
        RetainerMonthlyService().settle(world, settleRecorder)
        assertFalse(settleRecorder.generalPatches().any { it.id == 10 })
        assertTrue(world.consumeDirtyState().let { it.bugoks.isEmpty() && it.retainers.isEmpty() && it.deletedRetainers.isEmpty() })
    }
}

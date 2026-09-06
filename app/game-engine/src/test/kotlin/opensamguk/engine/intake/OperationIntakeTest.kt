package opensamguk.engine.intake

import opensamguk.common.wire.BoardActionResult
import opensamguk.common.wire.OperationActionResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.operation.OperationMonthlyService
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.read.BoardPostRepository
import opensamguk.logic.operation.OperationRules
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * spec v4.1 §8 `OperationIntakeTest` — 4 명령 채널·툼스톤(국가 소멸 → 작전+unit 제거·board operation_id NULL(P2·R4), 장수 삭제 →
 * unit 제거 + 선언자 NULL, 부곡 해산 → unit bugokId NULL)·같은 틱 선언 → 참여 → 글 연결(id 즉시)·join → leave DB 작업 0.
 */
class OperationIntakeTest {

    private val t0 = Instant.parse("0200-03-11T00:00:00Z")

    private fun general(id: Int, nationId: Int = 1, officerLevel: Int = 5, cityId: Int = 1) = TurnGeneral(
        id = id, name = "장수$id", nationId = nationId, cityId = cityId, troopId = 0,
        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0, officerLevel = officerLevel,
        gold = 1000, rice = 1000, crew = 500, crewTypeId = 1100, atmos = 50, turnTime = t0, role = GeneralRole(),
    )

    private fun city(id: Int, nationId: Int, supply: Int = 1) = City(id = id, name = "도시$id", nationId = nationId, level = 5, frontState = 0, supplyState = supply)

    private fun world(vararg generals: TurnGeneral, phase: Int = 2): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, currentPhase = phase, tickSeconds = 3600, lastTurnTime = t0, meta = mapOf("startYear" to 184, "map" to "miniche")),
            generals = generals.toList().ifEmpty { listOf(general(10)) },
            cities = listOf(city(1, 1), city(2, 2), city(3, 0)),
            nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", gold = 1000), Nation(id = 2, name = "위", color = "#00f", gold = 1000)),
            worldId = opensamguk.common.world.WorldId(1),
        ),
    )

    private fun flush(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())

    private fun boardRepo(): BoardPostRepository = Proxy.newProxyInstance(
        BoardPostRepository::class.java.classLoader, arrayOf(BoardPostRepository::class.java),
    ) { _, method, _ -> when (method.returnType) { java.util.List::class.java -> emptyList<Any>(); java.lang.Boolean.TYPE -> false; else -> null } } as BoardPostRepository

    @Test
    fun `declare gates and effects with month-rounded deadline`() {
        val world = world(general(10), general(11, officerLevel = 1)); val recorder = ChangeRecorder(); val h = OperationHandler(world, recorder)
        assertEquals(OperationRules.REASON_NO_PERMISSION, (h.handleDeclare(TurnDaemonCommand.OperationDeclare(generalId = 11, kind = "capture_city", targetCityId = 2, title = "낙양 공략", deadlineMonths = 1)) as OperationActionResult).reason)
        assertEquals(OperationRules.REASON_KIND_RESERVED, (h.handleDeclare(TurnDaemonCommand.OperationDeclare(generalId = 10, kind = "blockade", targetCityId = 2, title = "봉쇄", deadlineMonths = 1)) as OperationActionResult).reason)
        assertEquals(OperationRules.REASON_NOT_ENEMY, (h.handleDeclare(TurnDaemonCommand.OperationDeclare(generalId = 10, kind = "cut_supply", targetCityId = 3, title = "차단", deadlineMonths = 1)) as OperationActionResult).reason)
        val res = h.handleDeclare(TurnDaemonCommand.OperationDeclare(generalId = 10, kind = "capture_city", targetCityId = 2, title = " 낙양 공략 ", deadlineMonths = 1)) as OperationActionResult
        assertTrue(res.ok); assertNotNull(res.id)
        val op = world.getOperationById(res.id!!)!!
        assertEquals("낙양 공략", op.title); assertEquals(OperationRules.STATUS_DECLARED, op.status)
        // 선언 (200,3,중순) + 1개월 → (200,5,상순)
        assertEquals(listOf(200, 5, 1), listOf(op.deadlineYear, op.deadlineMonth, op.deadlinePhase))
        val payload = flush(world, recorder)
        assertEquals(1, payload.createdOperations.size); assertEquals(res.id, payload.worldStateUpdate["max_operation_id"])
        assertTrue(payload.logEntries.any { it.scope == "NATION" && it.category == "HISTORY" })
    }

    @Test
    fun `same tick declare then join then board link, and join then leave emits no DB work`() {
        val world = world(general(10), general(11, officerLevel = 1)); val recorder = ChangeRecorder(); val h = OperationHandler(world, recorder)
        val op = h.handleDeclare(TurnDaemonCommand.OperationDeclare(generalId = 10, kind = "capture_city", targetCityId = 2, title = "낙양 공략", deadlineMonths = 2)) as OperationActionResult
        val join = h.handleJoin(TurnDaemonCommand.OperationJoin(generalId = 11, operationId = op.id, role = "flank")) as OperationActionResult
        assertTrue(join.ok); assertEquals(OperationRules.STATUS_ACTIVE, world.getOperationById(op.id!!)!!.status)
        assertEquals(OperationRules.REASON_ALREADY_JOINED, (h.handleJoin(TurnDaemonCommand.OperationJoin(generalId = 11, operationId = op.id, role = "main")) as OperationActionResult).reason)
        val board = BoardHandler(world, recorder, boardRepo())
        val article = board.handleArticle(TurnDaemonCommand.BoardArticle(generalId = 10, title = "작전 회의", text = "본문", kind = "operation", operationId = op.id)) as BoardActionResult
        assertTrue(article.ok)
        assertEquals(op.id, recorder.boardPostInserts().single().columns["operation_id"])
        assertEquals("작전이 없습니다.", (board.handleArticle(TurnDaemonCommand.BoardArticle(generalId = 10, title = "x", text = "y", kind = "operation", operationId = 999)) as BoardActionResult).reason)
        assertEquals("올바르지 않은 입력입니다.", (board.handleArticle(TurnDaemonCommand.BoardArticle(generalId = 10, title = "x", text = "y", kind = "general", operationId = op.id)) as BoardActionResult).reason)
        // 같은 틱 join → leave → DB 작업 0
        assertTrue((h.handleLeave(TurnDaemonCommand.OperationLeave(generalId = 11, operationId = op.id)) as OperationActionResult).ok)
        val payload = flush(world, recorder)
        assertEquals(1, payload.createdOperations.size)
        assertTrue(payload.createdOperationUnits.isEmpty() && payload.updatedOperationUnits.isEmpty() && payload.deletedOperationUnitIds.isEmpty())
    }

    @Test
    fun `nation removal prunes operations and nulls pending board operation_id even for a persisted operation`() {
        val world = world(general(10)); val recorder = ChangeRecorder(); val h = OperationHandler(world, recorder)
        val op = h.handleDeclare(TurnDaemonCommand.OperationDeclare(generalId = 10, kind = "capture_city", targetCityId = 2, title = "낙양 공략", deadlineMonths = 2)) as OperationActionResult
        world.consumeDirtyState() // 지난 틱에 flush 됐다고 치자(R4)
        BoardHandler(world, recorder, boardRepo()).handleArticle(TurnDaemonCommand.BoardArticle(generalId = 10, title = "회의", text = "본문", kind = "operation", operationId = op.id))
        assertTrue(recorder.markNationDeleted(world, 1))
        assertNull(world.getOperationById(op.id!!))
        assertNull(recorder.boardPostInserts().single().columns["operation_id"])
        val dirty = world.consumeDirtyState()
        assertTrue(dirty.operations.isEmpty() && dirty.createdOperations.isEmpty() && dirty.deletedOperations.isEmpty())
    }

    @Test
    fun `general removal drops units and nulls the declarer, bugok disband nulls unit bugokId`() {
        val world = world(general(10), general(11, officerLevel = 1)); val recorder = ChangeRecorder(); val h = OperationHandler(world, recorder)
        val rh = RetainerHandler(world, recorder)
        val bugok = rh.handleBugokForm(TurnDaemonCommand.BugokForm(generalId = 11, troops = 100, rice = 0)) as opensamguk.common.wire.RetainerActionResult
        val op = h.handleDeclare(TurnDaemonCommand.OperationDeclare(generalId = 10, kind = "capture_city", targetCityId = 2, title = "낙양 공략", deadlineMonths = 2)) as OperationActionResult
        val unit = h.handleJoin(TurnDaemonCommand.OperationJoin(generalId = 11, operationId = op.id, role = "main", bugokId = bugok.id)) as OperationActionResult
        world.consumeDirtyState()
        rh.handleBugokDisband(TurnDaemonCommand.BugokDisband(generalId = 11, bugokId = bugok.id))
        assertNull(world.getOperationUnitById(unit.id!!)!!.bugokId)
        assertTrue(world.removeGeneral(10))
        assertNull(world.getOperationById(op.id!!)!!.declaredByGeneralId)
        assertTrue(world.removeGeneral(11))
        assertNull(world.getOperationUnitById(unit.id!!))
        val dirty = world.consumeDirtyState()
        assertEquals(listOf(op.id), dirty.operations.map { it.id }) // 선언자 NULL UPDATE 만
        assertTrue(dirty.deletedOperationUnits.isEmpty())
    }

    @Test
    fun `monthly settlement recomputes milestones and fails at the deadline with atmos loss`() {
        val world = world(general(10), general(11, officerLevel = 1, cityId = 1), phase = 1); val recorder = ChangeRecorder(); val h = OperationHandler(world, recorder)
        val op = h.handleDeclare(TurnDaemonCommand.OperationDeclare(generalId = 10, kind = "capture_city", targetCityId = 2, title = "낙양 공략", deadlineMonths = 1)) as OperationActionResult
        h.handleJoin(TurnDaemonCommand.OperationJoin(generalId = 11, operationId = op.id, role = "main"))
        // 부대가 목표 도시(2, 적 소유)로 이동했다고 치자 → departed + arrived
        world.applyGeneralDirtyFree(world.getGeneralById(11)!!.copy(cityId = 2))
        OperationMonthlyService().settle(world, recorder)
        var cur = world.getOperationById(op.id!!)!!
        assertTrue(cur.milestones.departed && cur.milestones.arrived); assertEquals(OperationRules.STATUS_ACTIVE, cur.status)
        // 기한 달 (200,4,상순) 로 시계를 올리고 정산 → 실패 + 사기 −5 + 개인 기록
        world.setCurrentDate(200, 4, 1)
        OperationMonthlyService().settle(world, recorder)
        cur = world.getOperationById(op.id!!)!!
        assertEquals(OperationRules.STATUS_FAILED, cur.status); assertEquals(OperationRules.CLOSED_DEADLINE, cur.closedReason)
        assertEquals(50 - OperationRules.FAIL_ATMOS_LOSS, world.getGeneralById(11)!!.atmos)
        assertTrue(recorder.generalPatches().any { it.id == 11 && "atmos" in it.columns })
        // 점령한 경우: achieved
        val world2 = world(general(10), general(11, officerLevel = 1), phase = 1); val r2 = ChangeRecorder(); val h2 = OperationHandler(world2, r2)
        val op2 = h2.handleDeclare(TurnDaemonCommand.OperationDeclare(generalId = 10, kind = "capture_city", targetCityId = 2, title = "낙양", deadlineMonths = 3)) as OperationActionResult
        h2.handleJoin(TurnDaemonCommand.OperationJoin(generalId = 11, operationId = op2.id, role = "main"))
        world2.updateCity(world2.getCityById(2)!!.copy(nationId = 1))
        OperationMonthlyService().settle(world2, r2)
        assertEquals(OperationRules.STATUS_ACHIEVED, world2.getOperationById(op2.id!!)!!.status)
    }
}

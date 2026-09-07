package opensamguk.logic.operation

import opensamguk.logic.operation.OperationRules.CityView
import opensamguk.logic.operation.OperationRules.MilestoneInput
import opensamguk.logic.operation.OperationRules.Milestones
import opensamguk.logic.operation.OperationRules.Transition
import opensamguk.logic.operation.OperationRules.UnitView
import opensamguk.logic.tick.GameDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** spec v4.1 §8 — 날짜 산술 표(P1·R6), 이정표·전이 표, 게이트 순서 표. 상수 값 단언 없음. */
class OperationRulesTest {

    @Test
    fun `deadline arithmetic table`() {
        assertEquals(GameDate(200, 5, 1), OperationRules.deadlineFor(GameDate(200, 3, 3), 1))
        assertEquals(GameDate(200, 4, 1), OperationRules.deadlineFor(GameDate(200, 3, 1), 1))
        assertEquals(GameDate(201, 2, 1), OperationRules.deadlineFor(GameDate(200, 12, 3), 1))
        assertEquals(GameDate(201, 1, 1), OperationRules.deadlineFor(GameDate(200, 12, 1), 1))
        assertEquals(GameDate(201, 3, 1), OperationRules.deadlineFor(GameDate(200, 3, 1), OperationRules.MAX_DEADLINE_MONTHS))
        assertEquals(GameDate(200, 5, 1), OperationRules.deadlineFor(GameDate(200, 3, 2), 1))
        // remainingMonths: 선언 순(중순)에 기한이 다음 상순+1개월이면 ≥ 1, 기한 달 상순에서는 1
        assertEquals(2, OperationRules.remainingMonths(GameDate(200, 3, 3), GameDate(200, 5, 1)))
        assertEquals(1, OperationRules.remainingMonths(GameDate(200, 4, 1), GameDate(200, 5, 1)))
        // now == deadline 은 정산이 같은 틱에 먼저 도니 진행 중 작전에서는 관측되지 않는다(P3) — 정의역 밖.
        assertEquals(true, OperationRules.deadlineReached(GameDate(200, 5, 1), GameDate(200, 5, 1)))
        assertEquals(false, OperationRules.deadlineReached(GameDate(200, 4, 3), GameDate(200, 5, 1)))
    }

    private fun input(kind: String, target: CityView, units: List<UnitView>, cityOf: Map<Int, CityView> = emptyMap(), adjacent: Set<Int> = emptySet(), atDeadline: Boolean = false) =
        MilestoneInput(kind, nationId = 1, targetCityId = 9, target = target, units = units, cityOf = cityOf, adjacentCityIds = adjacent, atDeadline = atDeadline)

    @Test
    fun `milestone table per declarable kind`() {
        val enemyTarget = CityView(nationId = 2, supplied = true)
        // 출발 전: 아무것도 없다(집에 서 있어도 supplied 가 공짜로 오지 않는다 — N3)
        val home = input(OperationRules.KIND_CAPTURE_CITY, enemyTarget, listOf(UnitView(1, 1)), cityOf = mapOf(1 to CityView(1, true)), adjacent = setOf(3))
        assertEquals(Milestones(false, false, false, false), OperationRules.milestones(home))
        // 인접 아군 보급 도시에 도착 → departed + supplied
        val adj = input(OperationRules.KIND_CAPTURE_CITY, enemyTarget, listOf(UnitView(3, 1)), cityOf = mapOf(3 to CityView(1, true)), adjacent = setOf(3))
        assertEquals(Milestones(true, false, true, false), OperationRules.milestones(adj))
        // 목표 도시 도착 → arrived; 아직 적 소유 → objective false
        val arrived = input(OperationRules.KIND_CAPTURE_CITY, enemyTarget, listOf(UnitView(9, 1)), cityOf = mapOf(9 to enemyTarget), adjacent = setOf(3))
        assertEquals(Milestones(true, true, false, false), OperationRules.milestones(arrived))
        // 점령 → objective
        val captured = input(OperationRules.KIND_CAPTURE_CITY, CityView(1, true), listOf(UnitView(9, 1)), cityOf = mapOf(9 to CityView(1, true)), adjacent = setOf(3))
        assertEquals(true, OperationRules.milestones(captured).objective)
        // relieve: 기한 전에는 objective false, 기한 달에 아군이면 true; supplied 는 목표 도시 자체
        val relieve = input(OperationRules.KIND_RELIEVE, CityView(1, true), listOf(UnitView(9, 4)), cityOf = mapOf(9 to CityView(1, true)))
        assertEquals(Milestones(true, true, true, false), OperationRules.milestones(relieve))
        assertEquals(true, OperationRules.milestones(relieve.copy(atDeadline = true)).objective)
        // cut_supply: 적 소유 + 보급 끊김 → objective; 공백지는 objective 아님(강제 보급) → targetGone
        val cut = input(OperationRules.KIND_CUT_SUPPLY, CityView(2, false), emptyList())
        assertEquals(true, OperationRules.milestones(cut).objective)
        assertEquals(false, OperationRules.milestones(input(OperationRules.KIND_CUT_SUPPLY, CityView(0, true), emptyList())).objective)
        assertEquals(true, OperationRules.targetGone(OperationRules.KIND_CUT_SUPPLY, 1, CityView(0, true)))
        assertEquals(true, OperationRules.targetGone(OperationRules.KIND_CUT_SUPPLY, 1, CityView(1, true)))
        assertEquals(false, OperationRules.targetGone(OperationRules.KIND_CAPTURE_CITY, 1, CityView(0, true)))
        // 단조: 이전 true 유지
        assertEquals(Milestones(true, true, true, false), Milestones(false, false, false, false).or(Milestones(true, true, true, false)))
        assertEquals(50, OperationRules.displayPct(Milestones(true, true, false, false)))
    }

    @Test
    fun `transition order is target-gone then achieved then deadline`() {
        val now = GameDate(200, 5, 1); val dl = GameDate(200, 5, 1)
        assertEquals(Transition.TargetGone, OperationRules.transition(OperationRules.KIND_CUT_SUPPLY, 1, CityView(1, true), Milestones(true, true, true, true), now, dl))
        assertEquals(Transition.Achieved, OperationRules.transition(OperationRules.KIND_CAPTURE_CITY, 1, CityView(1, true), Milestones(true, true, true, true), now, dl))
        assertEquals(Transition.Failed, OperationRules.transition(OperationRules.KIND_CAPTURE_CITY, 1, CityView(2, true), Milestones(true, false, false, false), now, dl))
        assertEquals(Transition.None, OperationRules.transition(OperationRules.KIND_CAPTURE_CITY, 1, CityView(2, true), Milestones(false, false, false, false), GameDate(200, 4, 1), dl))
    }

    @Test
    fun `gate order tables`() {
        // 선언: 재야 > 권한 > 예약 종류 > 목표 없음 > 소유 조건 > 상한
        assertEquals(OperationRules.REASON_NO_NATION, OperationRules.declareDeny(-1, "blockade", null, 0, 9))
        assertEquals(OperationRules.REASON_NO_PERMISSION, OperationRules.declareDeny(1, "blockade", null, 1, 9))
        assertEquals(OperationRules.REASON_KIND_RESERVED, OperationRules.declareDeny(2, "blockade", null, 1, 9))
        assertEquals(OperationRules.REASON_NO_TARGET, OperationRules.declareDeny(2, "capture_city", null, 1, 9))
        assertEquals(OperationRules.REASON_ALREADY_OURS, OperationRules.declareDeny(2, "capture_city", 1, 1, 9))
        assertEquals(OperationRules.REASON_NOT_ENEMY, OperationRules.declareDeny(2, "cut_supply", 0, 1, 9))
        assertEquals(OperationRules.REASON_NOT_ENEMY, OperationRules.declareDeny(2, "cut_supply", 1, 1, 9))
        assertEquals(OperationRules.REASON_NOT_OURS, OperationRules.declareDeny(2, "relieve", 2, 1, 9))
        assertEquals(OperationRules.REASON_NATION_FULL, OperationRules.declareDeny(2, "capture_city", 2, 1, OperationRules.MAX_ACTIVE_PER_NATION))
        assertNull(OperationRules.declareDeny(2, "capture_city", 0, 1, 0)) // 공백지 점령은 허용
        // 입력
        assertEquals(OperationRules.REASON_INPUT, (OperationRules.declareInput("capture_city", 1, "x", null, 1) as OperationRules.DeclareInput.Denied).reason)
        assertEquals(OperationRules.REASON_INPUT, (OperationRules.declareInput("capture_city", 1, "낙양 공략", null, OperationRules.MAX_DEADLINE_MONTHS + 1) as OperationRules.DeclareInput.Denied).reason)
        assertEquals("낙양 공략", (OperationRules.declareInput("capture_city", 1, " 낙양 공략 ", "  ", 1) as OperationRules.DeclareInput.Ok).title)
        assertNull((OperationRules.declareInput("capture_city", 1, "낙양 공략", "  ", 1) as OperationRules.DeclareInput.Ok).fallbackText)
        // 참여: 없음 > 종료 > 이미 > 다른 작전 > 상한 > 부곡
        assertEquals(OperationRules.REASON_NO_OPERATION, OperationRules.joinDeny(false, "closed", true, true, 99, 1, false))
        assertEquals(OperationRules.REASON_ENDED, OperationRules.joinDeny(true, "closed", true, true, 99, 1, false))
        assertEquals(OperationRules.REASON_ALREADY_JOINED, OperationRules.joinDeny(true, "active", true, true, 99, 1, false))
        assertEquals(OperationRules.REASON_OTHER_OPERATION, OperationRules.joinDeny(true, "active", false, true, 99, 1, false))
        assertEquals(OperationRules.REASON_UNITS_FULL, OperationRules.joinDeny(true, "active", false, false, OperationRules.MAX_UNITS, 1, false))
        assertEquals(OperationRules.REASON_NO_BUGOK, OperationRules.joinDeny(true, "active", false, false, 0, 1, false))
        assertNull(OperationRules.joinDeny(true, "declared", false, false, 0, null, false))
        assertEquals(OperationRules.REASON_INPUT, OperationRules.joinInputDeny(1, "boss"))
        // 이탈·종료
        assertEquals(OperationRules.REASON_NOT_JOINED, OperationRules.leaveDeny(true, false))
        assertEquals(OperationRules.REASON_NO_PERMISSION, OperationRules.closeDeny(true, 1, "active"))
        assertEquals(OperationRules.REASON_ENDED, OperationRules.closeDeny(true, 2, "failed"))
        assertNull(OperationRules.closeDeny(true, 2, "declared"))
    }
}

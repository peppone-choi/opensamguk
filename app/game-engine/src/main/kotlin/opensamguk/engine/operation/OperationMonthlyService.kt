package opensamguk.engine.operation

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.OperationMilestones
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.operation.OperationRules
import opensamguk.logic.tick.GameDate
import opensamguk.logic.world.ActiveWorldMap
import opensamguk.logic.world.CalcCityDistance

/**
 * Phase 4X-B 월 정산 (spec v4.1 §5). [opensamguk.engine.run.MonthlyPostUpdateHook.run] 의 마지막(4X-A 가신 정산 **뒤**).
 * 순서(P5): ① 참여자 정리 → ② 이정표 재계산(단조) → ③ 목표 소멸 → ④ 전이. 행 0 이면 산출물 무접촉(적색 프로브).
 * `supplyState` 는 같은 틱 L5 PRE_MONTH 의 UpdateCitySupply 가 이미 갱신한 값(전월 기준 BFS, 한 달 지연 규칙 S2).
 */
class OperationMonthlyService {

    fun settle(world: InMemoryTurnWorld, recorder: ChangeRecorder) {
        val ops = world.listOperations().filter { it.status in OperationRules.OPEN_STATUSES }.sortedBy { it.id }
        if (ops.isEmpty()) return
        val state = world.getState()
        val now = GameDate(state.currentYear, state.currentMonth, state.currentPhase)
        val variant = ActiveWorldMap.requireVariant(state.config, state.meta)

        for (op in ops) {
            if (world.getNationById(op.nationId) == null) continue
            // ① 참여자 정리(S11): 사라졌거나 국가를 바꾼 unit 은 제거(DELETE 기록 — 지난 틱 flush 된 행).
            val units = world.unitsOf(op.id).filter { u ->
                val g = world.getGeneralById(u.generalId)
                if (g == null || g.nationId != op.nationId) { world.removeOperationUnit(u.id); false } else true
            }
            val targetCity = world.getCityById(op.targetCityId) ?: continue
            val target = OperationRules.CityView(targetCity.nationId, targetCity.supplyState != 0)
            val deadline = GameDate(op.deadlineYear, op.deadlineMonth, op.deadlinePhase)
            var next = op

            // ② 이정표 재계산 — declared + unit 0 이면 건너뛴다(S12-b).
            if (units.isNotEmpty()) {
                val unitViews = units.mapNotNull { u -> world.getGeneralById(u.generalId)?.let { g -> OperationRules.UnitView(g.cityId, u.joinedCityId) } }
                val cityOf = unitViews.map { it.cityId }.distinct().mapNotNull { cid ->
                    world.getCityById(cid)?.let { c -> cid to OperationRules.CityView(c.nationId, c.supplyState != 0) }
                }.toMap()
                val adjacent = CalcCityDistance.nearCity(op.targetCityId, 1, variant)
                val computed = OperationRules.milestones(
                    OperationRules.MilestoneInput(
                        kind = op.kind, nationId = op.nationId, targetCityId = op.targetCityId, target = target,
                        units = unitViews, cityOf = cityOf, adjacentCityIds = adjacent,
                        atDeadline = OperationRules.deadlineReached(now, deadline),
                    ),
                ).or(OperationRules.Milestones(op.milestones.departed, op.milestones.arrived, op.milestones.supplied, op.milestones.objective))
                next = next.copy(milestones = OperationMilestones(computed.departed, computed.arrived, computed.supplied, computed.objective))
            }

            // ③ 목표 소멸 → ④ 전이
            val m = next.milestones
            when (OperationRules.transition(op.kind, op.nationId, target, OperationRules.Milestones(m.departed, m.arrived, m.supplied, m.objective), now, deadline)) {
                OperationRules.Transition.TargetGone -> {
                    next = next.copy(status = OperationRules.STATUS_CLOSED, closedReason = OperationRules.CLOSED_TARGET_GONE)
                    nationLog(world, op.nationId, "<Y>${op.title}</> 작전 목표가 사라져 종료했습니다.")
                }
                OperationRules.Transition.Achieved -> {
                    next = next.copy(status = OperationRules.STATUS_ACHIEVED, closedReason = OperationRules.CLOSED_ACHIEVED)
                    nationLog(world, op.nationId, "<Y>${op.title}</> 작전 목표를 달성했습니다.")
                }
                OperationRules.Transition.Failed -> {
                    next = next.copy(status = OperationRules.STATUS_FAILED, closedReason = OperationRules.CLOSED_DEADLINE)
                    nationLog(world, op.nationId, "<Y>${op.title}</> 작전이 기한을 넘겨 실패했습니다.")
                    for (u in units) {
                        val g = world.getGeneralById(u.generalId) ?: continue
                        val atmos = (g.atmos - OperationRules.FAIL_ATMOS_LOSS).coerceAtLeast(0)
                        if (atmos != g.atmos) applyGeneral(world, recorder, g, g.copy(atmos = atmos))
                        world.pushLog(LogEntryDraft(scope = "general", category = "action", text = "작전 실패로 사기가 떨어졌습니다.", generalId = g.id, nationId = g.nationId))
                    }
                }
                OperationRules.Transition.None -> Unit
            }
            if (next != op) world.updateOperation(next)
        }
    }

    private fun nationLog(world: InMemoryTurnWorld, nationId: Int, text: String) {
        world.pushLog(LogEntryDraft(scope = "nation", category = "history", text = text, nationId = nationId))
    }

    private fun applyGeneral(world: InMemoryTurnWorld, recorder: ChangeRecorder, me: TurnGeneral, next: TurnGeneral) {
        val pre = PerTurnOverlay.toLogicGeneral(me)
        world.applyGeneralDirtyFree(next)
        recorder.diffGeneral(pre, PerTurnOverlay.toLogicGeneral(next))
    }
}

package opensamguk.engine.intake

import opensamguk.common.wire.OperationActionResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.Operation
import opensamguk.engine.turn.OperationMilestones
import opensamguk.engine.turn.OperationUnit
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.actions.intake.SecretPermission
import opensamguk.logic.operation.OperationRules
import opensamguk.logic.tick.GameDate
import java.time.Instant

/**
 * Phase 4X-B 작전 intake 핸들러 (specs/2026-09-06-operation-vertical-slice v4.1 §4).
 * 게이트 순서(4 명령 공통): ① 장수 없음 → ② 접속 제한 → ③ 입력 → ④ 상태([OperationRules]). 권한은 엔진
 * `SecretPermission.check(me)` 한 곳. 작전·부대는 [InMemoryTurnWorld] 세계 상태(troop 미러)라 world create/update/remove 가 채널.
 */
class OperationHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private fun fail(type: String, generalId: Int, reason: String) =
        OperationActionResult(type, ok = false, generalId = generalId, reason = reason)

    private fun preGate(type: String, generalId: Int): Pair<TurnGeneral?, TurnDaemonCommandResult?> {
        val me = world.getGeneralById(generalId)
            ?: return null to fail(type, generalId, "장수가 존재하지 않습니다.")
        if (AccessLogThrottle(world, recorder, nowProvider).increaseAndBlocked(generalId)) {
            return null to fail(type, generalId, "접속 제한입니다.")
        }
        return me to null
    }

    private fun now(): GameDate = world.getState().let { GameDate(it.currentYear, it.currentMonth, it.currentPhase) }

    private fun nationLog(nationId: Int, text: String) {
        // 국가 기록 읽기 경로는 scope=NATION·category=HISTORY 만 본다(spec S4).
        world.pushLog(LogEntryDraft(scope = "nation", category = "history", text = text, nationId = nationId))
    }

    fun handleDeclare(c: TurnDaemonCommand.OperationDeclare): TurnDaemonCommandResult {
        val type = "operationDeclare"
        val (me, denied) = preGate(type, c.generalId)
        if (denied != null || me == null) return denied!!
        val input = when (val i = OperationRules.declareInput(c.kind, c.targetCityId, c.title, c.fallbackText, c.deadlineMonths)) {
            is OperationRules.DeclareInput.Denied -> return fail(type, c.generalId, i.reason)
            is OperationRules.DeclareInput.Ok -> i
        }
        val permission = SecretPermission.check(PerTurnOverlay.toLogicGeneral(me))
        val target = world.getCityById(input.targetCityId)
        val openCount = world.operationsOf(me.nationId).count { it.status in OperationRules.OPEN_STATUSES }
        OperationRules.declareDeny(permission, input.kind, target?.nationId, me.nationId, openCount)?.let { return fail(type, c.generalId, it) }

        val declaredAt = now()
        val deadline = OperationRules.deadlineFor(declaredAt, input.deadlineMonths)
        val id = world.allocateOperationId()
        world.createOperation(
            Operation(
                id = id, nationId = me.nationId, kind = input.kind, targetCityId = input.targetCityId, title = input.title,
                fallbackText = input.fallbackText, declaredByGeneralId = me.id,
                declaredYear = declaredAt.year, declaredMonth = declaredAt.month, declaredPhase = declaredAt.phase,
                deadlineYear = deadline.year, deadlineMonth = deadline.month, deadlinePhase = deadline.phase,
                status = OperationRules.STATUS_DECLARED, milestones = OperationMilestones(),
            ),
        )
        nationLog(me.nationId, "<Y>${input.title}</> 작전을 선언했습니다.")
        return OperationActionResult(type, ok = true, generalId = c.generalId, id = id)
    }

    fun handleJoin(c: TurnDaemonCommand.OperationJoin): TurnDaemonCommandResult {
        val type = "operationJoin"
        val (me, denied) = preGate(type, c.generalId)
        if (denied != null || me == null) return denied!!
        OperationRules.joinInputDeny(c.operationId, c.role)?.let { return fail(type, c.generalId, it) }
        val op = c.operationId?.let { world.getOperationById(it) }
        val mine = op != null && op.nationId == me.nationId && me.nationId != 0
        val alreadyJoined = op != null && world.unitsOf(op.id).any { it.generalId == me.id }
        val joinedOtherOpen = world.operationUnitsOfGeneral(me.id).any { u ->
            val other = world.getOperationById(u.operationId)
            other != null && other.id != op?.id && other.nationId == me.nationId && other.status in OperationRules.OPEN_STATUSES
        }
        val bugok = c.bugokId?.let { world.getBugokById(it) }
        OperationRules.joinDeny(
            operationExistsForMyNation = mine, status = op?.status, alreadyJoined = alreadyJoined, joinedOtherOpen = joinedOtherOpen,
            unitCount = op?.let { world.unitsOf(it.id).size } ?: 0, bugokId = c.bugokId,
            bugokOwned = bugok != null && bugok.masterGeneralId == me.id,
        )?.let { return fail(type, c.generalId, it) }
        op!!
        val at = now()
        val id = world.allocateOperationUnitId()
        world.createOperationUnit(
            OperationUnit(
                id = id, operationId = op.id, generalId = me.id, bugokId = c.bugokId, role = c.role!!,
                joinedCityId = me.cityId, joinedYear = at.year, joinedMonth = at.month, joinedPhase = at.phase,
            ),
        )
        if (op.status == OperationRules.STATUS_DECLARED) world.updateOperation(op.copy(status = OperationRules.STATUS_ACTIVE))
        return OperationActionResult(type, ok = true, generalId = c.generalId, id = id)
    }

    fun handleLeave(c: TurnDaemonCommand.OperationLeave): TurnDaemonCommandResult {
        val type = "operationLeave"
        val (me, denied) = preGate(type, c.generalId)
        if (denied != null || me == null) return denied!!
        val operationId = c.operationId ?: return fail(type, c.generalId, OperationRules.REASON_INPUT)
        val op = world.getOperationById(operationId)
        val unit = op?.let { o -> world.unitsOf(o.id).firstOrNull { it.generalId == me.id } }
        OperationRules.leaveDeny(op != null, unit != null)?.let { return fail(type, c.generalId, it) }
        world.removeOperationUnit(unit!!.id)
        return OperationActionResult(type, ok = true, generalId = c.generalId, id = unit.id)
    }

    fun handleClose(c: TurnDaemonCommand.OperationClose): TurnDaemonCommandResult {
        val type = "operationClose"
        val (me, denied) = preGate(type, c.generalId)
        if (denied != null || me == null) return denied!!
        val operationId = c.operationId ?: return fail(type, c.generalId, OperationRules.REASON_INPUT)
        val op = world.getOperationById(operationId)
        val mine = op != null && op.nationId == me.nationId && me.nationId != 0
        val permission = SecretPermission.check(PerTurnOverlay.toLogicGeneral(me))
        OperationRules.closeDeny(mine, permission, op?.status)?.let { return fail(type, c.generalId, it) }
        op!!
        world.updateOperation(op.copy(status = OperationRules.STATUS_CLOSED, closedReason = OperationRules.CLOSED_COMMAND))
        nationLog(me.nationId, "<Y>${op.title}</> 작전을 종료했습니다.")
        return OperationActionResult(type, ok = true, generalId = c.generalId, id = op.id)
    }
}

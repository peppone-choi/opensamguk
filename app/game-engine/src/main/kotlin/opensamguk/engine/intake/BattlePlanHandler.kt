package opensamguk.engine.intake

import opensamguk.common.wire.BattlePlanActionResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.BattlePlan
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.tick.GameDate
import opensamguk.logic.war.plan.BattlePlanRules
import java.time.Instant

/**
 * Phase 4X-C 출병 계획 봉인 intake 핸들러 (specs/2026-09-06-wego-field-seal-replay-vertical-slice v4.1 §4).
 * 게이트 순서(3 명령 공통): ① 장수 없음 → ② 접속 제한 → ③ 입력 → ④ 상태([BattlePlanRules]). 계획은 [InMemoryTurnWorld]
 * 세계 상태(troop 미러)라 world create/update/remove 가 채널. 봉인 뒤 수정·삭제는 인테이크 거부 사유(409 아님).
 * 소비된 계획(`resolved`)은 (장수, 도시) 키를 놓아 다시 저장할 수 있다(F7).
 */
class BattlePlanHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private fun fail(type: String, generalId: Int, reason: String) =
        BattlePlanActionResult(type, ok = false, generalId = generalId, reason = reason)

    private fun preGate(type: String, generalId: Int): Pair<TurnGeneral?, TurnDaemonCommandResult?> {
        val me = world.getGeneralById(generalId)
            ?: return null to fail(type, generalId, "장수가 존재하지 않습니다.")
        if (AccessLogThrottle(world, recorder, nowProvider).increaseAndBlocked(generalId)) {
            return null to fail(type, generalId, "접속 제한입니다.")
        }
        return me to null
    }

    private fun now(): GameDate = world.getState().let { GameDate(it.currentYear, it.currentMonth, it.currentPhase.coerceIn(1, 3)) }

    /** 내 미소비 계획 — 소비된 행은 기록으로만 남는다. */
    private fun myOpenPlan(me: TurnGeneral, planId: Int?): BattlePlan? =
        planId?.let { world.getBattlePlanById(it) }?.takeIf { it.generalId == me.id && !it.resolved }

    fun handleSave(c: TurnDaemonCommand.BattlePlanSave): TurnDaemonCommandResult {
        val type = "battlePlanSave"
        val (me, denied) = preGate(type, c.generalId)
        if (denied != null || me == null) return denied!!
        val input = when (val i = BattlePlanRules.saveInput(c.targetCityId, c.stance, c.retreatLossPct, c.retreatMoraleBelow)) {
            is BattlePlanRules.SaveInput.Denied -> return fail(type, c.generalId, i.reason)
            is BattlePlanRules.SaveInput.Ok -> i
        }
        val target = world.getCityById(input.targetCityId)
        val existing = world.battlePlansOf(me.id).firstOrNull { it.targetCityId == input.targetCityId && !it.resolved }
        BattlePlanRules.saveDeny(target != null, target?.nationId, me.nationId, existing?.sealed == true)?.let { return fail(type, c.generalId, it) }
        val saved = if (existing != null) {
            world.updateBattlePlan(existing.copy(stance = input.stance, retreatLossPct = input.retreatLossPct, retreatMoraleBelow = input.retreatMoraleBelow, version = existing.version + 1))!!
        } else {
            world.createBattlePlan(
                BattlePlan(id = world.allocateBattlePlanId(), generalId = me.id, targetCityId = input.targetCityId, stance = input.stance,
                    retreatLossPct = input.retreatLossPct, retreatMoraleBelow = input.retreatMoraleBelow, version = 1),
            )
        }
        return BattlePlanActionResult(type, ok = true, generalId = c.generalId, id = saved.id)
    }

    fun handleSeal(c: TurnDaemonCommand.BattlePlanSeal): TurnDaemonCommandResult {
        val type = "battlePlanSeal"
        val (me, denied) = preGate(type, c.generalId)
        if (denied != null || me == null) return denied!!
        val planId = c.planId ?: return fail(type, c.generalId, BattlePlanRules.REASON_INPUT)
        val plan = myOpenPlan(me, planId)
        BattlePlanRules.sealDeny(plan != null, plan?.sealed == true)?.let { return fail(type, c.generalId, it) }
        val at = now()
        world.updateBattlePlan(plan!!.copy(sealedAt = nowProvider(), sealedYear = at.year, sealedMonth = at.month, sealedPhase = at.phase))
        val cityName = world.getCityById(plan.targetCityId)?.name ?: "C${plan.targetCityId}"
        world.pushLog(LogEntryDraft(scope = "general", category = "action", text = "<Y>$cityName</> 출병 계획을 봉인했습니다.", generalId = me.id, nationId = me.nationId))
        return BattlePlanActionResult(type, ok = true, generalId = c.generalId, id = plan.id)
    }

    fun handleDelete(c: TurnDaemonCommand.BattlePlanDelete): TurnDaemonCommandResult {
        val type = "battlePlanDelete"
        val (me, denied) = preGate(type, c.generalId)
        if (denied != null || me == null) return denied!!
        val planId = c.planId ?: return fail(type, c.generalId, BattlePlanRules.REASON_INPUT)
        val plan = myOpenPlan(me, planId)
        BattlePlanRules.deleteDeny(plan != null, plan?.sealed == true)?.let { return fail(type, c.generalId, it) }
        world.removeBattlePlan(plan!!.id)
        return BattlePlanActionResult(type, ok = true, generalId = c.generalId, id = plan.id)
    }
}

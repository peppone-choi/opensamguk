package opensamguk.engine.intake

import opensamguk.common.wire.RetainerActionResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.Bugok
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.Retainer
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.retainer.RetainerRules
import java.time.Instant

/**
 * Phase 4X-A 가신·부곡 intake 핸들러 (specs/2026-09-06-retinue-buqu-vertical-slice v3 §4, ADR-LITE-017).
 *
 * 게이트 순서(6 명령 공통): ① 장수 없음 → ② 접속 제한([AccessLogThrottle]) → ③ 입력 → ④ 상태([RetainerRules]).
 * 가신·부곡 행은 [InMemoryTurnWorld] 세계 상태(troop 미러)라 world create/update/remove 가 채널이고,
 * 주인 장수의 gold/crew/rice 변경만 `applyGeneralDirtyFree` + `recorder.diffGeneral` 짝으로 기록한다(spec F1).
 * `world.updateGeneral` 은 데몬 쓰기 경로가 아니다. NPC 는 서약하지 않는다(결정성 — 인테이크만).
 */
class RetainerHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private fun fail(type: String, generalId: Int, reason: String) =
        RetainerActionResult(type, ok = false, generalId = generalId, reason = reason)

    /** ①·② 공통 게이트. 실패 사유가 있으면 반환. */
    private fun preGate(type: String, generalId: Int): Pair<TurnGeneral?, TurnDaemonCommandResult?> {
        val me = world.getGeneralById(generalId)
            ?: return null to fail(type, generalId, "장수가 존재하지 않습니다.")
        if (AccessLogThrottle(world, recorder, nowProvider).increaseAndBlocked(generalId)) {
            return null to fail(type, generalId, "접속 제한입니다.")
        }
        return me to null
    }

    /** 주인 장수 변경은 반드시 이 짝으로(spec F1). */
    private fun applyGeneral(me: TurnGeneral, next: TurnGeneral) {
        val pre = PerTurnOverlay.toLogicGeneral(me)
        world.applyGeneralDirtyFree(next)
        recorder.diffGeneral(pre, PerTurnOverlay.toLogicGeneral(next))
    }

    fun handlePledge(c: TurnDaemonCommand.RetainerPledge): TurnDaemonCommandResult {
        val type = "retainerPledge"
        val (me, denied) = preGate(type, c.generalId)
        if (denied != null || me == null) return denied!!
        val name = when (val n = RetainerRules.normalizeName(c.name)) {
            is RetainerRules.NameOutcome.Denied -> return fail(type, c.generalId, n.reason)
            is RetainerRules.NameOutcome.Ok -> n.name
        }
        val relation = c.relation ?: return fail(type, c.generalId, RetainerRules.REASON_INPUT)
        if (relation !in RetainerRules.RELATIONS) return fail(type, c.generalId, RetainerRules.REASON_INPUT)
        val role = c.role ?: RetainerRules.ROLE_NONE
        if (role !in RetainerRules.ROLES) return fail(type, c.generalId, RetainerRules.REASON_INPUT)

        val mine = world.retainersOf(me.id)
        RetainerRules.pledgeDeny(mine.size, mine.map { it.name }, name, me.gold)?.let { return fail(type, c.generalId, it) }

        applyGeneral(me, me.copy(gold = me.gold - RetainerRules.PLEDGE_COST_GOLD))
        val id = world.allocateRetainerId()
        world.createRetainer(
            Retainer(
                id = id, masterGeneralId = me.id, origin = RetainerRules.ORIGIN_RECRUITED, generalId = null,
                name = name, relation = relation, role = role, hasOwnBugok = false,
                releasePolicy = RetainerRules.RELEASE_MASTER_ONLY, loyalty = 50, task = RetainerRules.TASK_NONE,
            ),
        )
        return RetainerActionResult(type, ok = true, generalId = c.generalId, id = id)
    }

    fun handleRelease(c: TurnDaemonCommand.RetainerRelease): TurnDaemonCommandResult {
        val type = "retainerRelease"
        val (me, denied) = preGate(type, c.generalId)
        if (denied != null || me == null) return denied!!
        val retainerId = c.retainerId ?: return fail(type, c.generalId, RetainerRules.REASON_INPUT)
        val r = world.getRetainerById(retainerId)
        if (r == null || r.masterGeneralId != me.id) return fail(type, c.generalId, RetainerRules.REASON_NO_RETAINER)
        world.removeRetainer(retainerId) // 지휘 중이던 부곡의 commander NULL UPDATE 는 world 가 함께 기록
        return RetainerActionResult(type, ok = true, generalId = c.generalId, id = retainerId)
    }

    fun handleTask(c: TurnDaemonCommand.RetainerTask): TurnDaemonCommandResult {
        val type = "retainerTask"
        val (me, denied) = preGate(type, c.generalId)
        if (denied != null || me == null) return denied!!
        val retainerId = c.retainerId ?: return fail(type, c.generalId, RetainerRules.REASON_INPUT)
        val task = c.task ?: return fail(type, c.generalId, RetainerRules.REASON_INPUT)
        if (task !in RetainerRules.TASKS) return fail(type, c.generalId, RetainerRules.REASON_INPUT)
        val r = world.getRetainerById(retainerId)
        if (r == null || r.masterGeneralId != me.id) return fail(type, c.generalId, RetainerRules.REASON_NO_RETAINER)
        if (r.task != task) world.updateRetainer(r.copy(task = task))
        return RetainerActionResult(type, ok = true, generalId = c.generalId, id = retainerId)
    }

    fun handleBugokForm(c: TurnDaemonCommand.BugokForm): TurnDaemonCommandResult {
        val type = "bugokForm"
        val (me, denied) = preGate(type, c.generalId)
        if (denied != null || me == null) return denied!!
        RetainerRules.bugokFormInputDeny(c.troops, c.rice)?.let { return fail(type, c.generalId, it) }
        val troops = c.troops!!
        val rice = c.rice!!
        val mine = world.bugoksOf(me.id)
        RetainerRules.bugokFormDeny(mine.size, me.crew, troops, me.rice, rice)?.let { return fail(type, c.generalId, it) }

        applyGeneral(me, me.copy(crew = me.crew - troops, rice = me.rice - rice))
        val id = world.allocateBugokId()
        val ordinal = (mine.maxOfOrNull { b -> b.name.removePrefix("부곡 ").toIntOrNull() ?: 0 } ?: 0) + 1
        world.createBugok(
            Bugok(
                id = id, masterGeneralId = me.id, name = "부곡 $ordinal", troops = troops, crewTypeId = me.crewTypeId,
                training = me.train.coerceIn(0, 100), morale = me.atmos.coerceIn(0, 100), fatigue = 0, provisions = rice,
                commanderRetainerId = null,
            ),
        )
        return RetainerActionResult(type, ok = true, generalId = c.generalId, id = id)
    }

    fun handleBugokDisband(c: TurnDaemonCommand.BugokDisband): TurnDaemonCommandResult {
        val type = "bugokDisband"
        val (me, denied) = preGate(type, c.generalId)
        if (denied != null || me == null) return denied!!
        val bugokId = c.bugokId ?: return fail(type, c.generalId, RetainerRules.REASON_INPUT)
        val b = world.getBugokById(bugokId)
        val owned = b != null && b.masterGeneralId == me.id
        RetainerRules.bugokDisbandDeny(owned, b?.crewTypeId ?: -1, me.crewTypeId)?.let { return fail(type, c.generalId, it) }
        b!!
        // 해산은 crew/rice 만 되돌린다 — train/atmos 는 바꾸지 않는다(지어낸 가중식 없음, spec S3).
        applyGeneral(me, me.copy(crew = me.crew + b.troops, rice = me.rice + b.provisions))
        world.removeBugok(bugokId)
        return RetainerActionResult(type, ok = true, generalId = c.generalId, id = bugokId)
    }

    fun handleBugokAssignCommander(c: TurnDaemonCommand.BugokAssignCommander): TurnDaemonCommandResult {
        val type = "bugokAssignCommander"
        val (me, denied) = preGate(type, c.generalId)
        if (denied != null || me == null) return denied!!
        val bugokId = c.bugokId ?: return fail(type, c.generalId, RetainerRules.REASON_INPUT)
        val b = world.getBugokById(bugokId)
        val bugokOwned = b != null && b.masterGeneralId == me.id
        val r = c.retainerId?.let { world.getRetainerById(it) }
        val retainerOwned = r != null && r.masterGeneralId == me.id
        RetainerRules.assignCommanderDeny(bugokOwned, c.retainerId, retainerOwned, r?.relation)
            ?.let { return fail(type, c.generalId, it) }
        b!!
        val newlyAssigned = c.retainerId != null && b.commanderRetainerId != c.retainerId
        val morale = if (newlyAssigned) (b.morale + RetainerRules.COMMANDER_MORALE_BONUS).coerceAtMost(100) else b.morale
        if (b.commanderRetainerId != c.retainerId || morale != b.morale) {
            world.updateBugok(b.copy(commanderRetainerId = c.retainerId, morale = morale))
        }
        return RetainerActionResult(type, ok = true, generalId = c.generalId, id = bugokId)
    }
}

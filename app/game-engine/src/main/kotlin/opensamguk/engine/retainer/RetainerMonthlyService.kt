package opensamguk.engine.retainer

import opensamguk.common.josa.JosaUtil
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.retainer.RetainerRules

/**
 * Phase 4X-A 월 정산 (spec v3 §5). [opensamguk.engine.run.MonthlyPostUpdateHook.run] 의 **마지막 단계**에서
 * 호출된다(L10 안 — L6 조기 반환이면 정산도 없다). 행 0 이면 산출물을 하나도 건드리지 않는다(적색 프로브 게이트).
 *
 * 산식은 [RetainerRules] 순수 함수가 갖고, 여기서는 결과를 메모리 세계 상태에 적용만 한다. 주인 장수는 행마다
 * **그 시점에** 다시 읽는다(`run()` 시작 스냅샷 아님 — N2); 없으면 건너뛴다(정상 경로는 `removeGeneral` 가지치기).
 */
class RetainerMonthlyService {

    fun settle(world: InMemoryTurnWorld, recorder: ChangeRecorder) {
        val bugoks = world.listBugoks()
        val retainers = world.listRetainers()
        if (bugoks.isEmpty() && retainers.isEmpty()) return

        for (b in bugoks.sortedBy { it.id }) {
            val master = world.getGeneralById(b.masterGeneralId) ?: continue
            val commanderTask = b.commanderRetainerId?.let { world.getRetainerById(it) }?.task
            val out = RetainerRules.settleBugok(
                RetainerRules.BugokSettleInput(
                    troops = b.troops, provisions = b.provisions, morale = b.morale, fatigue = b.fatigue,
                    training = b.training, masterGold = master.gold, commanderTask = commanderTask,
                ),
            )
            if (out.goldPaid > 0) applyGeneral(world, recorder, master, master.copy(gold = master.gold - out.goldPaid))
            val next = b.copy(provisions = out.provisions, morale = out.morale, fatigue = out.fatigue, training = out.training)
            if (next != b) world.updateBugok(next)
        }

        for (r in retainers.sortedBy { it.id }) {
            val master = world.getGeneralById(r.masterGeneralId) ?: continue
            when (val out = RetainerRules.settleRetainer(
                RetainerRules.RetainerSettleInput(
                    loyalty = r.loyalty, task = r.task, origin = r.origin, masterGold = master.gold, masterRice = master.rice,
                ),
            )) {
                is RetainerRules.RetainerSettlement.Leave -> {
                    world.removeRetainer(r.id)
                    world.pushLog(
                        LogEntryDraft(
                            scope = "general", category = "action",
                            text = "<Y>${r.name}</>${JosaUtil.pick(r.name, "이")} 떠났습니다.",
                            generalId = master.id, nationId = master.nationId,
                        ),
                    )
                }
                is RetainerRules.RetainerSettlement.Stay -> {
                    if (out.goldPaid > 0 || out.ricePaid > 0) {
                        applyGeneral(world, recorder, master, master.copy(gold = master.gold - out.goldPaid, rice = master.rice - out.ricePaid))
                    }
                    if (out.loyalty != r.loyalty) world.updateRetainer(r.copy(loyalty = out.loyalty))
                }
            }
        }
    }

    private fun applyGeneral(world: InMemoryTurnWorld, recorder: ChangeRecorder, me: TurnGeneral, next: TurnGeneral) {
        val pre = PerTurnOverlay.toLogicGeneral(me)
        world.applyGeneralDirtyFree(next)
        recorder.diffGeneral(pre, PerTurnOverlay.toLogicGeneral(next))
    }
}

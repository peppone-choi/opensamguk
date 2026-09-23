package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.input.HwihaRenownEvents
import opensamguk.logic.input.RewardRequest
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.war.hwiha.HwihaS3Provisional

/**
 * 상사(賞賜) 실행 — 조정 결정(`court.reward`). 휘하 인물 카드를 직접 거느린 장수가 카드가 있는 곳의 창고망
 * ([HwihaWarehouseNetwork])에서 금을 내리고, 카드 충성을 금 [HwihaS3Provisional.REWARD_MONEY_PER_LOYALTY] 마다 +1
 * (한 번에 최대 [HwihaS3Provisional.REWARD_MAX_LOYALTY_GAIN]) 올리며, 받는 인물에게 결속 사건을 월 1회 기록한다.
 * 보물 상사(#788)는 아직 없다.
 */
class HwihaRewardExecutor(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder) {
    enum class Failure(val message: String) {
        WRONG_RULE_PROFILE("이 세계에서는 상사를 내릴 수 없습니다."),
        CARD_UNAVAILABLE("직접 거느린 인물 카드에만 상사를 내릴 수 있습니다."),
        TOO_SMALL("상사 금이 너무 적어 충성이 오르지 않습니다."),
        INSUFFICIENT_STOCK("카드가 있는 곳의 창고에 금이 모자랍니다."),
    }

    fun reward(request: RewardRequest): Failure? {
        if (world.ruleProfile != RuleProfile.HWIHA) return Failure.WRONG_RULE_PROFILE
        val card = world.getRetainerById(request.retainerId)?.takeIf { it.masterGeneralId == request.actorId }
            ?: return Failure.CARD_UNAVAILABLE
        val person = card.generalId?.let(world::getGeneralById) ?: return Failure.CARD_UNAVAILABLE
        val actor = world.getGeneralById(request.actorId) ?: return Failure.CARD_UNAVAILABLE
        val gain = minOf(request.money / HwihaS3Provisional.REWARD_MONEY_PER_LOYALTY,
            HwihaS3Provisional.REWARD_MAX_LOYALTY_GAIN.toLong()).toInt()
        if (gain <= 0) return Failure.TOO_SMALL
        val network = HwihaWarehouseNetwork(world, recorder)
        if (!network.payMoney(actor.nationId, network.countiesFor(actor.nationId, person.cityId), request.money))
            return Failure.INSUFFICIENT_STOCK
        world.updateRetainer(card.copy(loyalty = (card.loyalty + gain).coerceAtMost(100)))
        val state = world.getState()
        HwihaRenownEvents.record(person.meta, HwihaRenownEvents.Kind.REWARD_RECEIVED, state.currentYear, state.currentMonth)
            ?.let { meta ->
                val after = person.copy(meta = meta)
                recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(person), PerTurnOverlay.toLogicGeneral(after))
                world.applyGeneralDirtyFree(after)
            }
        world.pushLog(LogEntryDraft(scope = "general", category = "action",
            text = "${person.name}에게 금 ${request.money}을 상으로 내렸습니다.", generalId = actor.id, nationId = actor.nationId))
        return null
    }
}

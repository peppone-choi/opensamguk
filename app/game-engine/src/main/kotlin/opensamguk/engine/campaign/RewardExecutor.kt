package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.input.RewardRequest
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.record.AudienceTarget
import opensamguk.logic.record.EventFact
import opensamguk.logic.record.EventKey
import opensamguk.logic.record.EventKind
import opensamguk.logic.record.EventRef
import opensamguk.logic.record.FactRole
import opensamguk.logic.record.RefRole
import opensamguk.logic.record.RewardReasonCode
import opensamguk.logic.renown.RenownEventSource
import opensamguk.logic.war.CampaignBalance

/**
 * 상사(賞賜) 실행 — 조정 결정(`court.reward`). 휘하 인물 카드를 직접 거느린 장수가 카드가 있는 곳의 창고망
 * ([WarehouseNetwork])에서 금을 내리고, 카드 충성을 금 [CampaignBalance.REWARD_MONEY_PER_LOYALTY] 마다 +1
 * (한 번에 최대 [CampaignBalance.REWARD_MAX_LOYALTY_GAIN]) 올리며, 받는 인물에게 결속 사건을 월 1회 기록한다.
 * 보물 상사(#788)는 아직 없다.
 */
class RewardExecutor(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder) {
    enum class Failure(val message: String) {
        WRONG_RULE_PROFILE("이 세계에서는 상사를 내릴 수 없습니다."),
        CARD_UNAVAILABLE("직접 거느린 인물 카드에만 상사를 내릴 수 있습니다."),
        TOO_SMALL("상사 금이 너무 적어 충성이 오르지 않습니다."),
        INSUFFICIENT_STOCK("카드가 있는 곳의 창고에 금이 모자랍니다."),
        STATE_UNAVAILABLE("저장된 상사 이력을 확인할 수 없습니다."),
    }

    fun reward(request: RewardRequest, rewardId: String? = null,
        reason: RewardReasonCode = RewardReasonCode.ROUTINE_SERVICE, automatic: Boolean = false): Failure? {
        if (world.ruleProfile != RuleProfile.HWIHA) return Failure.WRONG_RULE_PROFILE
        val card = world.getRetainerById(request.retainerId)?.takeIf { it.masterGeneralId == request.actorId }
            ?: return Failure.CARD_UNAVAILABLE
        val person = card.generalId?.let(world::getGeneralById) ?: return Failure.CARD_UNAVAILABLE
        val actor = world.getGeneralById(request.actorId) ?: return Failure.CARD_UNAVAILABLE
        require(rewardId == null || rewardId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        val history = try { RewardHistory.read(person.meta) }
            catch (_: IllegalArgumentException) { return Failure.STATE_UNAVAILABLE }
        if (rewardId != null && history?.lastId == rewardId) return null
        if (history?.count == Int.MAX_VALUE) return Failure.STATE_UNAVAILABLE
        val gain = minOf(request.money / CampaignBalance.REWARD_MONEY_PER_LOYALTY,
            CampaignBalance.REWARD_MAX_LOYALTY_GAIN.toLong()).toInt()
        if (gain <= 0) return Failure.TOO_SMALL
        val network = WarehouseNetwork(world, recorder)
        if (!network.payMoney(actor.nationId, network.countiesFor(actor.nationId, person.cityId), request.money))
            return Failure.INSUFFICIENT_STOCK
        world.updateRetainer(card.copy(loyalty = (card.loyalty + gain).coerceAtMost(100)))
        val turn = world.getState()
        val next = RewardHistory((history?.count ?: 0) + 1, turn.currentYear, turn.currentMonth, rewardId ?: "")
        val updated = person.copy(meta = person.meta + (RewardHistory.META_KEY to next.toMetaValue()))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(person), PerTurnOverlay.toLogicGeneral(updated))
        world.applyGeneralDirtyFree(updated)
        if (automatic) {
            val stamped = actor.copy(meta = actor.meta + (RewardHistory.NPC_TURN_KEY to
                RewardHistory.turnStamp(turn.currentYear, turn.currentMonth, turn.currentPhase)))
            recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(actor), PerTurnOverlay.toLogicGeneral(stamped))
            world.applyGeneralDirtyFree(stamped)
        }
        // 결속 사건(상사) — 기록 스트림의 월단평 사건 집계. 같은 달 같은 종류는 한 건이다.
        RenownEventRecorder(world, recorder).record(person.id, RenownEventSource.REWARD)
        world.recordEvent(
            kind = EventKind.REWARD_RECEIVED,
            audience = AudienceTarget.Self(person.id),
            eventKey = EventKey.derive("court.rewardReceived", world.worldId.value.toString(),
                card.id.toString(), person.id.toString(), next.count.toString()),
            refs = mapOf(RefRole.ISSUER to EventRef.General(actor.id),
                RefRole.TARGET to EventRef.General(person.id)),
            facts = mapOf(FactRole.MONEY to EventFact.Amount(request.money),
                FactRole.REASON to EventFact.RewardReason(reason)),
        )
        world.pushLog(LogEntryDraft(scope = "general", category = "action",
            text = "${person.name}에게 금 ${request.money}을 상으로 내렸습니다.", generalId = actor.id, nationId = actor.nationId))
        return null
    }
}

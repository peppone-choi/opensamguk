package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.domain.NpcType
import opensamguk.logic.input.*
import opensamguk.logic.record.RewardReasonCode

/** One affordable direct-card reward on an NPC lord's turn, oldest unrewarded card first. */
internal object NpcRewardSelector {
    private val catalog by lazy { InputCatalog.load() }
    data class Choice(val request: RewardRequest, val id: String, val reason: RewardReasonCode)

    fun select(world: InMemoryTurnWorld, issuerId: Int, recorder: ChangeRecorder): Choice? {
        if (world.ruleProfile != RuleProfile.HWIHA ||
            !AiPolicyRegistry.selectable(catalog, RewardInput.INPUT_ID, AiSelectorKey.COURT_REWARD)) return null
        val issuer = world.getGeneralById(issuerId) ?: return null
        if (issuer.npcState != NpcType.NPC_LITE || issuer.nationId <= 0 ||
            issuer.meta[LordStatus.META_KEY] != true ||
            (!issuer.userId.isNullOrBlank() && issuer.userId.toLongOrNull()?.let { it <= 0 } != true) ||
            QueuedReward.META_KEY in issuer.meta || world.listRetainers().any { it.generalId == issuerId }) return null
        val turn = world.getState()
        if (RewardHistory.NPC_TURN_KEY in issuer.meta) {
            val stamp = issuer.meta[RewardHistory.NPC_TURN_KEY] as? String ?: return null
            if (!stamp.matches(Regex("[0-9]{4}-[0-9]{2}-[1-3]"))) return null
            if (stamp == RewardHistory.turnStamp(turn.currentYear, turn.currentMonth, turn.currentPhase)) return null
        }
        val cards = world.listRetainers().filter { it.masterGeneralId == issuerId && it.generalId != null }
        val bindingCounts = world.listRetainers().mapNotNull { it.generalId }.groupingBy { it }.eachCount()
        val network = WarehouseNetwork(world, recorder)
        return cards.mapNotNull { card ->
            val target = card.generalId?.let(world::getGeneralById) ?: return@mapNotNull null
            if (bindingCounts[target.id] != 1 || target.nationId != issuer.nationId ||
                target.meta[LordStatus.META_KEY] != false || card.loyalty >= 100) return@mapNotNull null
            val history = try { RewardHistory.read(target.meta) }
                catch (_: IllegalArgumentException) { return@mapNotNull null }
            if (history != null && history.year == turn.currentYear && history.month == turn.currentMonth)
                return@mapNotNull null
            val counties = network.countiesFor(issuer.nationId, target.cityId)
            val available = network.moneyIn(counties)
            val amount = (minOf(5, 100 - card.loyalty, (available / 100).coerceAtMost(5).toInt()) * 100).toLong()
            if (amount < 100) return@mapNotNull null
            Triple(card, history, amount)
        }.sortedWith(compareBy<Triple<opensamguk.engine.turn.Retainer, RewardHistory?, Long>>
            { it.second?.year ?: 0 }.thenBy { it.second?.month ?: 0 }.thenBy { it.first.id })
            .firstOrNull()?.let { (card, _, amount) ->
                val targetId = checkNotNull(card.generalId)
                Choice(RewardRequest(issuerId, card.id, amount),
                    "npc-reward:${world.worldId.value}:$issuerId:$targetId:${turn.currentYear}:${turn.currentMonth}",
                    if (card.loyalty < 70) RewardReasonCode.LOYALTY_SUPPORT else RewardReasonCode.ROUTINE_SERVICE)
            }
    }
}

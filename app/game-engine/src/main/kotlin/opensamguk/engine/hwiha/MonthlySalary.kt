package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.renown.RenownRules
import opensamguk.logic.war.CampaignBalance

/**
 * 월 경계 녹봉(재설계 spec §5.2 4단계 「수입 → 녹봉·유지비 지급(보급망에서, 못 받으면 충성 하락)」, §9.2).
 *
 * 인물 카드(장수가 붙은 휘하 카드)마다 그 카드를 **직접 거느린 장수**가 명망 코스트 × [CampaignBalance.SALARY_MONEY_PER_RENOWN_COST]
 * 금을 카드가 있는 곳의 창고망([WarehouseNetwork])에서 낸다. 못 내면 한 푼도 빼지 않고 충성을 깎는다.
 * 기존 가신 월 유지비(30/30, RetainerMonthlyService)는 HWIHA 에서 꺼져 있다 — 두 재정을 함께 켜지 않는다.
 * 도장([STAMP_KEY])으로 한 달에 한 번만 돈다(징세와 같은 방식, 같은 flush).
 */
class MonthlySalary(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder) {
    data class Outcome(val stamp: String, val alreadyStamped: Boolean, val paid: Int = 0, val unpaid: Int = 0, val money: Long = 0)

    fun pay(year: Int, month: Int): Outcome? {
        if (world.ruleProfile != RuleProfile.HWIHA) return null
        val stamp = "%04d-%02d".format(year, month)
        if (world.getState().meta[STAMP_KEY] == stamp) return Outcome(stamp, alreadyStamped = true)
        val network = WarehouseNetwork(world, recorder)
        var paid = 0; var unpaid = 0; var money = 0L
        for (card in world.listRetainers().sortedBy { it.id }) {
            val person = card.generalId?.let(world::getGeneralById) ?: continue
            val master = world.getGeneralById(card.masterGeneralId) ?: continue
            val cost = try {
                RenownRules.personCost(person.stats.leadership, person.stats.strength, person.stats.intelligence,
                    person.stats.politics, person.stats.charm)
            } catch (_: IllegalArgumentException) { continue }
            val amount = cost.toLong() * CampaignBalance.SALARY_MONEY_PER_RENOWN_COST
            if (network.payMoney(master.nationId, network.countiesFor(master.nationId, person.cityId), amount)) {
                paid++; money += amount
            } else {
                unpaid++
                val loyalty = (card.loyalty - CampaignBalance.UNPAID_SALARY_LOYALTY_LOSS).coerceAtLeast(0)
                if (loyalty != card.loyalty) world.updateRetainer(card.copy(loyalty = loyalty))
                world.pushLog(LogEntryDraft(scope = "general", category = "action",
                    text = "${person.name}에게 녹봉을 주지 못해 충성이 떨어졌습니다.", generalId = master.id, nationId = master.nationId))
            }
        }
        world.setGameEnvValue(STAMP_KEY, stamp)
        recorder.recordKv("game_env", "game_env", STAMP_KEY, stamp)
        return Outcome(stamp, alreadyStamped = false, paid, unpaid, money)
    }

    companion object {
        const val STAMP_KEY = "hwihaSalaryMonth"
    }
}

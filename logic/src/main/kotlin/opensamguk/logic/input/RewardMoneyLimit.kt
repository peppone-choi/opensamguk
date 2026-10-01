package opensamguk.logic.input

import opensamguk.logic.war.CampaignBalance

/** 현재 충성에서 한 번에 내릴 수 있는 최대 상사 금. 충성 100에서도 기록용 최소 금은 허용한다. */
object RewardMoneyLimit {
    fun maximumFor(loyalty: Int): Long {
        val loyaltyRoom = (100 - loyalty).coerceIn(0, CampaignBalance.REWARD_MAX_LOYALTY_GAIN)
        return maxOf(CampaignBalance.REWARD_MONEY_PER_LOYALTY,
            loyaltyRoom.toLong() * CampaignBalance.REWARD_MONEY_PER_LOYALTY)
    }
}

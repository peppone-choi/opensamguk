package opensamguk.gameapi.court.reward

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.ALWAYS)
data class RewardOptionsDto(
    val status: String, val reason: String?, val generalId: Int,
    val snapshot: RewardSnapshotDto?, val rule: RewardRuleDto?, val queued: RewardQueuedDto?,
    val cards: List<RewardCardDto>?, val preview: RewardPreviewDto?,
) {
    companion object {
        fun unavailable(generalId: Int, reason: String, status: String = "UNAVAILABLE") =
            RewardOptionsDto(status, reason, generalId, null, null, null, null, null)
    }
}

@JsonInclude(JsonInclude.Include.ALWAYS)
data class RewardSnapshotDto(val year: Int, val month: Int, val phase: Int)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class RewardRuleDto(val moneyPerLoyalty: Long, val maxLoyaltyGainPerReward: Int, val minimumMoney: Long,
    val inputMaximumMoney: Long, val loyaltyCap: Int)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class RewardQueuedDto(val status: String, val retainerId: Int?, val money: Long?)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class RewardCardDto(val retainerId: Int, val recipientGeneralId: Int, val name: String?, val loyalty: Int,
    val loyaltyRoom: Int, val maximumMoney: Long, val locationCityId: Int?, val funding: RewardFundingDto)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class RewardFundingDto(val status: String, val scope: String?, val noneReason: String?,
    val unavailableReason: String?, val usableMoney: String?, val warehouseCount: Int?)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class RewardPreviewDto(val retainerId: Int, val money: Long?, val verdict: String, val loyaltyGain: Int?,
    val loyaltyAfter: Int?, val moneyWithoutGain: Long?, val usableMoney: String?,
    val debitPlan: List<RewardDebitDto>?, val notChecked: List<String>)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class RewardDebitDto(val cityId: Int, val isCapital: Boolean, val take: String, val balance: String,
    val revision: String)

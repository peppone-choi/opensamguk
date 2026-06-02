package opensamguk.common.wire

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/**
 * Faithful port of `TurnDaemonCommandResult` (`turnDaemon/types.ts:188-371`).
 *
 * Unlike [TurnDaemonCommand], results share a `type` but split into Ok / Fail on the
 * boolean `ok` field (e.g. `auctionFinalize` Ok = `{type, ok, auctionId}`; Fail adds
 * `reason`). A single kotlinx `classDiscriminator = "type"` cannot model that, so this
 * file uses a [JsonContentPolymorphicSerializer] keyed on `(type, ok)`. Every concrete
 * class re-emits `type` and `ok` so encode round-trips.
 *
 * The boolean-ok group (`dieOnPrestart/buildNationCandidate/instantRetreat/vacation/
 * setMySetting/dropItem/changePermission/kick/appoint`) collapses to a single
 * [GeneralBoolResult] carrying `type` + `ok` (boolean) + `generalId` + nullable `reason`.
 */
@Serializable(with = TurnDaemonCommandResultSerializer::class)
sealed class TurnDaemonCommandResult {
    abstract val type: String
    abstract val ok: Boolean
}

@Serializable
data class AuctionFinalizeOk(
    override val type: String = "auctionFinalize",
    override val ok: Boolean = true,
    val auctionId: Int,
) : TurnDaemonCommandResult()

@Serializable
data class AuctionFinalizeFail(
    override val type: String = "auctionFinalize",
    override val ok: Boolean = false,
    val auctionId: Int,
    val reason: String,
) : TurnDaemonCommandResult()

@Serializable
data class TroopJoinOk(
    override val type: String = "troopJoin",
    override val ok: Boolean = true,
    val generalId: Int,
    val troopId: Int,
) : TurnDaemonCommandResult()

@Serializable
data class TroopJoinFail(
    override val type: String = "troopJoin",
    override val ok: Boolean = false,
    val generalId: Int,
    val troopId: Int,
    val reason: String,
) : TurnDaemonCommandResult()

@Serializable
data class TroopExitOk(
    override val type: String = "troopExit",
    override val ok: Boolean = true,
    val generalId: Int,
    val wasLeader: Boolean,
) : TurnDaemonCommandResult()

@Serializable
data class TroopExitFail(
    override val type: String = "troopExit",
    override val ok: Boolean = false,
    val generalId: Int,
    val reason: String,
) : TurnDaemonCommandResult()

/**
 * Collapsed boolean-ok group: dieOnPrestart / buildNationCandidate / instantRetreat /
 * vacation / setMySetting / dropItem / changePermission / kick / appoint.
 * `{ type; ok: boolean; generalId; reason? }`.
 */
@Serializable
data class GeneralBoolResult(
    override val type: String,
    override val ok: Boolean,
    val generalId: Int,
    val reason: String? = null,
) : TurnDaemonCommandResult()

@Serializable
data class TournamentRefundOk(
    override val type: String = "tournamentRefund",
    override val ok: Boolean = true,
    val bettingId: Int? = null,
    val processed: Int,
    val missing: Int,
    val totalRefund: Int,
) : TurnDaemonCommandResult()

@Serializable
data class TournamentRefundFail(
    override val type: String = "tournamentRefund",
    override val ok: Boolean = false,
    val bettingId: Int? = null,
    val reason: String,
) : TurnDaemonCommandResult()

@Serializable
data class TournamentBettingPayoutOk(
    override val type: String = "tournamentBettingPayout",
    override val ok: Boolean = true,
    val bettingId: Int? = null,
    val processed: Int,
    val missing: Int,
    val totalPayout: Int,
) : TurnDaemonCommandResult()

@Serializable
data class TournamentBettingPayoutFail(
    override val type: String = "tournamentBettingPayout",
    override val ok: Boolean = false,
    val bettingId: Int? = null,
    val reason: String,
) : TurnDaemonCommandResult()

@Serializable
data class TournamentRewardOk(
    override val type: String = "tournamentReward",
    override val ok: Boolean = true,
    val winnerId: Int,
    val runnerUpId: Int,
    val rewarded: Int,
    val missing: Int,
    val totalGold: Int,
    val totalExp: Int,
) : TurnDaemonCommandResult()

@Serializable
data class TournamentRewardFail(
    override val type: String = "tournamentReward",
    override val ok: Boolean = false,
    val winnerId: Int,
    val runnerUpId: Int,
    val reason: String,
) : TurnDaemonCommandResult()

@Serializable
data class VoteRewardOk(
    override val type: String = "voteReward",
    override val ok: Boolean = true,
    val voteId: Int,
    val generalId: Int,
    val awardedUnique: Boolean,
    val itemKey: String? = null,
    val alreadyApplied: Boolean? = null,
) : TurnDaemonCommandResult()

@Serializable
data class VoteRewardFail(
    override val type: String = "voteReward",
    override val ok: Boolean = false,
    val voteId: Int,
    val generalId: Int,
    val reason: String,
) : TurnDaemonCommandResult()

@Serializable
data class SetNationMetaOk(
    override val type: String = "setNationMeta",
    override val ok: Boolean = true,
    val nationId: Int,
    val updatedAt: String,
) : TurnDaemonCommandResult()

@Serializable
data class SetNationMetaFail(
    override val type: String = "setNationMeta",
    override val ok: Boolean = false,
    val nationId: Int,
    val reason: String,
    val currentUpdatedAt: String? = null,
) : TurnDaemonCommandResult()

@Serializable
data class AdjustGeneralResourcesOk(
    override val type: String = "adjustGeneralResources",
    override val ok: Boolean = true,
    val processed: Int,
    val missing: Int,
    val totalGoldDelta: Int,
    val totalRiceDelta: Int,
) : TurnDaemonCommandResult()

@Serializable
data class AdjustGeneralResourcesFail(
    override val type: String = "adjustGeneralResources",
    override val ok: Boolean = false,
    val reason: String,
) : TurnDaemonCommandResult()

@Serializable
data class AdjustGeneralMetaOk(
    override val type: String = "adjustGeneralMeta",
    override val ok: Boolean = true,
    val processed: Int,
    val missing: Int,
) : TurnDaemonCommandResult()

@Serializable
data class AdjustGeneralMetaFail(
    override val type: String = "adjustGeneralMeta",
    override val ok: Boolean = false,
    val reason: String,
) : TurnDaemonCommandResult()

@Serializable
data class TournamentMatchResultOk(
    override val type: String = "tournamentMatchResult",
    override val ok: Boolean = true,
    val tournamentType: Int,
    val attackerId: Int,
    val defenderId: Int,
    val result: MatchResult,
) : TurnDaemonCommandResult()

@Serializable
data class TournamentMatchResultFail(
    override val type: String = "tournamentMatchResult",
    override val ok: Boolean = false,
    val tournamentType: Int,
    val attackerId: Int,
    val defenderId: Int,
    val result: MatchResult,
    val reason: String,
) : TurnDaemonCommandResult()

@Serializable
data class PatchGeneralOk(
    override val type: String = "patchGeneral",
    override val ok: Boolean = true,
    val generalId: Int,
) : TurnDaemonCommandResult()

@Serializable
data class PatchGeneralFail(
    override val type: String = "patchGeneral",
    override val ok: Boolean = false,
    val generalId: Int,
    val reason: String,
) : TurnDaemonCommandResult()

@Serializable
data class AuctionBidOk(
    override val type: String = "auctionBid",
    override val ok: Boolean = true,
    val auctionId: Int,
    val closeAt: String,
) : TurnDaemonCommandResult()

@Serializable
data class AuctionBidFail(
    override val type: String = "auctionBid",
    override val ok: Boolean = false,
    val auctionId: Int,
    val reason: String,
) : TurnDaemonCommandResult()

@Serializable
data class PlaceBetOk(
    override val type: String = "placeBet",
    override val ok: Boolean = true,
    val bettingId: Int,
    val generalId: Int,
    val amount: Int,
) : TurnDaemonCommandResult()

@Serializable
data class PlaceBetFail(
    override val type: String = "placeBet",
    override val ok: Boolean = false,
    val bettingId: Int,
    val reason: String,
) : TurnDaemonCommandResult()

@Serializable
data class AcceptDiplomaticMessageOk(
    override val type: String = "acceptDiplomaticMessage",
    override val ok: Boolean = true,
    val messageId: Int,
) : TurnDaemonCommandResult()

@Serializable
data class AcceptDiplomaticMessageFail(
    override val type: String = "acceptDiplomaticMessage",
    override val ok: Boolean = false,
    val messageId: Int,
    val reason: String,
) : TurnDaemonCommandResult()

@Serializable
data class DeclineDiplomaticMessageOk(
    override val type: String = "declineDiplomaticMessage",
    override val ok: Boolean = true,
    val messageId: Int,
) : TurnDaemonCommandResult()

@Serializable
data class DeclineDiplomaticMessageFail(
    override val type: String = "declineDiplomaticMessage",
    override val ok: Boolean = false,
    val messageId: Int,
    val reason: String,
) : TurnDaemonCommandResult()

// ── F4 Wave C2 (slice A) — single-actor intake command results ──────────────────────────────────
// The 7 nation-finance setters + tournament enroll collapse to a single [NationSettingResult]
// ({type, ok, generalId, nationId?, reason?}); each inheritance reset has its own Ok/Fail pair
// carrying the spent points (the UI shows the deduction).

@Serializable
data class NationSettingResult(
    override val type: String,
    override val ok: Boolean,
    val generalId: Int,
    val nationId: Int? = null,
    val reason: String? = null,
) : TurnDaemonCommandResult()

@Serializable
data class InheritResetTurnTimeOk(
    override val type: String = "inheritResetTurnTime",
    override val ok: Boolean = true,
    val generalId: Int,
    val spent: Int,
) : TurnDaemonCommandResult()

@Serializable
data class InheritResetTurnTimeFail(
    override val type: String = "inheritResetTurnTime",
    override val ok: Boolean = false,
    val generalId: Int,
    val reason: String,
) : TurnDaemonCommandResult()

@Serializable
data class InheritResetSpecialWarOk(
    override val type: String = "inheritResetSpecialWar",
    override val ok: Boolean = true,
    val generalId: Int,
    val spent: Int,
) : TurnDaemonCommandResult()

@Serializable
data class InheritResetSpecialWarFail(
    override val type: String = "inheritResetSpecialWar",
    override val ok: Boolean = false,
    val generalId: Int,
    val reason: String,
) : TurnDaemonCommandResult()

@Serializable
data class InheritSetNextSpecialWarOk(
    override val type: String = "inheritSetNextSpecialWar",
    override val ok: Boolean = true,
    val generalId: Int,
    val spent: Int,
) : TurnDaemonCommandResult()

@Serializable
data class InheritSetNextSpecialWarFail(
    override val type: String = "inheritSetNextSpecialWar",
    override val ok: Boolean = false,
    val generalId: Int,
    val reason: String,
) : TurnDaemonCommandResult()

/**
 * The nation-finance setters + tournament enroll all carry the same shape — collapse to
 * [NationSettingResult] (mirrors the [GeneralBoolResult] collapse) keyed on `type` regardless of `ok`.
 */
private val NATION_SETTING_TYPES = setOf(
    "setNotice", "setScoutMsg", "setRate", "setBill", "setSecretLimit",
    "setBlockWar", "setBlockScout", "tournamentEnroll",
)

private val BOOLEAN_OK_TYPES = setOf(
    "dieOnPrestart", "buildNationCandidate", "instantRetreat", "vacation",
    "setMySetting", "dropItem", "changePermission", "kick", "appoint",
)

/**
 * Custom `(type, ok)` selector. [JsonContentPolymorphicSerializer] cannot be used because
 * (a) it only models decode, and (b) the concrete classes carry `type`/`ok` as default-valued
 * fields that [WireJson] (`encodeDefaults = false`) would otherwise omit. This serializer forces
 * those discriminators to be emitted on encode by re-encoding through an `encodeDefaults = true`
 * codec.
 */
object TurnDaemonCommandResultSerializer : KSerializer<TurnDaemonCommandResult> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("opensamguk.common.wire.TurnDaemonCommandResult")

    private val emitJson = Json { encodeDefaults = true; explicitNulls = false }

    private fun selectSerializer(type: String, ok: Boolean): KSerializer<out TurnDaemonCommandResult> {
        if (type in BOOLEAN_OK_TYPES) {
            return GeneralBoolResult.serializer()
        }
        if (type in NATION_SETTING_TYPES) {
            return NationSettingResult.serializer()
        }
        return when (type) {
            "inheritResetTurnTime" -> if (ok) InheritResetTurnTimeOk.serializer() else InheritResetTurnTimeFail.serializer()
            "inheritResetSpecialWar" -> if (ok) InheritResetSpecialWarOk.serializer() else InheritResetSpecialWarFail.serializer()
            "inheritSetNextSpecialWar" -> if (ok) InheritSetNextSpecialWarOk.serializer() else InheritSetNextSpecialWarFail.serializer()
            "auctionFinalize" -> if (ok) AuctionFinalizeOk.serializer() else AuctionFinalizeFail.serializer()
            "troopJoin" -> if (ok) TroopJoinOk.serializer() else TroopJoinFail.serializer()
            "troopExit" -> if (ok) TroopExitOk.serializer() else TroopExitFail.serializer()
            "tournamentRefund" -> if (ok) TournamentRefundOk.serializer() else TournamentRefundFail.serializer()
            "tournamentBettingPayout" -> if (ok) TournamentBettingPayoutOk.serializer() else TournamentBettingPayoutFail.serializer()
            "tournamentReward" -> if (ok) TournamentRewardOk.serializer() else TournamentRewardFail.serializer()
            "voteReward" -> if (ok) VoteRewardOk.serializer() else VoteRewardFail.serializer()
            "setNationMeta" -> if (ok) SetNationMetaOk.serializer() else SetNationMetaFail.serializer()
            "adjustGeneralResources" -> if (ok) AdjustGeneralResourcesOk.serializer() else AdjustGeneralResourcesFail.serializer()
            "adjustGeneralMeta" -> if (ok) AdjustGeneralMetaOk.serializer() else AdjustGeneralMetaFail.serializer()
            "tournamentMatchResult" -> if (ok) TournamentMatchResultOk.serializer() else TournamentMatchResultFail.serializer()
            "patchGeneral" -> if (ok) PatchGeneralOk.serializer() else PatchGeneralFail.serializer()
            "auctionBid" -> if (ok) AuctionBidOk.serializer() else AuctionBidFail.serializer()
            "placeBet" -> if (ok) PlaceBetOk.serializer() else PlaceBetFail.serializer()
            "acceptDiplomaticMessage" -> if (ok) AcceptDiplomaticMessageOk.serializer() else AcceptDiplomaticMessageFail.serializer()
            "declineDiplomaticMessage" -> if (ok) DeclineDiplomaticMessageOk.serializer() else DeclineDiplomaticMessageFail.serializer()
            else -> throw IllegalArgumentException("unknown result type=$type")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun concreteSerializer(value: TurnDaemonCommandResult): KSerializer<TurnDaemonCommandResult> =
        selectSerializer(value.type, value.ok) as KSerializer<TurnDaemonCommandResult>

    override fun deserialize(decoder: Decoder): TurnDaemonCommandResult {
        val input = decoder as JsonDecoder
        val element = input.decodeJsonElement() as JsonObject
        val type = element["type"]!!.jsonPrimitive.content
        val ok = element["ok"]!!.jsonPrimitive.boolean
        @Suppress("UNCHECKED_CAST")
        val ser = selectSerializer(type, ok) as KSerializer<TurnDaemonCommandResult>
        return input.json.decodeFromJsonElement(ser, element)
    }

    override fun serialize(encoder: Encoder, value: TurnDaemonCommandResult) {
        val output = encoder as JsonEncoder
        // Re-encode through an encodeDefaults=true codec so `type`/`ok` (default-valued on the
        // concrete classes) are always present, then splice the resulting tree into the stream.
        val tree = emitJson.encodeToJsonElement(concreteSerializer(value), value)
        output.encodeJsonElement(tree)
    }
}

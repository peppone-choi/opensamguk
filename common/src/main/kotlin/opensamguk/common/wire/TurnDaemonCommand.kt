package opensamguk.common.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Faithful port of the `TurnDaemonCommand` discriminated union (`turnDaemon/types.ts:43-186`).
 *
 * Single `type` discriminator (kotlinx default via [WireJson.classDiscriminator]). All ~28
 * variants are transcribed for forward-compat; P0-B wires no handlers. `requestId` is optional
 * on the variants that carry it in TS.
 */
@Serializable
sealed class TurnDaemonCommand {
    abstract val type: String

    @Serializable
    @SerialName("run")
    data class Run(
        val reason: RunReason,
        val targetTime: String? = null,
        val budget: TurnRunBudget? = null,
    ) : TurnDaemonCommand() {
        override val type: String get() = "run"
    }

    @Serializable
    @SerialName("pause")
    data class Pause(val reason: String? = null) : TurnDaemonCommand() {
        override val type: String get() = "pause"
    }

    @Serializable
    @SerialName("resume")
    data class Resume(val reason: String? = null) : TurnDaemonCommand() {
        override val type: String get() = "resume"
    }

    @Serializable
    @SerialName("shutdown")
    data class Shutdown(val reason: String? = null) : TurnDaemonCommand() {
        override val type: String get() = "shutdown"
    }

    @Serializable
    @SerialName("getStatus")
    data class GetStatus(val requestId: String? = null) : TurnDaemonCommand() {
        override val type: String get() = "getStatus"
    }

    @Serializable
    @SerialName("troopJoin")
    data class TroopJoin(
        val requestId: String? = null,
        val generalId: Int,
        val troopId: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "troopJoin"
    }

    @Serializable
    @SerialName("troopExit")
    data class TroopExit(
        val requestId: String? = null,
        val generalId: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "troopExit"
    }

    @Serializable
    @SerialName("dieOnPrestart")
    data class DieOnPrestart(
        val requestId: String? = null,
        val generalId: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "dieOnPrestart"
    }

    @Serializable
    @SerialName("buildNationCandidate")
    data class BuildNationCandidate(
        val requestId: String? = null,
        val generalId: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "buildNationCandidate"
    }

    @Serializable
    @SerialName("instantRetreat")
    data class InstantRetreat(
        val requestId: String? = null,
        val generalId: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "instantRetreat"
    }

    @Serializable
    @SerialName("vacation")
    data class Vacation(
        val requestId: String? = null,
        val generalId: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "vacation"
    }

    @Serializable
    @SerialName("setMySetting")
    data class SetMySetting(
        val requestId: String? = null,
        val generalId: Int,
        val settings: MySettings,
    ) : TurnDaemonCommand() {
        override val type: String get() = "setMySetting"
    }

    @Serializable
    @SerialName("dropItem")
    data class DropItem(
        val requestId: String? = null,
        val generalId: Int,
        val itemType: String,
    ) : TurnDaemonCommand() {
        override val type: String get() = "dropItem"
    }

    @Serializable
    @SerialName("auctionFinalize")
    data class AuctionFinalize(
        val requestId: String? = null,
        val auctionId: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "auctionFinalize"
    }

    @Serializable
    @SerialName("changePermission")
    data class ChangePermission(
        val requestId: String? = null,
        val generalId: Int,
        val isAmbassador: Boolean,
        val targetGeneralIds: List<Int>,
    ) : TurnDaemonCommand() {
        override val type: String get() = "changePermission"
    }

    @Serializable
    @SerialName("kick")
    data class Kick(
        val requestId: String? = null,
        val generalId: Int,
        val destGeneralId: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "kick"
    }

    @Serializable
    @SerialName("appoint")
    data class Appoint(
        val requestId: String? = null,
        val generalId: Int,
        val destGeneralId: Int,
        val destCityId: Int,
        val officerLevel: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "appoint"
    }

    @Serializable
    @SerialName("tournamentRefund")
    data class TournamentRefund(
        val requestId: String? = null,
        val bettingId: Int? = null,
        val reason: String? = null,
        val refunds: List<AmountEntry>,
    ) : TurnDaemonCommand() {
        override val type: String get() = "tournamentRefund"
    }

    @Serializable
    @SerialName("tournamentBettingPayout")
    data class TournamentBettingPayout(
        val requestId: String? = null,
        val bettingId: Int? = null,
        val reason: String? = null,
        val payouts: List<AmountEntry>,
    ) : TurnDaemonCommand() {
        override val type: String get() = "tournamentBettingPayout"
    }

    @Serializable
    @SerialName("tournamentReward")
    data class TournamentReward(
        val requestId: String? = null,
        val tournamentType: Int,
        val winnerId: Int,
        val runnerUpId: Int,
        val top16: List<Int>,
        val top8: List<Int>,
        val top4: List<Int>,
    ) : TurnDaemonCommand() {
        override val type: String get() = "tournamentReward"
    }

    @Serializable
    @SerialName("voteReward")
    data class VoteReward(
        val requestId: String? = null,
        val voteId: Int,
        val generalId: Int,
        val goldReward: Int,
        val unique: VoteUnique? = null,
    ) : TurnDaemonCommand() {
        override val type: String get() = "voteReward"
    }

    @Serializable
    @SerialName("setNationMeta")
    data class SetNationMeta(
        val requestId: String? = null,
        val nationId: Int,
        val updates: Map<String, kotlinx.serialization.json.JsonElement>,
        val expectedUpdatedAt: String? = null,
    ) : TurnDaemonCommand() {
        override val type: String get() = "setNationMeta"
    }

    @Serializable
    @SerialName("adjustGeneralResources")
    data class AdjustGeneralResources(
        val requestId: String? = null,
        val reason: String? = null,
        val adjustments: List<ResourceAdj>,
    ) : TurnDaemonCommand() {
        override val type: String get() = "adjustGeneralResources"
    }

    @Serializable
    @SerialName("adjustGeneralMeta")
    data class AdjustGeneralMeta(
        val requestId: String? = null,
        val reason: String? = null,
        val adjustments: List<MetaAdj>,
    ) : TurnDaemonCommand() {
        override val type: String get() = "adjustGeneralMeta"
    }

    @Serializable
    @SerialName("tournamentMatchResult")
    data class TournamentMatchResult(
        val requestId: String? = null,
        val tournamentType: Int,
        val attackerId: Int,
        val defenderId: Int,
        val result: MatchResult,
    ) : TurnDaemonCommand() {
        override val type: String get() = "tournamentMatchResult"
    }

    @Serializable
    @SerialName("patchGeneral")
    data class PatchGeneral(
        val requestId: String? = null,
        val generalId: Int,
        val patch: GeneralPatch,
    ) : TurnDaemonCommand() {
        override val type: String get() = "patchGeneral"
    }

    @Serializable
    @SerialName("auctionBid")
    data class AuctionBid(
        val requestId: String? = null,
        val auctionId: Int,
        val generalId: Int,
        val amount: Int,
        val tryExtendCloseDate: Boolean? = null,
    ) : TurnDaemonCommand() {
        override val type: String get() = "auctionBid"
    }
}

@Serializable
data class MySettings(
    val tnmt: Int? = null,
    @SerialName("defence_train") val defenceTrain: Int? = null,
    @SerialName("use_treatment") val useTreatment: Int? = null,
    @SerialName("use_auto_nation_turn") val useAutoNationTurn: Int? = null,
)

@Serializable
data class AmountEntry(
    val generalId: Int,
    val amount: Int,
)

@Serializable
data class VoteUnique(
    val expected: Boolean,
    val itemKey: String? = null,
)

@Serializable
data class ResourceAdj(
    val generalId: Int,
    val goldDelta: Int? = null,
    val riceDelta: Int? = null,
)

@Serializable
data class MetaAdj(
    val generalId: Int,
    val metaDelta: Map<String, Int>,
)

@Serializable
data class GeneralPatch(
    val meta: Map<String, kotlinx.serialization.json.JsonElement>? = null,
    val turnTime: String? = null,
    val stats: PatchStats? = null,
    val specialWar: String? = null,
)

@Serializable
data class PatchStats(
    val leadership: Int? = null,
    val strength: Int? = null,
    val intelligence: Int? = null,
)

@Serializable
enum class MatchResult {
    @SerialName("attacker") ATTACKER,
    @SerialName("defender") DEFENDER,
    @SerialName("draw") DRAW,
}

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

    // F4 Wave C2 (slice B) — troop intake (NewTroop / KickFromTroop / SetTroopName).
    @Serializable
    @SerialName("troopNew")
    data class TroopNew(
        val requestId: String? = null,
        val generalId: Int,
        val troopName: String,
    ) : TurnDaemonCommand() {
        override val type: String get() = "troopNew"
    }

    @Serializable
    @SerialName("troopKick")
    data class TroopKick(
        val requestId: String? = null,
        // Acting (kicking) general — controller-resolved. NOTE: KickFromTroop.php::launch does NOT
        // consult the session general; the guard runs purely on the target + troopId (see
        // TroopActions.kickFromTroop). Carried for the command envelope/ownership only.
        val generalId: Int,
        val troopId: Int,
        // PHP args['generalID'] — the general to remove from the troop.
        val targetGeneralId: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "troopKick"
    }

    @Serializable
    @SerialName("troopSetName")
    data class TroopSetName(
        val requestId: String? = null,
        val generalId: Int,
        val troopId: Int,
        val troopName: String,
    ) : TurnDaemonCommand() {
        override val type: String get() = "troopSetName"
    }

    // F4 Wave C2 (slice C) — board intake (회의실/기밀실 글/댓글). title/text are nullable to preserve
    // the PHP null(absent)-vs-blank distinction (Util::getPost returns null when the field is absent).
    @Serializable
    @SerialName("boardArticle")
    data class BoardArticle(
        val requestId: String? = null,
        val generalId: Int,
        val isSecret: Boolean = false,
        val title: String? = null,
        val text: String? = null,
    ) : TurnDaemonCommand() {
        override val type: String get() = "boardArticle"
    }

    @Serializable
    @SerialName("boardComment")
    data class BoardComment(
        val requestId: String? = null,
        val generalId: Int,
        // PHP `Util::getPost('articleNo','int')`의 null(부재)-vs-존재를 보존하려 nullable — 댓글 추가의
        // 1차 게이트가 `$articleNo === null || $text === null`이다 (j_board_comment_add.php).
        val articleNo: Int? = null,
        val text: String? = null,
    ) : TurnDaemonCommand() {
        override val type: String get() = "boardComment"
    }

    // F4 Wave 투표 인테이크 — 설문조사(vote) 개설/투표/댓글/마감. 게시판(boardArticle/boardComment)과
    // 동일한 즉시-인테이크 경로: game-api 인테이크 → 명령 스트림 → TurnDaemonCommandDispatcher →
    // VoteHandler → InMemoryTurnWorld 변이 → ChangeRecorder 델타 → JdbcFlushExecutor flush.
    // VoteReward(추첨 보상)는 이미 ~line 269에 존재 — 재사용한다(여기서 중복 정의하지 않음).

    /**
     * 설문조사 개설 (NewVote.php::launch). 권한(vote ACL OR userGrade≥5) 보유 장수가 새 vote_poll을
     * 연다. title은 필수(lengthMin 1); [multipleOptions]는 PHP `?? 1` → `<0이면 0` → `valueFit(0, count)`
     * 클램프; [endDate]는 nullable(부재 시 무기한); [keepOldVote] false(기본)면 직전 vote를 closeOldVote로
     * 마감한다. [options]는 stringArray — 비어 있으면 '항목이 없습니다.' deny.
     */
    @Serializable
    @SerialName("newVote")
    data class NewVote(
        val requestId: String? = null,
        val generalId: Int,
        val title: String,
        val options: List<String> = emptyList(),
        // PHP `$multipleOptions = $this->args['multipleOptions'] ?? 1` — 부재 시 1. nullable로 유지해
        // 엔진이 PHP 기본값/클램프(<0→0, valueFit 0..count)를 직접 적용하게 한다.
        val multipleOptions: Int? = null,
        // PHP `$endDate = $this->args['endDate'] ?? null` — 부재(무기한) vs 지정을 보존하려 nullable.
        val endDate: String? = null,
        // PHP `$this->args['keepOldVote'] ?? false` — true면 직전 vote를 자동 마감하지 않는다.
        val keepOldVote: Boolean? = null,
    ) : TurnDaemonCommand() {
        override val type: String get() = "newVote"
    }

    /**
     * 설문조사 투표 (Vote.php::launch). [voteId]의 vote_poll에 [selection](항목 인덱스 배열)을 던진다.
     * PHP: 빈 선택 deny, 종료일 경과 deny, multipleOptions 초과 deny, 범위 밖 인덱스 deny,
     * `sort($selection, SORT_NUMERIC)` 후 `insertIgnore('vote', …)` (UNIQUE(vote_id,general_id) →
     * affectedRows==0이면 '이미 완료' deny). 성공 시 보상 골드 + voteUnique 추첨(VoteReward가 처리).
     */
    @Serializable
    @SerialName("voteCast")
    data class VoteCast(
        val requestId: String? = null,
        val generalId: Int,
        val voteId: Int,
        val selection: List<Int> = emptyList(),
    ) : TurnDaemonCommand() {
        override val type: String get() = "voteCast"
    }

    /**
     * 설문조사 댓글 (AddComment.php::launch). [voteId]에 [text]를 단다 — PHP는 `mb_substr(text, 0, 200)`로
     * 200자 절단 후 `vote_comment` INSERT(general/nation 이름 포함). text는 필수(lengthMin 1).
     */
    @Serializable
    @SerialName("voteComment")
    data class VoteComment(
        val requestId: String? = null,
        val generalId: Int,
        val voteId: Int,
        val text: String,
    ) : TurnDaemonCommand() {
        override val type: String get() = "voteComment"
    }

    /**
     * 설문조사 마감 (NewVote.php::closeOldVote). [voteId]의 vote_poll endDate가 비어 있으면 now()로
     * 채워 마감한다(이미 endDate가 있으면 no-op). 새 vote 개설 시 keepOldVote=false면 암묵 호출되지만,
     * 명시적 마감 인테이크로도 노출한다.
     */
    @Serializable
    @SerialName("voteClose")
    data class VoteClose(
        val requestId: String? = null,
        val generalId: Int,
        val voteId: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "voteClose"
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

    @Serializable
    @SerialName("placeBet")
    data class PlaceBet(
        val requestId: String? = null,
        val bettingId: Int,
        val generalId: Int,
        val bettingType: List<Int>,
        val amount: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "placeBet"
    }

    @Serializable
    @SerialName("acceptDiplomaticMessage")
    data class AcceptDiplomaticMessage(
        val requestId: String? = null,
        val messageId: Int,
        val generalId: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "acceptDiplomaticMessage"
    }

    @Serializable
    @SerialName("declineDiplomaticMessage")
    data class DeclineDiplomaticMessage(
        val requestId: String? = null,
        val messageId: Int,
        val generalId: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "declineDiplomaticMessage"
    }

    // ── F4 Wave C2 (slice A) — single-actor intake commands (immediate daemon-command path) ──────
    // Faithful ports of the PHP BaseAPI launch() actions (NOT turn-reserved che_* commands): the
    // 내무부 finance setters (sammo/API/Nation/Set*.php), tournament enroll (j_set_my_setting.php tnmt),
    // and the inheritance resets (sammo/API/InheritAction/Reset*.php). They flow exactly like
    // [PlaceBet]/[AuctionBid]: game-api intake → command stream → TurnDaemonCommandDispatcher →
    // handler → InMemoryTurnWorld mutate → ChangeRecorder delta → JdbcFlushExecutor flush.

    /**
     * 내무부 공지 설정 ([opensamguk] SetNotice.php). Writes the `nationNotice` KV into `nation_env`
     * (date/msg/author/authorID). `generalId` is the acting officer (permission ≥ 5 OR secret == 4).
     */
    @Serializable
    @SerialName("setNotice")
    data class SetNotice(
        val requestId: String? = null,
        val generalId: Int,
        val msg: String,
    ) : TurnDaemonCommand() {
        override val type: String get() = "setNotice"
    }

    /** 임관 권유문 설정 (SetScoutMsg.php). Writes `scout_msg` KV into `nation_env`. */
    @Serializable
    @SerialName("setScoutMsg")
    data class SetScoutMsg(
        val requestId: String? = null,
        val generalId: Int,
        val msg: String,
    ) : TurnDaemonCommand() {
        override val type: String get() = "setScoutMsg"
    }

    /** 세율 설정 (SetRate.php). Writes `nation.rate` (rides meta). amount 5..30. */
    @Serializable
    @SerialName("setRate")
    data class SetRate(
        val requestId: String? = null,
        val generalId: Int,
        val amount: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "setRate"
    }

    /** 지급률(인구세) 설정 (SetBill.php). Writes `nation.bill` (rides meta). amount 20..200. */
    @Serializable
    @SerialName("setBill")
    data class SetBill(
        val requestId: String? = null,
        val generalId: Int,
        val amount: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "setBill"
    }

    /** 기밀 제한 설정 (SetSecretLimit.php). Writes `nation.secretlimit` (rides meta). amount 1..99. */
    @Serializable
    @SerialName("setSecretLimit")
    data class SetSecretLimit(
        val requestId: String? = null,
        val generalId: Int,
        val amount: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "setSecretLimit"
    }

    /**
     * 전쟁 가능 설정 (SetBlockWar.php). Writes `nation.war` (rides meta, 0/1) AND decrements the
     * `nation_env` `available_war_setting_cnt` counter (deny when it is already ≤ 0).
     */
    @Serializable
    @SerialName("setBlockWar")
    data class SetBlockWar(
        val requestId: String? = null,
        val generalId: Int,
        val value: Boolean,
    ) : TurnDaemonCommand() {
        override val type: String get() = "setBlockWar"
    }

    /**
     * 임관 가능 설정 (SetBlockScout.php). Writes `nation.scout` (rides meta, 0/1); denied when the
     * game-env `block_change_scout` flag is set.
     */
    @Serializable
    @SerialName("setBlockScout")
    data class SetBlockScout(
        val requestId: String? = null,
        val generalId: Int,
        val value: Boolean,
    ) : TurnDaemonCommand() {
        override val type: String get() = "setBlockScout"
    }

    /**
     * 토너먼트 참가 (enroll) — `j_set_my_setting.php` 의 `tnmt` 토글. Writes the acting general's
     * `tnmt` (0/1) into the general row. `value` clamps to 0..1 (PHP: `< 0 || > 1 → 1`).
     */
    @Serializable
    @SerialName("tournamentEnroll")
    data class TournamentEnroll(
        val requestId: String? = null,
        val generalId: Int,
        val value: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "tournamentEnroll"
    }

    /**
     * 유산: 턴 시간 초기화 (ResetTurnTime.php). Spends `inheritResetAttrPointBase[nextLevel]` from the
     * acting owner's inheritance `previous` balance, draws ONE `RandUtil(hiddenSeed,'ResetTurnTime',…)`
     * float for the new turn-time offset, and bumps `inherit_point_spent_dynamic`.
     */
    @Serializable
    @SerialName("inheritResetTurnTime")
    data class InheritResetTurnTime(
        val requestId: String? = null,
        val generalId: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "inheritResetTurnTime"
    }

    /**
     * 유산: 전투 특기 초기화 (ResetSpecialWar.php). Spends `inheritResetAttrPointBase[nextLevel]`,
     * archives the current `special2` into `aux.prev_types_special2`, sets `special2 = None`.
     */
    @Serializable
    @SerialName("inheritResetSpecialWar")
    data class InheritResetSpecialWar(
        val requestId: String? = null,
        val generalId: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "inheritResetSpecialWar"
    }

    /**
     * 유산: 다음 전투 특기 예약 (SetNextSpecialWar.php → "nextSpecial"). Spends
     * `inheritSpecificSpecialPoint` (4000) and reserves `aux.inheritSpecificSpecialWar = type`.
     */
    @Serializable
    @SerialName("inheritSetNextSpecialWar")
    data class InheritSetNextSpecialWar(
        val requestId: String? = null,
        val generalId: Int,
        // wire field `specialWar` (NOT `type` — that is the union discriminator). The PHP arg is
        // `type`; the frontend maps its select value onto `specialWar` in extraArgs.
        val specialWar: String,
    ) : TurnDaemonCommand() {
        override val type: String get() = "inheritSetNextSpecialWar"
    }

    /**
     * 유산: 히든 버프 구매 (BuyHiddenBuff.php). 누적 차분 `inheritBuffPoints[level]-inheritBuffPoints[prevLevel]`
     * 만큼 inheritance `previous` 잔액을 차감하고 `aux.inheritBuff[buffKey]=level`을 적재한다. 뽑지 않음.
     * `prevLevel`은 **서버에서** `aux.inheritBuff[buffKey]`로 산출(클라 값 무시) — wire는 `buffKey`+`level`만.
     * wire field `buffKey`(PHP arg `type`; `type`은 union 판별자라 이름 충돌 회피).
     */
    @Serializable
    @SerialName("buyHiddenBuff")
    data class BuyHiddenBuff(
        val requestId: String? = null,
        val generalId: Int,
        val buffKey: String,
        val level: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "buyHiddenBuff"
    }

    /**
     * 유산: 랜덤 유니크 확정 드롭 플래그 구매 (BuyRandomUnique.php). `inheritItemRandomPoint`(3000)를
     * 차감하고 `aux.inheritRandomUnique` 마커를 적재한다. 무인자. 뽑지 않음.
     */
    @Serializable
    @SerialName("buyRandomUnique")
    data class BuyRandomUnique(
        val requestId: String? = null,
        val generalId: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "buyRandomUnique"
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

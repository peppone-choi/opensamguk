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

    // ── B1 장수생성(재야→일반/Join) — 즉시 데몬커맨드. RNG-bearing(draw-for-draw).
    /**
     * 장수 생성 (`sammo/API/General/Join.php`).
     *
     * **W0-7 widen — 유산 4필드 + 전콘(picture/imgsvr).**
     *  - 유산 4필드는 PHP POST 인자명 verbatim (`Join.php:142-145`, 모두 `?? null`):
     *    [inheritSpecial](`Join.php:74` — `in availableSpecialWar`, 천재 생성),
     *    [inheritTurntimeZone](`Join.php:75-76` — int 0..59, 60-zone 턴 시간 지정),
     *    [inheritCity](`Join.php:77` — `in array_keys(CityConst::all())`, 생성 도시 지정),
     *    [inheritBonusStat](`Join.php:78,200-211` — integerArray, count==3 + 합계 검증은 엔진).
     *    null = 해당 유산 옵션 미사용(PHP 부재와 동일) — 포인트 차감 분기(`Join.php:233-244`)는
     *    엔진 핸들러(W1 에이전트 K) 소관.
     *  - 전콘: PHP POST `pic`(boolean, `Join.php:135`)은 REST 계층(JoinController) 인자다. 게이트
     *    `show_img_level>=1 && grade>=1 && picture!="" && pic`(`Join.php:379-385`)의 member 프로필은
     *    gateway DB에 있어 엔진이 읽을 수 없으므로, 컨트롤러가 게이트를 적용한 **resolved**
     *    [picture]/[imgsvr] 쌍(PHP `$face`/`$imgsvr`)을 wire에 싣는다. null = default.jpg/0.
     */
    @Serializable
    @SerialName("makeGeneral")
    data class MakeGeneral(
        val requestId: String? = null,
        val userId: Int,
        val name: String,
        val leadership: Int,
        val strength: Int,
        val intel: Int,
        val politics: Int = 50,
        val charm: Int = 50,
        val character: String = "Random",
        val picture: String? = null,
        val ownerName: String? = null,
        // W0-7 — 전콘 resolved 이미지 서버 (PHP `$imgsvr`, Join.php:381/384). null이면 0(기본 서버).
        val imgsvr: Int? = null,
        // W0-7 — 유산 포인트 4필드 (Join.php:142-145, PHP 인자명 verbatim. null = 미사용).
        val inheritSpecial: String? = null,
        val inheritTurntimeZone: Int? = null,
        val inheritCity: Int? = null,
        val inheritBonusStat: List<Int>? = null,
    ) : TurnDaemonCommand() {
        override val type: String get() = "makeGeneral"
    }

    @Serializable
    @SerialName("claimNpc")
    data class ClaimNpc(
        val requestId: String? = null,
        val generalId: Int,
        val userId: Long,
        val userNick: String,
        val userPenaltyJson: String = "{}",
    ) : TurnDaemonCommand() {
        override val type: String get() = "claimNpc"
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
    @SerialName("checkOwner")
    data class CheckOwner(
        val requestId: String? = null,
        val generalId: Int,
        val destGeneralId: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "checkOwner"
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

    @Serializable
    @SerialName("npcPolicyUpdate")
    data class NpcPolicyUpdate(
        val requestId: String? = null,
        val generalId: Int,
        val policyType: String,
        val data: kotlinx.serialization.json.JsonElement,
    ) : TurnDaemonCommand() {
        override val type: String get() = "npcPolicyUpdate"
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

    @Serializable
    @SerialName("tournamentStart")
    data class TournamentStart(
        val requestId: String? = null,
        val generalId: Int,
        val tournamentType: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "tournamentStart"
    }

    @Serializable
    @SerialName("tournamentReset")
    data class TournamentReset(
        val requestId: String? = null,
        val generalId: Int,
    ) : TurnDaemonCommand() {
        override val type: String get() = "tournamentReset"
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

    @Serializable
    @SerialName("resetStat")
    data class ResetStat(
        val requestId: String? = null,
        val generalId: Int,
        val leadership: Int,
        val strength: Int,
        val intel: Int,
        val inheritBonusStat: List<Int>? = null,
    ) : TurnDaemonCommand() {
        override val type: String get() = "resetStat"
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

    // ── W6a 메시지 인테이크 (SendMessage / DeleteMessage) — j_send_message.php / j_delete_message.php ──
    /**
     * 메시지 발송 (j_send_message.php). [mailbox]가 라우팅을 결정한다:
     * 9999=공개, >=9000=국가(9000+nationId), <9000=개인(상대 generalId). PHP `getPost('mailbox','int')`.
     */
    @Serializable
    @SerialName("sendMessage")
    data class SendMessage(
        val requestId: String? = null,
        val generalId: Int,
        // 9999=공개, >=9000=국가(9000+nationId), <9000=개인(상대 generalId). PHP getPost('mailbox','int').
        val mailbox: Int,
        val text: String,
    ) : TurnDaemonCommand() { override val type: String get() = "sendMessage" }

    /** 메시지 삭제 (j_delete_message.php). [msgID]는 삭제 대상 message 행 id. */
    @Serializable
    @SerialName("deleteMessage")
    data class DeleteMessage(
        val requestId: String? = null,
        val generalId: Int,
        val msgID: Int,
    ) : TurnDaemonCommand() { override val type: String get() = "deleteMessage" }

    // ── W6c 경매 개설 (BuyRice / SellRice / Unique) — OpenBuyRiceAuction.php 등 ──
    /** 쌀 매수 경매 개설 (OpenBuyRiceAuction.php → AuctionBasicResource::openResourceAuction). */
    @Serializable
    @SerialName("auctionOpenBuyRice")
    data class AuctionOpenBuyRice(
        val requestId: String? = null,
        val generalId: Int,
        val amount: Int,
        val closeTurnCnt: Int,
        val startBidAmount: Int,
        val finishBidAmount: Int,
    ) : TurnDaemonCommand() { override val type: String get() = "auctionOpenBuyRice" }

    /** 쌀 매도 경매 개설 (OpenSellRiceAuction.php → AuctionBasicResource::openResourceAuction). */
    @Serializable
    @SerialName("auctionOpenSellRice")
    data class AuctionOpenSellRice(
        val requestId: String? = null,
        val generalId: Int,
        val amount: Int,
        val closeTurnCnt: Int,
        val startBidAmount: Int,
        val finishBidAmount: Int,
    ) : TurnDaemonCommand() { override val type: String get() = "auctionOpenSellRice" }

    /** 유니크 아이템 경매 개설 (AuctionUniqueItem). [itemId]는 유니크 아이템 키. */
    @Serializable
    @SerialName("auctionOpenUnique")
    data class AuctionOpenUnique(
        val requestId: String? = null,
        val generalId: Int,
        val itemId: String,
        val amount: Int,
    ) : TurnDaemonCommand() { override val type: String get() = "auctionOpenUnique" }

    // ── W5d 외교 서신 (Send / Rollback / Destroy) — j_diplomacy_*_letter.php ──
    /**
     * 외교 서신 발송 (j_diplomacy_send_letter.php). [prevLetterNo]는 직전 문서 번호(체인) — PHP `?? null`,
     * <1 → null. nullable 유지로 엔진이 '이전 문서 없음' 게이트를 태운다.
     */
    @Serializable
    @SerialName("diploSendLetter")
    data class DiploSendLetter(
        val requestId: String? = null,
        val generalId: Int,
        val destNationId: Int,
        // PHP `?? null`, <1 → null. 직전 문서 번호(체인). nullable 유지.
        val prevLetterNo: Int? = null,
        val textBrief: String,
        val textDetail: String,
    ) : TurnDaemonCommand() { override val type: String get() = "diploSendLetter" }

    /** 외교 서신 회수 (j_diplomacy_rollback_letter.php). [letterNo]는 회수 대상 ng_diplomacy 행 번호. */
    @Serializable
    @SerialName("diploRollbackLetter")
    data class DiploRollbackLetter(
        val requestId: String? = null,
        val generalId: Int,
        val letterNo: Int,
    ) : TurnDaemonCommand() { override val type: String get() = "diploRollbackLetter" }

    /** 외교 서신 파기(요청) (j_diplomacy_destroy_letter.php). 양측 동의 시 cancelled. */
    @Serializable
    @SerialName("diploDestroyLetter")
    data class DiploDestroyLetter(
        val requestId: String? = null,
        val generalId: Int,
        val letterNo: Int,
    ) : TurnDaemonCommand() { override val type: String get() = "diploDestroyLetter" }

    /**
     * 외교 서신 승인/거부 (W0-7 — `j_diplomacy_respond_letter.php:16-18`). 수신국 수뇌
     * (checkSecretPermission >= 4)가 `state='proposed'` 서신에 응답한다 — POST 인자명 verbatim:
     * [letterNo](`Util::getPost('letterNo','int')`), [isAgree](`'bool', false` — 부재 시 거부),
     * [reason](`'string', ''` — 거부 사유, trim은 엔진).
     * 승인: activated + dest_signer 서명 + prev_no 체인 replaced (`:78-93`);
     * 거부: cancelled + aux.reason (`:96-109`); 메시지 2채널(diplomacy+national) 발송 (`:112-133`).
     * 엔진 핸들러(handleRespond)는 W1 에이전트 G 소관 — 그전까지 dispatcher가 명시적 deny.
     */
    @Serializable
    @SerialName("diploRespondLetter")
    data class DiploRespondLetter(
        val requestId: String? = null,
        val generalId: Int,
        val letterNo: Int,
        // PHP Util::getPost('isAgree','bool',false) — 부재 시 false(거부).
        val isAgree: Boolean = false,
        // PHP Util::getPost('reason','string','') — 거부 사유. trim/접미(' 이유 : ')는 엔진.
        val reason: String = "",
    ) : TurnDaemonCommand() { override val type: String get() = "diploRespondLetter" }

    // ── W6f 장수 선택 풀 pick/update — j_pick_general.php / j_update_picked_general.php (RNG-BEARING) ──
    /**
     * 장수 선택 풀에서 픽 (j_pick_general.php). 가중 추첨(`allStat^1.5`)을 포함하는 RNG-BEARING 액션 —
     * 골든 게이트는 /parity-wave. 스탯/성격은 풀 항목이 편집 가능할 때만 유효(부재 시 null → 풀 기본값).
     */
    @Serializable
    @SerialName("selectPoolPick")
    data class SelectPoolPick(
        val requestId: String? = null,
        val generalId: Int,
        val ownerUserId: Int? = null,
        val uniqueName: String,
        // 선택 스탯(풀 항목이 stat-editable일 때만 유효). 부재 시 null → 엔진이 풀 기본값 사용.
        val leadership: Int? = null,
        val strength: Int? = null,
        val intel: Int? = null,
        // 'Random' 또는 유효 성격명. 부재 시 null.
        val personalityName: String? = null,
        val useOwnPicture: Boolean = false,
    ) : TurnDaemonCommand() { override val type: String get() = "selectPoolPick" }

    /** 선택 풀 장수 갱신 (j_update_picked_general.php). pick과 동일 인자 shape. */
    @Serializable
    @SerialName("selectPoolUpdate")
    data class SelectPoolUpdate(
        val requestId: String? = null,
        val generalId: Int,
        val ownerUserId: Int? = null,
        val uniqueName: String,
        val leadership: Int? = null,
        val strength: Int? = null,
        val intel: Int? = null,
        val personalityName: String? = null,
        val useOwnPicture: Boolean = false,
    ) : TurnDaemonCommand() { override val type: String get() = "selectPoolUpdate" }

    @Serializable
    @SerialName("selectPoolRefresh")
    data class SelectPoolRefresh(
        val requestId: String? = null,
        val ownerUserId: Int,
        val requestedAt: String,
    ) : TurnDaemonCommand() { override val type: String get() = "selectPoolRefresh" }

    @Serializable
    @SerialName("adminGeneralModeration")
    data class AdminGeneralModeration(
        val requestId: String? = null,
        val actorGeneralId: Int,
        val generalIds: List<Int>,
        val action: String,
    ) : TurnDaemonCommand() { override val type: String get() = "adminGeneralModeration" }

    @Serializable
    @SerialName("adminWorldSettings")
    data class AdminWorldSettings(
        val requestId: String? = null,
        val status: String? = null,
        val settings: List<AdminWorldSetting> = emptyList(),
    ) : TurnDaemonCommand() { override val type: String get() = "adminWorldSettings" }
}

@Serializable
data class AdminWorldSetting(
    val key: String,
    val intValue: Int? = null,
    val stringValue: String? = null,
)

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

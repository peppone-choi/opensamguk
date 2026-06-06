package opensamguk.gameapi.reserve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.wire.TurnDaemonCommand

/**
 * F-INTAKE seam — maps a `POST /api/command/{code}` `{code, argJson, generalId}` onto the EXISTING
 * typed [TurnDaemonCommand] variants for the **immediate daemon-command** intake commands (the
 * betting/auction + F4 Wave C2 single-actor commands).
 *
 * **Why this exists.** Before this, [CommandReserveService] published a `Run(POKE)` for EVERY
 * AVAILABLE command and reserved the action-code into the `general_turn` ring. That is correct for
 * the **turn-reserved** `che_*` commands (resolved on the general's turn from the ring). But the
 * betting/auction + C2 commands are NOT turn-reserved — their engine handlers
 * ([opensamguk.engine.betting.PlaceBetHandler], …, the C2 intake handlers) are driven by the
 * [opensamguk.engine.run.TurnDaemonCommandDispatcher] off a TYPED command on the command stream, NOT
 * by the `general_turn` ring. So they need their typed [TurnDaemonCommand] published verbatim — a
 * `Run(POKE)` would reach the dispatcher and return `null` (no handler), silently dropping the action.
 *
 * This mapper is the ONLY place that knows the `{code → typed command}` translation. It returns the
 * typed command for an immediate-intake code, or `null` for any other code (the turn-reserved `che_*`
 * path, which [CommandReserveService] keeps handling via the ring + `Run(POKE)`).
 *
 * The arg shape is the JSON body the frontend `CommandModal`/`api.command(code, args, …)` posts —
 * the page-fixed `extraArgs` (auctionId/bettingId/…) merged with the picked arg (amount/value/…). The
 * `generalId` is the RESOLVED owner (from the controller, NOT from the body) so a caller can never
 * act as another general. NO new wire variant is introduced — every command here already exists in
 * `:common` ([TurnDaemonCommand]) and round-trips through the existing wire serializer.
 */
object CommandWireMapper {
    /** Lenient codec for the request body — tolerant of string/number coercion the UI may send. */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The immediate-intake command codes this mapper translates (everything else = turn-reserved). */
    val intakeCodes: Set<String> = setOf(
        "placeBet",
        "auctionBid",
        "setNotice",
        "setScoutMsg",
        "setRate",
        "setBill",
        "setSecretLimit",
        "setBlockWar",
        "setBlockScout",
        "tournamentEnroll",
        "inheritResetTurnTime",
        "inheritResetSpecialWar",
        "inheritSetNextSpecialWar",
        // 유산 포인트 구매 — FE(inherit/nation page)가 PHP API 이름 그대로 BuyHiddenBuff/BuyRandomUnique 전송.
        "BuyHiddenBuff",
        "BuyRandomUnique",
        // F4 Wave C2 slice B — troop intake.
        "troopNew",
        "troopJoin",
        "troopExit",
        "troopKick",
        "troopSetName",
        // F4 Wave C2 슬라이스 C — 게시판(회의실/기밀실) 인테이크.
        "boardArticle",
        "boardComment",
        // F4 Wave 투표 — 설문조사(개설/투표/댓글/마감) 인테이크.
        "newVote",
        "voteCast",
        "voteComment",
        "voteClose",
        // W6a 메시지 — 발송/삭제 인테이크.
        "sendMessage",
        "deleteMessage",
        // W6c 경매 개설 — 쌀 매수/매도/유니크.
        "auctionOpenBuyRice",
        "auctionOpenSellRice",
        "auctionOpenUnique",
        // W5d 외교 서신 — 발송/회수/파기.
        "diploSendLetter",
        "diploRollbackLetter",
        "diploDestroyLetter",
        // W6f 장수 선택 풀 — 픽/갱신 (RNG-bearing — 골든은 /parity-wave).
        "selectPoolPick",
        "selectPoolUpdate",
        // NB: join(REST-only, no daemon command), /bulk·/push·/repeat(W6e, CommandQueueService),
        //     buildNationCandidate(NationController가 wire 명령을 직접 발행)는 의도적으로 intakeCodes
        //     밖이다 — 추가 금지.
    )

    /**
     * F4 C3 사령(chief) 커맨드 12종 — **턴-예약(turn-reserved) `che_*`이므로 의도적으로 [intakeCodes]에
     * 넣지 않는다.** 급습/몰수/물자원조/백성동원/부대탈퇴지시/수몰/의병모집/이호경식/초토화/피장파장/
     * 필사즉생/허보는 즉시-인테이크 핸들러가 없다. 이들은 장수 턴에 `general_turn` 링에서
     * [opensamguk.gameapi.reserve.CommandReserveService.reserve]가 RAW `argJson`을 그대로 적재하고,
     * 데몬이 턴 해소 시 `CommandRegistry.resolve(code).parseArgs(rawArgMap)`로 정규화한다(각 액션의
     * `parseArgs`가 destGeneralID/destCityID/destNationID/amount/amountList/commandType 인자 시그니처를
     * PHP argTest와 byte-동일하게 받는다). 따라서 [toCommand]는 이 12 코드에 대해 `null`을 반환해야
     * 하며(아래 `else`), 그래야 reserve가 링 기록 경로로 떨어진다. intakeCodes에 추가하면 존재하지 않는
     * 핸들러로 라우팅되어 ring write를 건너뛰고 액션이 조용히 유실된다(패리티 깨짐) — 추가 금지.
     */
    val turnReservedC3Codes: Set<String> = setOf(
        "che_급습", "che_몰수", "che_물자원조", "che_백성동원", "che_부대탈퇴지시", "che_수몰",
        "che_의병모집", "che_이호경식", "che_초토화", "che_피장파장", "che_필사즉생", "che_허보",
        // event_*연구 9종 — 같은 chief-reserved 링 family(BeChief 국가 연구, run() deterministic, 무인자).
        "event_극병연구", "event_무희연구", "event_상병연구", "event_화륜차연구", "event_원융노병연구",
        "event_대검병연구", "event_화시병연구", "event_음귀병연구", "event_산저병연구",
    )

    /** True when [code] is an immediate-intake command (typed-publish, NOT general_turn reserve). */
    fun isIntakeCommand(code: String): Boolean = code in intakeCodes

    /**
     * Build the typed [TurnDaemonCommand] for an immediate-intake [code], or `null` when [code] is not
     * an immediate-intake command (the caller falls back to the turn-reserved path).
     *
     * @param code the action code (the `{code}` path var).
     * @param generalId the RESOLVED acting general (from the controller's ownership check — never the body).
     * @param requestId the generated request id (echoed back to the UI as the 202 requestId).
     * @param argJson the raw JSON body (the merged extraArgs + picked arg), or null/blank for no-arg commands.
     */
    fun toCommand(code: String, generalId: Int, requestId: String, argJson: String?): TurnDaemonCommand? {
        if (code !in intakeCodes) return null
        val args = parseArgs(argJson)
        return when (code) {
            "placeBet" -> TurnDaemonCommand.PlaceBet(
                requestId = requestId,
                bettingId = args.int("bettingId") ?: 0,
                generalId = generalId,
                bettingType = args.intList("bettingType"),
                amount = args.int("amount") ?: 0,
            )
            "auctionBid" -> TurnDaemonCommand.AuctionBid(
                requestId = requestId,
                auctionId = args.int("auctionId") ?: 0,
                generalId = generalId,
                amount = args.int("amount") ?: 0,
                tryExtendCloseDate = args.bool("tryExtendCloseDate"),
            )
            "setNotice" -> TurnDaemonCommand.SetNotice(
                requestId = requestId, generalId = generalId, msg = args.str("msg") ?: "",
            )
            "setScoutMsg" -> TurnDaemonCommand.SetScoutMsg(
                requestId = requestId, generalId = generalId, msg = args.str("msg") ?: "",
            )
            "setRate" -> TurnDaemonCommand.SetRate(
                requestId = requestId, generalId = generalId, amount = args.int("amount") ?: 0,
            )
            "setBill" -> TurnDaemonCommand.SetBill(
                requestId = requestId, generalId = generalId, amount = args.int("amount") ?: 0,
            )
            "setSecretLimit" -> TurnDaemonCommand.SetSecretLimit(
                requestId = requestId, generalId = generalId, amount = args.int("amount") ?: 0,
            )
            "setBlockWar" -> TurnDaemonCommand.SetBlockWar(
                requestId = requestId, generalId = generalId, value = args.bool("value") ?: false,
            )
            "setBlockScout" -> TurnDaemonCommand.SetBlockScout(
                requestId = requestId, generalId = generalId, value = args.bool("value") ?: false,
            )
            "tournamentEnroll" -> TurnDaemonCommand.TournamentEnroll(
                requestId = requestId, generalId = generalId, value = args.int("value") ?: 1,
            )
            "inheritResetTurnTime" -> TurnDaemonCommand.InheritResetTurnTime(
                requestId = requestId, generalId = generalId,
            )
            "inheritResetSpecialWar" -> TurnDaemonCommand.InheritResetSpecialWar(
                requestId = requestId, generalId = generalId,
            )
            "inheritSetNextSpecialWar" -> TurnDaemonCommand.InheritSetNextSpecialWar(
                requestId = requestId, generalId = generalId, specialWar = args.str("specialWar") ?: "",
            )
            // 유산 포인트 구매. FE는 `buffKey`(PHP arg `type`)+`level`을 전송. prevLevel은 클라가 보내도
            // 무시 — 엔진 핸들러가 general aux.inheritBuff에서 서버측으로 산출(PHP launch와 동일).
            "BuyHiddenBuff" -> TurnDaemonCommand.BuyHiddenBuff(
                requestId = requestId, generalId = generalId,
                buffKey = args.str("buffKey") ?: args.str("type") ?: "",
                level = args.int("level") ?: 0,
            )
            "BuyRandomUnique" -> TurnDaemonCommand.BuyRandomUnique(
                requestId = requestId, generalId = generalId,
            )
            // ── F4 Wave C2 slice B — troop intake. `generalId` is always the RESOLVED acting general;
            //    troopKick carries the TARGET separately (`targetGeneralId`, legacy `generalID`). ──
            "troopNew" -> TurnDaemonCommand.TroopNew(
                requestId = requestId, generalId = generalId, troopName = args.str("troopName") ?: "",
            )
            "troopJoin" -> TurnDaemonCommand.TroopJoin(
                requestId = requestId, generalId = generalId, troopId = args.int("troopId") ?: 0,
            )
            "troopExit" -> TurnDaemonCommand.TroopExit(
                requestId = requestId, generalId = generalId,
            )
            "troopKick" -> TurnDaemonCommand.TroopKick(
                requestId = requestId, generalId = generalId,
                troopId = args.int("troopId") ?: 0,
                targetGeneralId = args.int("targetGeneralId") ?: args.int("generalID") ?: 0,
            )
            "troopSetName" -> TurnDaemonCommand.TroopSetName(
                requestId = requestId, generalId = generalId,
                troopId = args.int("troopId") ?: 0,
                troopName = args.str("troopName") ?: "",
            )
            // ── F4 Wave C2 슬라이스 C — 게시판(회의실/기밀실). title/text는 nullable 유지(`?: ""` 없음)로
            //    PHP `Util::getPost`의 null(부재)-vs-blank를 보존; isSecret이 방을 고른다. ──
            "boardArticle" -> TurnDaemonCommand.BoardArticle(
                requestId = requestId, generalId = generalId,
                isSecret = args.bool("isSecret") ?: false,
                title = args.str("title"),
                text = args.str("text"),
            )
            "boardComment" -> TurnDaemonCommand.BoardComment(
                requestId = requestId, generalId = generalId,
                // `?: 0` 없음 — 부재 시 articleNo를 null로 유지(PHP getPost null)해, 엔진이
                // `$articleNo === null` 1차 게이트 '올바르지 않은 입력입니다.'를 발동하게 한다.
                articleNo = args.int("articleNo"),
                text = args.str("text"),
            )
            // ── F4 Wave 투표 — 설문조사(개설/투표/댓글/마감). multipleOptions/endDate/keepOldVote는
            //    nullable 유지(`?: …` 없음)로 PHP `?? 1`/`?? null`/`?? false` 기본값·부재를 엔진이
            //    적용하게 한다. title/text는 빈 문자열 fallback(`?: ""`)으로 PHP 필수 검증을 태운다. ──
            "newVote" -> TurnDaemonCommand.NewVote(
                requestId = requestId, generalId = generalId,
                title = args.str("title") ?: "",
                options = args.strList("options"),
                multipleOptions = args.int("multipleOptions"),
                endDate = args.str("endDate"),
                keepOldVote = args.bool("keepOldVote"),
            )
            "voteCast" -> TurnDaemonCommand.VoteCast(
                requestId = requestId, generalId = generalId,
                voteId = args.int("voteId") ?: args.int("voteID") ?: 0,
                selection = args.intList("selection"),
            )
            "voteComment" -> TurnDaemonCommand.VoteComment(
                requestId = requestId, generalId = generalId,
                voteId = args.int("voteId") ?: args.int("voteID") ?: 0,
                text = args.str("text") ?: "",
            )
            "voteClose" -> TurnDaemonCommand.VoteClose(
                requestId = requestId, generalId = generalId,
                voteId = args.int("voteId") ?: args.int("voteID") ?: 0,
            )
            // ── W6a 메시지 — 발송/삭제. mailbox 라우팅(9999 공개/>=9000 국가/<9000 개인)은 엔진이 적용. ──
            "sendMessage" -> TurnDaemonCommand.SendMessage(
                requestId = requestId, generalId = generalId,
                // PHP SendMessage::validateArgs: mailbox 부재 시 Message::MAILBOX_PUBLIC(9999). (required라 실제 부재
                // 불가지만, faithful 기본값.) 9999=공개/>=9000=국가/<9000=개인 라우팅은 엔진 핸들러가 적용.
                mailbox = args.int("mailbox") ?: 9999,
                text = args.str("text") ?: "",
            )
            "deleteMessage" -> TurnDaemonCommand.DeleteMessage(
                requestId = requestId, generalId = generalId,
                msgID = args.int("msgID") ?: args.int("msgId") ?: 0,
            )
            // ── W6c 경매 개설 — 쌀 매수/매도/유니크. 검증 순서(3개월→턴수→거래량→입찰가)는 엔진이 적용. ──
            "auctionOpenBuyRice" -> TurnDaemonCommand.AuctionOpenBuyRice(
                requestId = requestId, generalId = generalId,
                amount = args.int("amount") ?: 0,
                closeTurnCnt = args.int("closeTurnCnt") ?: 0,
                startBidAmount = args.int("startBidAmount") ?: 0,
                finishBidAmount = args.int("finishBidAmount") ?: 0,
            )
            "auctionOpenSellRice" -> TurnDaemonCommand.AuctionOpenSellRice(
                requestId = requestId, generalId = generalId,
                amount = args.int("amount") ?: 0,
                closeTurnCnt = args.int("closeTurnCnt") ?: 0,
                startBidAmount = args.int("startBidAmount") ?: 0,
                finishBidAmount = args.int("finishBidAmount") ?: 0,
            )
            "auctionOpenUnique" -> TurnDaemonCommand.AuctionOpenUnique(
                requestId = requestId, generalId = generalId,
                itemId = args.str("itemId") ?: args.str("itemKey") ?: "",
                amount = args.int("amount") ?: 0,
            )
            // ── W5d 외교 서신 — 발송/회수/파기. prevLetterNo는 <1 → null(PHP)로 '이전 문서 없음' 게이트 유지. ──
            "diploSendLetter" -> TurnDaemonCommand.DiploSendLetter(
                requestId = requestId, generalId = generalId,
                destNationId = args.int("destNation") ?: args.int("destNationId") ?: 0,
                // <1 → null (PHP). nullable 유지로 엔진이 '이전 문서 없음' 게이트를 태운다.
                prevLetterNo = args.int("prevNo")?.takeIf { it >= 1 } ?: args.int("prevLetterNo")?.takeIf { it >= 1 },
                textBrief = args.str("brief") ?: args.str("textBrief") ?: "",
                textDetail = args.str("detail") ?: args.str("textDetail") ?: "",
            )
            "diploRollbackLetter" -> TurnDaemonCommand.DiploRollbackLetter(
                requestId = requestId, generalId = generalId,
                letterNo = args.int("letterNo") ?: 0,
            )
            "diploDestroyLetter" -> TurnDaemonCommand.DiploDestroyLetter(
                requestId = requestId, generalId = generalId,
                letterNo = args.int("letterNo") ?: 0,
            )
            // ── W6f 장수 선택 풀 — 픽/갱신. 스탯/성격은 nullable 유지(풀 기본값/편집가능 분기는 엔진). ──
            "selectPoolPick" -> TurnDaemonCommand.SelectPoolPick(
                requestId = requestId, generalId = generalId,
                uniqueName = args.str("uniqueName") ?: "",
                leadership = args.int("leadership"), strength = args.int("strength"), intel = args.int("intel"),
                personalityName = args.str("personalityName"),
                useOwnPicture = args.bool("useOwnPicture") ?: false,
            )
            "selectPoolUpdate" -> TurnDaemonCommand.SelectPoolUpdate(
                requestId = requestId, generalId = generalId,
                uniqueName = args.str("uniqueName") ?: "",
                leadership = args.int("leadership"), strength = args.int("strength"), intel = args.int("intel"),
                personalityName = args.str("personalityName"),
                useOwnPicture = args.bool("useOwnPicture") ?: false,
            )
            else -> null
        }
    }

    private fun parseArgs(argJson: String?): Map<String, JsonElement> {
        if (argJson.isNullOrBlank()) return emptyMap()
        return try {
            (json.parseToJsonElement(argJson) as? JsonObject)?.toMap() ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun Map<String, JsonElement>.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }

    private fun Map<String, JsonElement>.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun Map<String, JsonElement>.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }

    private fun Map<String, JsonElement>.intList(key: String): List<Int> {
        val el = this[key] ?: return emptyList()
        return try {
            el.jsonArray.mapNotNull { (it as? JsonPrimitive)?.let { p -> p.intOrNull ?: p.content.toIntOrNull() } }
        } catch (_: Exception) {
            // single scalar → wrap (the UI may send a lone betting type)
            (el as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }?.let { listOf(it) } ?: emptyList()
        }
    }

    /**
     * 문자열 배열 추출 (newVote options). 배열이 아니면 단일 스칼라를 래핑한다. 빈 항목 검증/순서는
     * PHP가 grand truth — 여기서는 입력 순서를 그대로 보존(stringArray)만 한다.
     */
    private fun Map<String, JsonElement>.strList(key: String): List<String> {
        val el = this[key] ?: return emptyList()
        return try {
            el.jsonArray.mapNotNull { (it as? JsonPrimitive)?.content }
        } catch (_: Exception) {
            (el as? JsonPrimitive)?.content?.let { listOf(it) } ?: emptyList()
        }
    }
}

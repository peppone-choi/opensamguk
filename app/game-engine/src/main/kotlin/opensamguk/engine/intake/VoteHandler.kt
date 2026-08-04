package opensamguk.engine.intake

import opensamguk.common.wire.BoardActionResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralItems
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.actions.vote.VoteLotteryInputs
import opensamguk.logic.actions.vote.deriveItemPool
import opensamguk.logic.actions.vote.deriveSurveyProb0
import opensamguk.logic.actions.vote.tryUniqueItemLottery
import opensamguk.logic.actions.vote.voteUniqueRng
import opensamguk.logic.util.jsonEncode

/**
 * 설문조사 (vote) intake 핸들러 — `sammo/API/Vote/{NewVote,Vote,AddComment}.php` `launch()` 본문의
 * faithful 포팅 (F4 Wave 투표). per-run (world + recorder), [BoardHandler]/[TroopHandler]를 미러링.
 *
 * 순수 추첨(voteUnique) RNG 수학은 `:logic` [opensamguk.logic.actions.vote.VoteLottery] 에 있다.
 * 이 핸들러는 PHP가 `DB::db()`/KVStorage로 수행하는 부수 효과를 담당한다:
 *  - vote_poll/vote/vote_comment INSERT — [ChangeRecorder]의 투표 채널(board/betting과 동일,
 *    InMemoryTurnWorld 게임 상태가 아니므로 world create/update 경로를 절대 건드리지 않는다).
 *  - 투표 보상 골드(`develcost*5`) + 추첨 당첨 아이템 슬롯 → [ChangeRecorder.diffGeneral] (SINGLE
 *    dirty source, board/troop/inherit/enroll와 동일).
 *  - `lastVote` 카운터 + `general.newvote` 플래그 → KV / general meta.
 *
 * PHP의 설문조사 상태는 KVStorage(`vote_{voteID}` in `vote` 네임스페이스, `lastVote` in `game_env`)에
 * 저장되지만 opensamguk 스키마는 vote_poll/vote/vote_comment DB 테이블로 매핑한다. 투표(VoteCast)의
 * 설문 존재/만료/중복 게이트는 그 테이블을 읽어야 하므로 — infra `VotePollRepository`(read seam)를
 * InheritResetHandler가 `previousPointReader`로 하듯 [votePollReader] 시드(주입 가능한 reader 함수)로
 * 소비한다. 디스패처가 `VotePollRepository.findPollState(...)`를 이 reader로 어댑트해 주입하고
 * (infra `VotePollReadRow` → 엔진 [VotePollState] 매핑은 디스패처가 담당 — DTO는 모듈 사이클 회피를 위해
 * 의도적으로 분리), 기본값은 "설문 없음"(null) 이며 테스트가 stub을 먹인다.
 *
 * 추첨 RNG는 매 투표(win/no-win 불문)에서 정확히 PHP 순서로 돈다 — Vote.php:111-117 의
 * `tryUniqueItemLottery($uniqueRng, $general, '설문조사')`. seed는 per-action 시드와 분리된
 * 전용 RandUtil(`voteUnique`)이므로 action draw 스트림을 재배열하지 않는다.
 */
class VoteHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    /**
     * `vote_{voteID}` 설문 상태 read (PHP KVStorage `vote`). opensamguk에서는 vote_poll 행 + 해당 장수의
     * vote 행 존재 여부(`alreadyVoted`)로 채워진다 — 그래서 voteID에 더해 generalId도 받는다(Vote.php의
     * `insertIgnore` 후 `affectedRows==0` dedup을 read로 선판정). null = 설문 없음(Vote.php
     * `'설문조사가 없습니다.'`). 기본은 stub(항상 null) — 디스패처가 `VotePollRepository`로 어댑트해 주입하고,
     * IT/단위 테스트는 double을 먹인다.
     */
    private val votePollReader: (voteId: Int, generalId: Int) -> VotePollState? = { _, _ -> null },
    /**
     * `game_env.lastVote` 카운터 read (NewVote.php `$gameStor->getValue('lastVote') ?? 0`). voteID는
     * `lastVote+1`. 기본은 world meta(`lastVote`)에서 읽되 없으면 0.
     */
    private val lastVoteReader: () -> Int = { (world.getState().meta["lastVote"] as? Number)?.toInt() ?: 0 },
    /**
     * func.php '설문조사' 분기 추첨 입력 파생 — null(기본)이면 [deriveLotteryInputs](live world + GameConst).
     * 골든 테스트는 fixture가 박제한 PINNED [VoteLotteryInputs](genCount/itemTypeCnt/maxCnt/prob0/itemPool)을
     * 직접 주입해 추첨을 draw-for-draw 골든과 e2e로 대조한다(capture_vote.php는 vote_poll 상태를 읽지 않고
     * general+game_env+genCount만 입력으로 받으므로 이 시드만 고정하면 추첨이 100% real PHP 경로를 탄다).
     */
    private val lotteryInputsProvider: ((me: TurnGeneral) -> VoteLotteryInputs)? = null,
) {

    // ── NewVote.php ──────────────────────────────────────────────────────────────────────────────
    fun handleNewVote(c: TurnDaemonCommand.NewVote): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return result("newVote", ok = false, generalId = c.generalId, reason = "장수가 존재하지 않습니다.")

        // PHP: $isVoteAdmin = in_array('vote', acl) || userGrade >= 5. 세션 ACL/userGrade는 게이트웨이
        // 개념이라 daemon world엔 없다 — general.meta["userGrade"](게이트웨이가 인테이크 시 실어 보냄)로
        // 충실히 게이트한다. acl/userGrade 둘 다 부재면 PHP처럼 '권한이 부족합니다.' deny.
        val isVoteAdmin = hasVoteAcl(me) || userGrade(me) >= 5
        if (!isVoteAdmin) {
            return result("newVote", ok = false, generalId = c.generalId, reason = "권한이 부족합니다.")
        }

        // PHP: $title = $this->args['title'] (validator lengthMin 1 → 빈 문자열이면 인테이크에서 걸러짐).
        val title = c.title
        if (title.isEmpty()) {
            return result("newVote", ok = false, generalId = c.generalId, reason = "올바르지 않은 입력입니다.")
        }

        // PHP: $multipleOptions = args['multipleOptions'] ?? 1; if (<0) =0; (valueFit은 options 검증 뒤).
        var multipleOptions = c.multipleOptions ?: 1
        if (multipleOptions < 0) multipleOptions = 0

        val options = c.options
        // PHP: if (!$options) return '항목이 없습니다.';
        if (options.isEmpty()) {
            return result("newVote", ok = false, generalId = c.generalId, reason = "항목이 없습니다.")
        }

        // PHP: if ($endDate !== null) { 종료일 < now → '종료일이 이미 지났습니다.' }. 데몬은 now() 비교를
        // 게이트웨이/인테이크에서 이미 통과한 입력으로 받으므로(같은 정신: precheck), 여기선 endDate를
        // 그대로 vote_poll.end_at으로 싣는다. (잘못된 형식 자체는 인테이크 validator `date` 룰이 거른다.)
        val endDate = c.endDate

        // PHP: $now = TimeUtil::now(); $lastVote = lastVote ?? 0; $voteID = $lastVote + 1.
        val lastVote = lastVoteReader()
        val voteId = lastVote + 1

        // PHP: if (!keepOldVote) closeOldVote($lastVote). keepOldVote ?? false.
        val keepOldVote = c.keepOldVote ?: false
        if (!keepOldVote) {
            closeOldVote(lastVote, me.id)
        }

        // PHP: $multipleOptions = Util::valueFit($multipleOptions, 0, count($options)) — 0..옵션수 클램프.
        multipleOptions = multipleOptions.coerceIn(0, options.size)

        // PHP: opener = userName ?? '[SYSTEM]'.
        val opener = me.name

        // vote_poll INSERT — options/selection은 jsonb(인코딩된 문자열, auction_bid aux 패턴과 동일).
        // reveal_mode는 V1 NOT NULL — PHP VoteInfo엔 대응 필드가 없어 기본 'always'(상시 공개)로 싣는다.
        // start_at은 PHP `VoteInfo.startDate = TimeUtil::now()` — 데몬의 현재 시각(world lastTurnTime)을
        // ISO-8601로 싣는다(NOT NULL; executor가 `CAST(:start_at AS timestamptz)`로 항상 바인딩하므로
        // null을 주면 NOT NULL 위반). 시작 시각은 골든 미대상(capture_vote.php는 vote_poll 상태 미독).
        recorder.recordVotePollInsert(
            linkedMapOf(
                "title" to title,
                "body" to "",
                "options" to jsonEncode(options),
                "multiple_options" to multipleOptions,
                "reveal_mode" to "always",
                "opener_general_id" to me.id,
                "opener_name" to opener,
                "start_at" to world.getState().lastTurnTime.toString(),
                "end_at" to endDate,
            ),
        )

        // PHP: $db->update('general', ['newvote' => 1], true) — 전 장수의 newvote=1 (새 설문 알림).
        markAllGeneralsNewVote()

        return result("newVote", ok = true, generalId = c.generalId)
    }

    // ── Vote.php (투표 던지기) ─────────────────────────────────────────────────────────────────────
    fun handleVoteCast(c: TurnDaemonCommand.VoteCast): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return result("voteCast", ok = false, generalId = c.generalId, reason = "장수가 존재하지 않습니다.")

        // PHP: $selection = args['selection']; if (!$selection) return '선택한 항목이 없습니다.';
        val selection = c.selection
        if (selection.isEmpty()) {
            return result("voteCast", ok = false, generalId = c.generalId, reason = "선택한 항목이 없습니다.")
        }

        // PHP: $rawVoteInfo = voteStor->getValue("vote_{voteID}"); if (!$rawVoteInfo) '설문조사가 없습니다.';
        // reader는 voteID(=vote_poll.id)로 설문 행 + 이 장수의 vote 행 존재(alreadyVoted)를 함께 read.
        val poll = votePollReader(c.voteId, c.generalId)
            ?: return result("voteCast", ok = false, generalId = c.generalId, reason = "설문조사가 없습니다.")

        // PHP: if ($voteInfo->endDate && $voteInfo->endDate < now) return '설문조사가 종료되었습니다.';
        if (poll.expired) {
            return result("voteCast", ok = false, generalId = c.generalId, reason = "설문조사가 종료되었습니다.")
        }

        // PHP: if ($voteInfo->multipleOptions >= 1 && count($selection) > $voteInfo->multipleOptions)
        //        return '선택한 항목이 너무 많습니다.';
        if (poll.multipleOptions >= 1 && selection.size > poll.multipleOptions) {
            return result("voteCast", ok = false, generalId = c.generalId, reason = "선택한 항목이 너무 많습니다.")
        }

        // PHP: foreach ($selection as $sel) if ($sel >= $optionsCnt) return '선택한 항목이 없습니다.';
        // (0-기반 인덱스라 범위 밖은 옵션수 이상). 음수도 잘못된 인덱스 — PHP는 양수 인덱스만 옴.
        if (selection.any { it >= poll.optionsCount || it < 0 }) {
            return result("voteCast", ok = false, generalId = c.generalId, reason = "선택한 항목이 없습니다.")
        }

        // PHP: sort($selection, SORT_NUMERIC); — 오름차순 수치 정렬 후 INSERT.
        val sortedSelection = selection.sorted()

        // PHP: $nationID = SELECT nation FROM general WHERE no = generalID.
        val nationId = me.nationId

        if (poll.alreadyVoted) {
            return result("voteCast", ok = false, generalId = c.generalId, reason = "이미 설문조사를 완료하였습니다.")
        }
        if (!recorder.tryRecordVoteInsert(poll.id, me.id)) {
            return result("voteCast", ok = false, generalId = c.generalId, reason = "이미 설문조사를 완료하였습니다.")
        }

        // FK: vote.vote_id는 vote_poll(id)를 참조한다(V1 baseline FK). 게임-레벨 voteID는 vote_poll.id와
        // 동일 단조 시퀀스이므로 c.voteId == poll.id 이지만 — 방금 read한 설문의 DB id(poll.id)를 직접 써서
        // FK 대상을 명시적으로 일치시킨다(게임 voteID를 그대로 자식 행에 쓰던 FK 갭 수정).
        recorder.recordVoteInsert(
            linkedMapOf(
                "vote_id" to poll.id,
                "general_id" to me.id,
                "nation_id" to nationId,
                "selection" to jsonEncode(sortedSelection),
            ),
        )

        // PHP: $voteReward = $gameStor->getValue('develcost') * 5; $general->increaseVar('gold', $voteReward);
        val voteReward = develCost() * 5

        // PHP: tryUniqueItemLottery($uniqueRng, $general, '설문조사') — 매 투표마다 추첨 RNG가 돈다
        //      (win/no-win 불문, 정확히 PHP 순서). seed = simpleSerialize(hiddenSeed,'voteUnique',voteID,generalID).
        val lottery = runVoteUniqueLottery(me, c.voteId)

        // gold 보상 + (당첨 시) 아이템 슬롯을 한 번에 적용하고 diffGeneral로 dirty 기록 (SINGLE source).
        val pre = PerTurnOverlay.toLogicGeneral(me)
        var nextItems = me.role.items
        val wonType = lottery.itemType
        val wonCode = lottery.itemCode
        if (lottery.won && wonType != null && wonCode != null) {
            // PHP giveRandomUniqueItem: $general->setVar($itemType, $itemCode) — itemType ∈ horse/weapon/book/item.
            nextItems = applyItemSlot(nextItems, wonType, wonCode)
        }
        val next = me.copy(
            gold = me.gold + voteReward,
            role = me.role.copy(items = nextItems),
        )
        world.applyGeneralDirtyFree(next)
        recorder.diffGeneral(pre, PerTurnOverlay.toLogicGeneral(next))

        // 당첨 로그 격리: PHP giveRandomUniqueItem(func.php:1602-1605)는 당첨 시 4종 로그를 push 한다 —
        // pushGeneralActionLog/pushGeneralHistoryLog/pushGlobalActionLog/pushGlobalHistoryLog, 모두
        // 아이템 NAME(`buildItemClass(code)->getName()` code→name lookup)+JosaUtil(이/을)+<C>/<Y>/<D> 태그
        // 조합이다(raw itemCode가 아님). 이 4종 문자열은 어떤 PHP 로그 골든에도 미캡처(capture_vote.php는
        // draw 스트림+결과만 박제, 로그 문자열 미수집)다.
        // → faithful-never-fabricate(rule 5): 추측 한국어 로그를 발명하지 않고 격리한다. state 효과(보상 골드
        //   + 당첨 아이템 슬롯 + general dirty)는 위에서 이미 적용했고 골든-검증됨. giveRandomUniqueItem 4종
        //   로그는 PHP 로그 골든 미캡처 → 격리(fabricate 금지). 추후 capture_vote 로그 캡처 후 포팅.

        return result("voteCast", ok = true, generalId = c.generalId)
    }

    // ── AddComment.php ───────────────────────────────────────────────────────────────────────────
    fun handleVoteComment(c: TurnDaemonCommand.VoteComment): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return result("voteComment", ok = false, generalId = c.generalId, reason = "장수가 존재하지 않습니다.")

        // PHP: validator required('text') + lengthMin('text', 1) → 빈 문자열이면 인테이크에서 걸러짐.
        // text = mb_substr(args['text'], 0, 200) — 200자 절단.
        if (c.text.isEmpty()) {
            return result("voteComment", ok = false, generalId = c.generalId, reason = "올바르지 않은 입력입니다.")
        }
        val text = mbSubstr(c.text, 200)

        // PHP: GeneralLite::createObjFromDB → generalName / nationID / nationName(getStaticNation).
        val nationName = world.getNationById(me.nationId)?.name ?: ""

        recorder.recordVoteCommentInsert(
            linkedMapOf(
                "vote_id" to c.voteId,
                "general_id" to me.id,
                "nation_id" to me.nationId,
                "general_name" to me.name,
                "nation_name" to nationName,
                "text" to text,
            ),
        )

        // PHP AddComment.php::launch는 null을 반환한다(성공 시그널만). 데몬 결과로는 ok=true 매핑.
        return result("voteComment", ok = true, generalId = c.generalId)
    }

    // ── NewVote.php::closeOldVote (명시적 마감 인테이크) ──────────────────────────────────────────
    fun handleVoteClose(c: TurnDaemonCommand.VoteClose): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return result("voteClose", ok = false, generalId = c.generalId, reason = "장수가 존재하지 않습니다.")

        closeOldVote(c.voteId, me.id)
        return result("voteClose", ok = true, generalId = c.generalId)
    }

    // ── 추첨 구동 ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Vote.php:111-117 의 voteUnique 추첨을 매 투표마다 구동한다. seed/draw 순서는 `:logic`
     * [opensamguk.logic.actions.vote] 가 패러티-검증된 형태로 제공한다(골든 16/16 green).
     *
     * 입력 파생 (func.php:1611-1703, '설문조사' 분기):
     *  - genCount  = count(general WHERE npc<2)  (live world에서 npcState<2 카운트).
     *  - itemTypeCnt = count(GameConst::$allItems) (= 4: horse/weapon/book/item).
     *  - maxCnt    = itemTypeCnt (장비 슬롯 점유로 감소하나, golden은 module-free actor라 4 고정).
     *  - prob0     = deriveSurveyProb0(genCount, itemTypeCnt) — 70% 투표율 공식 + 0.25 clamp + /sqrt(7).
     *  - itemPool  = deriveItemPool(GameConst::$allItems) — weight==0 스킵, 삽입 순서 보존.
     *
     * inertNpc = (npcState>=2) — func.php 첫 줄 가드. true면 RNG를 0 draw로 즉시 미당첨.
     */
    private fun runVoteUniqueLottery(me: TurnGeneral, voteId: Int): opensamguk.logic.actions.vote.LotteryResult {
        val rng = voteUniqueRng(hiddenSeed(), voteId, me.id)
        val inputs = lotteryInputsProvider?.invoke(me) ?: deriveLotteryInputs(me)
        return tryUniqueItemLottery(rng, inputs, inertNpc = me.npcState >= 2)
    }

    /** func.php의 '설문조사' 분기 입력을 live world + GameConst로 파생. golden은 PINNED inputs를 직접 먹인다. */
    internal fun deriveLotteryInputs(me: TurnGeneral): VoteLotteryInputs {
        val genCount = world.listGenerals().count { it.npcState < 2 }
        val allItems = allItemsTable()
        val itemTypeCnt = allItems.size
        // maxCnt = itemTypeCnt 에서 비-buyable(이미 보유한 유니크) 슬롯만큼 감소 — module-free actor는 4 고정.
        val occupied = me.role.items.let { listOfNotNull(it.horse, it.weapon, it.book, it.item).count { c -> c != "None" } }
        val maxCnt = (itemTypeCnt - occupied).coerceAtLeast(0)
        return VoteLotteryInputs(
            genCount = genCount,
            itemTypeCnt = itemTypeCnt,
            maxCnt = maxCnt,
            prob0 = deriveSurveyProb0(genCount, itemTypeCnt),
            moreProb = opensamguk.logic.actions.vote.MORE_PROB,
            itemPool = deriveItemPool(allItems),
        )
    }

    // ── 부수 효과 헬퍼 ────────────────────────────────────────────────────────────────────────────

    /**
     * PHP closeOldVote(voteID): 설문의 endDate가 비어 있으면 now()로 채워 마감(이미 있으면 no-op).
     * NewVote.php::closeOldVote — `$lastVoteInfo->endDate`가 이미 있으면 return, 없으면
     * `endDate = TimeUtil::now()`로 setValue. opensamguk은 KV blob 대신 vote_poll 행이므로 vote_poll
     * UPDATE 채널(recordVotePollUpdate)로 end_at/closed_at/updated_at = now를 기록해 실제로 영속시킨다.
     *
     * @param generalId reader의 per-general alreadyVoted EXISTS 인자(마감 판정엔 무영향이나 read seam이 요구).
     */
    private fun closeOldVote(voteId: Int, generalId: Int) {
        if (voteId <= 0) return // PHP: lastVote==0이면 vote_0 — 존재하지 않아 no-op.
        val poll = votePollReader(voteId, generalId) ?: return // 설문 없음 → no-op (PHP `!$rawLastVoteInfo`).
        if (poll.hasEndDate) return // 이미 종료일 있음 → no-op (PHP `if ($lastVoteInfo->endDate) return;`).
        // PHP: $lastVoteInfo->endDate = TimeUtil::now(). 데몬의 now() = world lastTurnTime(설문 INSERT의
        // start_at과 동일 시각 소스). end_at(PHP endDate 대응) + closed_at(opensamguk 명시 마감 마커) +
        // updated_at을 now로 SET — executor가 모두 CAST(... AS timestamptz)로 바인딩한다(UPDATE-CHANNEL CONTRACT).
        val now = world.getState().lastTurnTime.toString()
        recorder.recordVotePollUpdate(
            poll.id,
            linkedMapOf(
                "end_at" to now,
                "closed_at" to now,
                "updated_at" to now,
            ),
        )
    }

    /** PHP: $db->update('general', ['newvote'=>1], true) — 전 장수의 newvote=1 (general.meta). */
    private fun markAllGeneralsNewVote() {
        for (g in world.listGenerals()) {
            if ((g.meta["newvote"] as? Number)?.toInt() == 1) continue
            val pre = PerTurnOverlay.toLogicGeneral(g)
            val nextMeta = LinkedHashMap(g.meta)
            nextMeta["newvote"] = 1
            val next = g.copy(meta = nextMeta)
            world.applyGeneralDirtyFree(next)
            recorder.diffGeneral(pre, PerTurnOverlay.toLogicGeneral(next))
        }
    }

    /** PHP setVar(itemType, itemCode) — horse/weapon/book/item 슬롯 중 하나에 코드를 싣는다. */
    private fun applyItemSlot(items: GeneralItems, itemType: String, itemCode: String): GeneralItems = when (itemType) {
        "horse" -> items.copy(horse = itemCode)
        "weapon" -> items.copy(weapon = itemCode)
        "book" -> items.copy(book = itemCode)
        "item" -> items.copy(item = itemCode)
        else -> items // 알 수 없는 슬롯 타입 — 무시(추첨 풀은 4 슬롯만 산출).
    }

    // ── env readers (world meta / general meta) ───────────────────────────────────────────────────

    private fun hiddenSeed(): String = world.getState().meta["hiddenSeed"] as? String ?: ""

    private fun develCost(): Int = (world.getState().meta["develcost"] as? Number)?.toInt() ?: 0

    private fun userGrade(me: TurnGeneral): Int = (me.meta["userGrade"] as? Number)?.toInt() ?: 0

    @Suppress("UNCHECKED_CAST")
    private fun hasVoteAcl(me: TurnGeneral): Boolean {
        val acl = me.meta["acl"] as? List<*> ?: return false
        return acl.any { it?.toString() == "vote" }
    }

    /**
     * GameConst::$allItems 미러 — itemType -> (itemCode -> weight) 삽입 순서 보존. world.getState().meta의
     * `allItems`(시드가 실어 보낸 GameConst 미러)를 우선 쓰고, 없으면 빈 맵(deriveItemPool이 빈 풀 → 추첨 시
     * 빈 풀 require 실패 방지를 위해 호출부에서 won 시에만 풀을 쓴다). 골든은 PINNED inputs를 직접 먹여 우회.
     */
    @Suppress("UNCHECKED_CAST")
    private fun allItemsTable(): Map<String, Map<String, Int>> {
        val raw = world.getState().meta["allItems"] as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, Map<String, Int>>()
        for ((type, cats) in raw) {
            val typeStr = type?.toString() ?: continue
            val catMap = cats as? Map<*, *> ?: continue
            val inner = LinkedHashMap<String, Int>()
            for ((code, w) in catMap) {
                val codeStr = code?.toString() ?: continue
                inner[codeStr] = (w as? Number)?.toInt() ?: 0
            }
            out[typeStr] = inner
        }
        return out
    }

    /** PHP mb_substr($s, 0, $len) — 코드포인트 기준 절단(한글 안전). */
    private fun mbSubstr(s: String, len: Int): String {
        if (s.length <= len) return s
        return s.substring(0, s.offsetByCodePoints(0, minOf(len, s.codePointCount(0, s.length))))
    }

    /** 4개 vote intake op가 공유하는 collapsed 결과 — [BoardActionResult] 형태 재사용(type/ok/generalId/reason?). */
    private fun result(type: String, ok: Boolean, generalId: Int, reason: String? = null): TurnDaemonCommandResult =
        BoardActionResult(type = type, ok = ok, generalId = generalId, reason = reason)
}

/**
 * `vote_{voteID}` 설문 상태 스냅샷 (PHP `VoteInfo` 의 데몬-게이트 read 서브셋). VoteCast/closeOldVote
 * 게이트가 읽는 필드만 담는다. opensamguk에서는 vote_poll 행 + 해당 장수의 vote 행 존재 여부로 채운다.
 */
data class VotePollState(
    /**
     * vote_poll.id (DB SERIAL = read seam이 조회한 설문의 실제 DB id). 게임-레벨 voteID와 동일 단조
     * 시퀀스지만, vote/vote_comment의 vote_id FK 대상을 명시적으로 일치시키려 read한 DB id를 그대로 쓴다.
     */
    val id: Int,
    /** vote_poll.multiple_options. */
    val multipleOptions: Int,
    /** count(vote_poll.options). */
    val optionsCount: Int,
    /** end_at < now (이미 종료). */
    val expired: Boolean = false,
    /** end_at IS NOT NULL (closeOldVote no-op 판정). */
    val hasEndDate: Boolean = false,
    /** 이 장수가 이미 투표함 (vote UNIQUE(vote_id,general_id) 존재) — 보상/추첨 중복 방지. */
    val alreadyVoted: Boolean = false,
)

package opensamguk.engine.intake

import opensamguk.common.wire.BoardActionResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralItems
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.actions.vote.UniqueItemEntry
import opensamguk.logic.actions.vote.VoteLotteryInputs
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F4 Wave 투표 풀-사이클 테스트 — 설문조사 개설/투표/댓글/마감 intake 명령.
 *
 * 각 테스트는 스펙이 강제하는 데몬 경로를 실행한다:
 *   command → [VoteHandler] (검증 + 부수 효과) → [ChangeRecorder] 투표 INSERT 채널 +
 *   [diffGeneral] dirty → [DatabaseHooks.toFlushPayload] → flush payload 검증.
 *
 * 투표(VoteCast)는 보상, 중복 방지, 저장 payload를 검증한다.
 */
class VoteIntakeSliceTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")
    private val hiddenSeed = "4bcea5ec9686d42f64f02329932f35b1"
    private val develCost = 20

    private fun lotteryInputs(probability: Double = 0.0) = VoteLotteryInputs(
        genCount = 1,
        itemTypeCnt = 1,
        maxCnt = 1,
        prob0 = probability,
        moreProb = 0.0,
        itemPool = listOf(UniqueItemEntry("horse", "test_horse", 1)),
    )

    // ── world / handler fixtures ──────────────────────────────────────────────────────────────────

    private fun general(
        id: Int = 1,
        nationId: Int = 1,
        gold: Int = 100,
        npcState: Int = 0,
        meta: Map<String, Any?> = mapOf("userGrade" to 5),
    ) = TurnGeneral(
        id = id, name = "유비", nationId = nationId, cityId = 5, troopId = 0,
        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
        officerLevel = 12, gold = gold, npcState = npcState, turnTime = t0,
        role = GeneralRole(items = GeneralItems()), meta = meta,
    )

    private fun world(general: TurnGeneral = general()): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0,
                meta = linkedMapOf("hiddenSeed" to hiddenSeed, "develcost" to develCost, "lastVote" to 0),
            ),
            generals = listOf(general),
            nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", gold = 1000)),
            worldId = opensamguk.common.world.WorldId((TurnWorldState(
                id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0,
                meta = linkedMapOf("hiddenSeed" to hiddenSeed, "develcost" to develCost, "lastVote" to 0),
            )).id),
        ),
    )

    /** TurnRunService와 동일하게 flush payload를 구성한다 (recorder + world + drained dirty). */
    private fun flush(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())

    /**
     * poll-state double을 반환하는 핸들러 (production reader 경로 = 디스패처가 VotePollRepository를 어댑트해
     * 주입하는 `(voteId, generalId) -> VotePollState?`와 동일 시그니처). 테스트는 실제 설문 행을 흉내내는
     * double을 먹인다. voteId/generalId 인자는 무시하고 고정 [poll]을 돌려준다(read seam의 cast-가드 필드만 검증).
     */
    private fun handler(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        poll: VotePollState? = null,
        pinned: VoteLotteryInputs? = null,
    ) = VoteHandler(
        world, recorder,
        votePollReader = { _, _ -> poll },
        lotteryInputsProvider = pinned?.let { p -> { _ -> p } },
    )

    // ── NewVote ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `newVote records a vote_poll INSERT and flushes it`() {
        val world = world()
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleNewVote(
            TurnDaemonCommand.NewVote(
                generalId = 1, title = "다음 천도지?", options = listOf("성도", "한중"), multipleOptions = 1,
            ),
        )

        assertTrue((res as BoardActionResult).ok)
        assertEquals("newVote", res.type)
        val rows = recorder.votePollInserts()
        assertEquals(1, rows.size)
        val c = rows.single().columns
        assertEquals("다음 천도지?", c["title"])
        assertEquals("[\"성도\",\"한중\"]", c["options"])  // jsonEncode(options)
        assertEquals(1, c["multiple_options"])
        assertEquals(1, c["opener_general_id"])
        assertEquals("유비", c["opener_name"])
        // PHP db->update('general', ['newvote'=>1], true) — opener도 newvote=1로 dirty.
        assertEquals(setOf(1), recorder.dirtyGeneralIds())
        assertEquals(1, world.getGeneralById(1)!!.meta["newvote"])
        // 풀-사이클: recorder 채널이 flush payload로 수렴한다.
        assertEquals(1, flush(world, recorder).votePollInserts.size)
    }

    @Test
    fun `newVote with no options is denied`() {
        val world = world()
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleNewVote(
            TurnDaemonCommand.NewVote(generalId = 1, title = "제목만", options = emptyList()),
        )
        assertFalse((res as BoardActionResult).ok)
        assertEquals("항목이 없습니다.", res.reason)
        assertTrue(recorder.votePollInserts().isEmpty())
    }

    @Test
    fun `newVote by a non-admin general is denied`() {
        // userGrade < 5 AND no 'vote' acl → PHP '권한이 부족합니다.'
        val world = world(general(meta = mapOf("userGrade" to 1)))
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleNewVote(
            TurnDaemonCommand.NewVote(generalId = 1, title = "x", options = listOf("a")),
        )
        assertEquals("권한이 부족합니다.", (res as BoardActionResult).reason)
        assertTrue(recorder.votePollInserts().isEmpty())
    }

    @Test
    fun `newVote multipleOptions is clamped to 0_count`() {
        val world = world()
        val recorder = ChangeRecorder()
        // multipleOptions 99 > count(options)=2 → valueFit 0..2 → 2.
        handler(world, recorder).handleNewVote(
            TurnDaemonCommand.NewVote(generalId = 1, title = "t", options = listOf("a", "b"), multipleOptions = 99),
        )
        assertEquals(2, recorder.votePollInserts().single().columns["multiple_options"])
    }

    // ── VoteCast validation ───────────────────────────────────────────────────────────

    @Test
    fun `voteCast win applies item slot and flushes gold and item together`() {
        val world = world(general(id = 1, gold = 100))
        val recorder = ChangeRecorder()
        val result = handler(
            world, recorder,
            poll = VotePollState(id = 2, multipleOptions = 1, optionsCount = 2),
            pinned = lotteryInputs(probability = 1.0),
        ).handleVoteCast(TurnDaemonCommand.VoteCast(generalId = 1, voteId = 2, selection = listOf(0)))

        assertTrue((result as BoardActionResult).ok)
        assertEquals(1, recorder.voteInserts().size)
        val general = world.getGeneralById(1)!!
        assertEquals(100 + develCost * 5, general.gold)
        assertEquals("test_horse", general.role.items.horse)
        val payload = flush(world, recorder)
        val patch = payload.updatedGenerals.single { it.id == 1 }
        assertEquals(100 + develCost * 5, patch.gold)
        assertEquals("test_horse", patch.horse)
        assertEquals(1, payload.voteInserts.size)
    }

    @Test
    fun `voteCast with an empty selection is denied`() {
        val world = world()
        val recorder = ChangeRecorder()
        val res = handler(world, recorder, poll = VotePollState(id = 1, multipleOptions = 1, optionsCount = 2)).handleVoteCast(
            TurnDaemonCommand.VoteCast(generalId = 1, voteId = 1, selection = emptyList()),
        )
        assertEquals("선택한 항목이 없습니다.", (res as BoardActionResult).reason)
        assertTrue(recorder.voteInserts().isEmpty())
    }

    @Test
    fun `voteCast on a missing poll is denied`() {
        val world = world()
        val recorder = ChangeRecorder()
        val res = handler(world, recorder, poll = null).handleVoteCast(
            TurnDaemonCommand.VoteCast(generalId = 1, voteId = 99, selection = listOf(0)),
        )
        assertEquals("설문조사가 없습니다.", (res as BoardActionResult).reason)
    }

    @Test
    fun `voteCast on an expired poll is denied`() {
        val world = world()
        val recorder = ChangeRecorder()
        val res = handler(world, recorder, poll = VotePollState(id = 1, multipleOptions = 1, optionsCount = 2, expired = true)).handleVoteCast(
            TurnDaemonCommand.VoteCast(generalId = 1, voteId = 1, selection = listOf(0)),
        )
        assertEquals("설문조사가 종료되었습니다.", (res as BoardActionResult).reason)
    }

    @Test
    fun `voteCast exceeding multipleOptions is denied`() {
        val world = world()
        val recorder = ChangeRecorder()
        val res = handler(world, recorder, poll = VotePollState(id = 1, multipleOptions = 1, optionsCount = 3)).handleVoteCast(
            TurnDaemonCommand.VoteCast(generalId = 1, voteId = 1, selection = listOf(0, 1)),
        )
        assertEquals("선택한 항목이 너무 많습니다.", (res as BoardActionResult).reason)
    }

    @Test
    fun `voteCast with an out-of-range index is denied`() {
        val world = world()
        val recorder = ChangeRecorder()
        val res = handler(world, recorder, poll = VotePollState(id = 1, multipleOptions = 2, optionsCount = 2)).handleVoteCast(
            TurnDaemonCommand.VoteCast(generalId = 1, voteId = 1, selection = listOf(0, 5)),
        )
        assertEquals("선택한 항목이 없습니다.", (res as BoardActionResult).reason)
    }

    @Test
    fun `voteCast on an already-voted poll is denied (insertIgnore dedup)`() {
        val world = world(general(id = 1, gold = 100))
        val recorder = ChangeRecorder()
        val res = handler(
            world, recorder,
            poll = VotePollState(id = 1, multipleOptions = 1, optionsCount = 2, alreadyVoted = true),
        ).handleVoteCast(
            TurnDaemonCommand.VoteCast(generalId = 1, voteId = 1, selection = listOf(0)),
        )
        assertEquals("이미 설문조사를 완료하였습니다.", (res as BoardActionResult).reason)
        assertTrue(recorder.voteInserts().isEmpty())
        // 중복이면 보상/추첨 모두 건너뛴다 — gold 불변.
        assertEquals(100, world.getGeneralById(1)!!.gold)
    }

    @Test
    fun `two vote casts in one recorder claim once before reward and lottery`() {
        val world = world(general(id = 1, gold = 100))
        val recorder = ChangeRecorder()
        var lotteryInputReads = 0
        val handler = VoteHandler(
            world = world,
            recorder = recorder,
            votePollReader = { _, _ -> VotePollState(id = 2, multipleOptions = 1, optionsCount = 2) },
            lotteryInputsProvider = {
                lotteryInputReads += 1
                lotteryInputs()
            },
        )
        val command = TurnDaemonCommand.VoteCast(generalId = 1, voteId = 2, selection = listOf(0))

        val first = handler.handleVoteCast(command) as BoardActionResult
        val duplicate = handler.handleVoteCast(command) as BoardActionResult

        assertTrue(first.ok)
        assertFalse(duplicate.ok)
        assertEquals("이미 설문조사를 완료하였습니다.", duplicate.reason)
        assertEquals(1, recorder.voteInserts().size)
        assertEquals(1, lotteryInputReads)
        assertEquals(100 + develCost * 5, world.getGeneralById(1)!!.gold)
        assertNull(world.getGeneralById(1)!!.role.items.horse)
    }

    // ── VoteComment ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `voteComment records a vote_comment INSERT and flushes it`() {
        val world = world()
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleVoteComment(
            TurnDaemonCommand.VoteComment(generalId = 1, voteId = 3, text = "성도가 낫다"),
        )

        assertTrue((res as BoardActionResult).ok)
        val rows = recorder.voteCommentInserts()
        assertEquals(1, rows.size)
        val c = rows.single().columns
        assertEquals(3, c["vote_id"])
        assertEquals(1, c["general_id"])
        assertEquals(1, c["nation_id"])
        assertEquals("유비", c["general_name"])
        assertEquals("촉", c["nation_name"])
        assertEquals("성도가 낫다", c["text"])
        assertEquals(1, flush(world, recorder).voteCommentInserts.size)
    }

    @Test
    fun `voteComment truncates text to 200 codepoints (mb_substr)`() {
        val world = world()
        val recorder = ChangeRecorder()
        val longText = "가".repeat(250) // 250 한글 코드포인트
        handler(world, recorder).handleVoteComment(
            TurnDaemonCommand.VoteComment(generalId = 1, voteId = 3, text = longText),
        )
        assertEquals(200, (recorder.voteCommentInserts().single().columns["text"] as String).length)
    }

    @Test
    fun `voteComment with empty text is denied`() {
        val world = world()
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleVoteComment(
            TurnDaemonCommand.VoteComment(generalId = 1, voteId = 3, text = ""),
        )
        assertEquals("올바르지 않은 입력입니다.", (res as BoardActionResult).reason)
        assertTrue(recorder.voteCommentInserts().isEmpty())
    }

    // ── VoteClose (vote_poll UPDATE 채널) ──────────────────────────────────────────────────────────

    @Test
    fun `voteClose records a vote_poll UPDATE (end_at_closed_at = now) and flushes it`() {
        val world = world()
        val recorder = ChangeRecorder()
        // poll.id=1, end_at 없음(hasEndDate=false) → closeOldVote가 마감을 기록한다.
        val res = handler(world, recorder, poll = VotePollState(id = 1, multipleOptions = 1, optionsCount = 2)).handleVoteClose(
            TurnDaemonCommand.VoteClose(generalId = 1, voteId = 1),
        )
        assertTrue((res as BoardActionResult).ok)
        assertEquals("voteClose", res.type)

        // UPDATE 채널에 poll.id=1 항목이 end_at/closed_at/updated_at = now(world lastTurnTime)로 기록됐다.
        val updates = recorder.votePollUpdates()
        assertEquals(setOf(1), updates.keys)
        val cols = updates.getValue(1)
        val now = t0.toString()
        assertEquals(now, cols["end_at"])
        assertEquals(now, cols["closed_at"])
        assertEquals(now, cols["updated_at"])
        // 삽입 순서 보존 (executor가 이 순서대로 SET 절 구성).
        assertEquals(listOf("end_at", "closed_at", "updated_at"), cols.keys.toList())
        // 풀-사이클: recorder UPDATE 채널이 flush payload로 수렴한다.
        assertEquals(setOf(1), flush(world, recorder).votePollUpdates.keys)
    }

    @Test
    fun `voteClose on an already-closed poll is a no-op (PHP endDate already set)`() {
        val world = world()
        val recorder = ChangeRecorder()
        // hasEndDate=true → PHP `if ($lastVoteInfo->endDate) return;` 대응 no-op.
        handler(world, recorder, poll = VotePollState(id = 1, multipleOptions = 1, optionsCount = 2, hasEndDate = true)).handleVoteClose(
            TurnDaemonCommand.VoteClose(generalId = 1, voteId = 1),
        )
        assertTrue(recorder.votePollUpdates().isEmpty())
    }

    @Test
    fun `voteClose on a missing poll is a no-op`() {
        val world = world()
        val recorder = ChangeRecorder()
        handler(world, recorder, poll = null).handleVoteClose(
            TurnDaemonCommand.VoteClose(generalId = 1, voteId = 9),
        )
        assertTrue(recorder.votePollUpdates().isEmpty())
    }

    @Test
    fun `newVote with keepOldVote=false closes the previous poll via the UPDATE channel`() {
        // lastVote=5 → 새 voteID=6 개설. keepOldVote=false면 직전 설문(voteId=5)을 closeOldVote로 마감한다
        // (NewVote.php keepOldVote 미지정 → false). lastVote는 lastVoteReader 주입으로 5로 세팅.
        val world = world(general(meta = mapOf("userGrade" to 5)))
        val recorder = ChangeRecorder()
        val h = VoteHandler(
            world, recorder,
            // 직전 설문(voteId=5)은 미마감(hasEndDate=false) → closeOldVote가 UPDATE를 기록한다.
            votePollReader = { voteId, _ -> if (voteId == 5) VotePollState(id = 5, multipleOptions = 1, optionsCount = 2) else null },
            lastVoteReader = { 5 },
        )
        val res = h.handleNewVote(
            TurnDaemonCommand.NewVote(generalId = 1, title = "신설문", options = listOf("a", "b"), keepOldVote = false),
        )
        assertTrue((res as BoardActionResult).ok)
        // 새 vote_poll INSERT 1건 + 직전 설문(id=5) close UPDATE 1건.
        assertEquals(1, recorder.votePollInserts().size)
        assertEquals(setOf(5), recorder.votePollUpdates().keys)
        assertEquals(t0.toString(), recorder.votePollUpdates().getValue(5)["closed_at"])
    }

    @Test
    fun `newVote with keepOldVote=true does NOT close the previous poll`() {
        val world = world(general(meta = mapOf("userGrade" to 5)))
        val recorder = ChangeRecorder()
        val h = VoteHandler(
            world, recorder,
            votePollReader = { voteId, _ -> if (voteId == 5) VotePollState(id = 5, multipleOptions = 1, optionsCount = 2) else null },
            lastVoteReader = { 5 },
        )
        h.handleNewVote(
            TurnDaemonCommand.NewVote(generalId = 1, title = "신설문", options = listOf("a", "b"), keepOldVote = true),
        )
        assertEquals(1, recorder.votePollInserts().size)
        assertTrue(recorder.votePollUpdates().isEmpty()) // keepOldVote=true → 마감 안 함.
    }

    @Test
    fun `voteCast writes the read poll DB id into the vote FK (not the raw game voteID)`() {
        // FK 갭 수정 검증: 게임 voteID와 read한 vote_poll.id가 다른 경우, 자식 vote 행은 poll.id를 써야 한다.
        val world = world(general(id = 1, gold = 100))
        val recorder = ChangeRecorder()
        // reader가 게임 voteID=9에 대해 DB id=42인 설문을 돌려준다(시퀀스 어긋남 가정).
        val h = VoteHandler(
            world, recorder,
            votePollReader = { _, _ -> VotePollState(id = 42, multipleOptions = 1, optionsCount = 2) },
            lotteryInputsProvider = { _ -> lotteryInputs() }, // Deterministic no-win inputs.
        )
        h.handleVoteCast(TurnDaemonCommand.VoteCast(generalId = 1, voteId = 9, selection = listOf(0)))
        // vote.vote_id = poll.id(42), 게임 voteID(9)가 아니다.
        assertEquals(42, recorder.voteInserts().single().columns["vote_id"])
    }
}

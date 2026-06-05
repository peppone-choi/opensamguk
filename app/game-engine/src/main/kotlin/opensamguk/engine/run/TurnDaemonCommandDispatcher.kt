package opensamguk.engine.run

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.auction.AuctionBidHandler
import opensamguk.engine.auction.AuctionFinalizeHandler
import opensamguk.engine.betting.PlaceBetHandler
import opensamguk.engine.intake.BoardHandler
import opensamguk.engine.intake.InheritResetHandler
import opensamguk.engine.intake.NationFinanceSetterHandler
import opensamguk.engine.intake.TournamentEnrollHandler
import opensamguk.engine.intake.TroopHandler
import opensamguk.engine.intake.VoteHandler
import opensamguk.engine.intake.VotePollState
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import opensamguk.infra.read.BoardPostRepository
import opensamguk.infra.read.VotePollRepository

/**
 * Routes drained [TurnDaemonCommand]s to their engine handlers.
 *
 * **P6 keystone seam.** Before this, [opensamguk.engine.redis.RedisCommandStream.readCommands]'s
 * result was DISCARDED in [TurnRunService.runTick] (the inline comment admitted the dispatcher was
 * "assembled by the consuming P3 waves" and never built), so every command-intake feature — auction
 * bids/finalize, and the P6/P7 commands that follow — was inert. This dispatcher routes each drained
 * command to the handler that owns its type.
 *
 * **Partial by design (incremental P6 build).** Only the command types with a built engine handler
 * are routed; everything else returns `null` = "no engine handler wired yet". That covers two
 * distinct cases that both legitimately produce no result here:
 *  - **control commands** (Run/Pause/Resume/Shutdown/GetStatus) that gate *when* the daemon ticks —
 *    they are consumed by [opensamguk.engine.redis.RedisCommandStream] advancing its cursor, not by a
 *    state-mutating handler;
 *  - **not-yet-built handlers** (troop/tournament/permission/vote/… — most of the ~26 command types)
 *    that later P6/P7 waves will plug in by adding a `when` branch here.
 *
 * Handlers are per-run plain classes built against the live [InMemoryTurnWorld] (the snapshot source
 * of truth), mirroring the sibling turn handlers — NOT Spring beans (the world is per-run state).
 *
 * Result publishing (the events-stream `commandResult` channel) remains deferred per the P1 DECISION
 * in [TurnRunService]; the caller currently discards the returned result.
 */
class TurnDaemonCommandDispatcher(
    private val world: InMemoryTurnWorld,
    recorder: ChangeRecorder,
    auctionRepository: AuctionRepository,
    auctionBidRepository: AuctionBidRepository,
    boardPostRepository: BoardPostRepository,
    /**
     * vote_poll/vote 설문 상태 read seam (F4 Wave 투표). VoteCast/closeOldVote 게이트가 PHP Vote.php의
     * cast 가드(설문 존재/만료/선택수/이미 투표)를 충실히 재현하려면 설문 행을 read 해야 한다. null이면
     * VoteHandler는 기본 stub(항상 "설문 없음")로 동작한다(board/auction read-repo와 동일 주입 패턴).
     */
    private val votePollRepository: VotePollRepository? = null,
) {
    private val auctionBid = AuctionBidHandler(world, recorder, auctionRepository, auctionBidRepository)
    private val auctionFinalize = AuctionFinalizeHandler(world, recorder, auctionRepository, auctionBidRepository)
    private val placeBet = PlaceBetHandler(world, recorder)

    // ── F4 Wave C2 (slice A) — single-actor intake handlers (per-run, world+recorder) ──────────────
    private val nationFinance = NationFinanceSetterHandler(world, recorder)
    private val tournamentEnroll = TournamentEnrollHandler(world, recorder)
    private val inheritReset = InheritResetHandler(world, recorder)

    // ── F4 Wave C2 (slice B) — troop intake handler ──
    private val troop = TroopHandler(world, recorder)

    // ── F4 Wave C2 (슬라이스 C) — 게시판(회의실/기밀실) 인테이크 핸들러 ──
    private val board = BoardHandler(world, recorder, boardPostRepository)

    // ── F4 Wave 투표 — 설문조사(개설/투표/댓글/마감) 인테이크 핸들러 ──
    // VotePollRepository(infra read seam)를 주입 가능하면 reader 어댑터로 소비한다(BoardHandler가 board
    // read repo를 주입받는 패턴). infra `VotePollReadRow`→엔진 [VotePollState] 매핑은 여기서 담당한다
    // (DTO는 infra↔engine 모듈 사이클 회피를 위해 의도적으로 분리). repo가 null이면 핸들러 기본 stub
    // (항상 "설문 없음")로 동작한다. 매 투표의 voteUnique 추첨은 logic VoteLottery를 경유(골든 16/16 green).
    private val vote = VoteHandler(
        world, recorder,
        votePollReader = votePollRepository?.let { repo ->
            { voteId: Int, generalId: Int ->
                // now = 데몬 현재 시각(world lastTurnTime) = PHP `new DateTimeImmutable()` 만료 비교 기준.
                repo.findPollState(voteId, generalId, world.getState().lastTurnTime)?.let { row ->
                    VotePollState(
                        id = row.id,
                        multipleOptions = row.multipleOptions,
                        optionsCount = row.optionsCount,
                        expired = row.expired,
                        hasEndDate = row.hasEndDate,
                        alreadyVoted = row.alreadyVoted,
                    )
                }
            }
        } ?: { _, _ -> null },
    )

    /**
     * Dispatch one command to its handler.
     *
     * @return the handler's [TurnDaemonCommandResult], or `null` when no engine handler is wired for
     *   this command type yet (control commands + not-yet-built handlers).
     */
    fun dispatch(command: TurnDaemonCommand): TurnDaemonCommandResult? = when (command) {
        is TurnDaemonCommand.AuctionBid -> auctionBid.handle(command)
        is TurnDaemonCommand.AuctionFinalize -> auctionFinalize.handle(command)
        is TurnDaemonCommand.PlaceBet -> placeBet.handle(command)
        // ── F4 Wave C2 (slice A) intake bindings ──
        is TurnDaemonCommand.SetNotice -> nationFinance.handleSetNotice(command)
        is TurnDaemonCommand.SetScoutMsg -> nationFinance.handleSetScoutMsg(command)
        is TurnDaemonCommand.SetRate -> nationFinance.handleSetRate(command)
        is TurnDaemonCommand.SetBill -> nationFinance.handleSetBill(command)
        is TurnDaemonCommand.SetSecretLimit -> nationFinance.handleSetSecretLimit(command)
        is TurnDaemonCommand.SetBlockWar -> nationFinance.handleSetBlockWar(command)
        is TurnDaemonCommand.SetBlockScout -> nationFinance.handleSetBlockScout(command)
        is TurnDaemonCommand.TournamentEnroll -> tournamentEnroll.handle(command)
        is TurnDaemonCommand.InheritResetTurnTime -> inheritReset.handleResetTurnTime(command)
        is TurnDaemonCommand.InheritResetSpecialWar -> inheritReset.handleResetSpecialWar(command)
        is TurnDaemonCommand.InheritSetNextSpecialWar -> inheritReset.handleSetNextSpecialWar(command)
        // ── F4 Wave C2 (slice B) troop intake bindings ──
        is TurnDaemonCommand.TroopNew -> troop.handleNew(command)
        is TurnDaemonCommand.TroopJoin -> troop.handleJoin(command)
        is TurnDaemonCommand.TroopExit -> troop.handleExit(command)
        is TurnDaemonCommand.TroopKick -> troop.handleKick(command)
        is TurnDaemonCommand.TroopSetName -> troop.handleSetName(command)
        // ── F4 Wave C2 (슬라이스 C) 게시판 인테이크 바인딩 ──
        is TurnDaemonCommand.BoardArticle -> board.handleArticle(command)
        is TurnDaemonCommand.BoardComment -> board.handleComment(command)
        // ── F4 Wave 투표 인테이크 바인딩 ──
        is TurnDaemonCommand.NewVote -> vote.handleNewVote(command)
        is TurnDaemonCommand.VoteCast -> vote.handleVoteCast(command)
        is TurnDaemonCommand.VoteComment -> vote.handleVoteComment(command)
        is TurnDaemonCommand.VoteClose -> vote.handleVoteClose(command)
        else -> null
    }

    /** Dispatch a batch (one drained tick's worth), returning only the non-null results in order. */
    fun dispatchAll(commands: List<TurnDaemonCommand>): List<TurnDaemonCommandResult> =
        commands.mapNotNull { dispatch(it) }
}

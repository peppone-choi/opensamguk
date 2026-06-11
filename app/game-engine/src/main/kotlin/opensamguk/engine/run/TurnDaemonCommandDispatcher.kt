package opensamguk.engine.run

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.auction.AuctionBidHandler
import opensamguk.engine.auction.AuctionFinalizeHandler
import opensamguk.engine.auction.AuctionOpenHandler
import opensamguk.engine.betting.PlaceBetHandler
import opensamguk.engine.intake.BoardHandler
import opensamguk.engine.intake.BuildNationCandidateHandler
import opensamguk.engine.intake.ClaimNpcHandler
import opensamguk.engine.intake.MakeGeneralHandler
import opensamguk.engine.intake.DiplomacyLetterHandler
import opensamguk.engine.intake.InheritResetHandler
import opensamguk.engine.intake.MessageHandler
import opensamguk.engine.intake.NationFinanceSetterHandler
import opensamguk.engine.intake.SelectPoolHandler
import opensamguk.engine.intake.TournamentEnrollHandler
import opensamguk.engine.intake.TroopHandler
import opensamguk.engine.intake.VoteHandler
import opensamguk.engine.intake.VotePollState
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import opensamguk.infra.read.BettingRepository
import opensamguk.infra.read.BoardPostRepository
import opensamguk.infra.read.ContactReader
import opensamguk.infra.read.DiplomacyLetterRepository
import opensamguk.infra.read.GameKvRepository
import opensamguk.infra.read.SelectPoolRepository
import opensamguk.infra.read.VotePollRepository
import opensamguk.infra.read.InheritanceRepository
import opensamguk.logic.betting.BettingInfo
import opensamguk.logic.util.jsonDecode
import opensamguk.logic.util.jsonDecodeAny

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
    /**
     * W5d 외교 서신(ng_diplomacy) read seam. null이면 [DiplomacyLetterHandler]가 stub-empty(서신 없음)로
     * 동작한다(votePollRepository 주입 패턴 — IT가 DB 없이도 컴파일/실행되게).
     */
    diplomacyLetterRepository: DiplomacyLetterRepository? = null,
    /**
     * W6f 장수 선택 풀 read seam. null이면 [SelectPoolHandler]가 stub-empty(풀 항목 없음)로 동작한다.
     */
    selectPoolRepository: SelectPoolRepository? = null,
    /**
     * W6a 메시지 연락처/장수 read seam. null이면 [MessageHandler]가 stub-empty(연락처 없음)로 동작한다.
     */
    contactReader: ContactReader? = null,
    /**
     * P0-07 베팅 마스터 read seam — game_kv(table='betting'). null이면 [PlaceBetHandler]가
     * stub('해당 베팅이 없습니다')로 동작한다(다른 read-repo 주입 패턴과 동일).
     */
    gameKvRepository: GameKvRepository? = null,
    /**
     * P0-07 ng_betting 누적 합 read seam — PHP Betting.php:135의 user별 sum. null이면 누적 0 가정.
     */
    bettingRepository: BettingRepository? = null,
    /**
     * P0-07 유산포인트 read seam — `inheritance_{userID}` `previous[0]`(PHP Betting.php:133,142).
     * null이면 PlaceBetHandler 기본(world meta `inheritancePrevious` 스냅샷)으로 폴백.
     */
    inheritanceRepository: InheritanceRepository? = null,
) {
    private val auctionBid = AuctionBidHandler(world, recorder, auctionRepository, auctionBidRepository)
    private val auctionFinalize = AuctionFinalizeHandler(world, recorder, auctionRepository, auctionBidRepository)

    private val placeBet = PlaceBetHandler(
        world, recorder,
        // PHP `bettingStor->getValue("id_{n}")`(Betting.php:42-44) — BettingController.loadRawBettingInfo와
        // 동일하게 table='betting' 전 행을 맵 디코드해 id 일치 행을 찾는다(key 레이아웃 비의존).
        bettingInfoReader = gameKvRepository?.let { repo ->
            { bettingId: Int ->
                repo.findByTable("betting").firstNotNullOfOrNull { row ->
                    runCatching { jsonDecode(row.value) }.getOrNull()
                        ?.let { BettingInfo.fromKvMap(it) }
                        ?.takeIf { it.id == bettingId }
                }
            }
        } ?: { null },
        prevBetAmountDbReader = bettingRepository?.let { repo ->
            { bettingId: Int, userId: Int -> repo.sumAmountByBettingIdAndUserId(bettingId, userId).toInt() }
        } ?: { _, _ -> 0 },
        // PHP `inheritStor->getValue('previous')[0]`(Betting.php:133,142) — game_kv
        // (table='inheritance', namespace='inheritance_{owner}', key='previous') 라이브 read.
        previousPointReader = inheritanceRepository?.let { repo ->
            { ownerId: Int ->
                repo.findByTableAndNamespaceAndKey("inheritance", "inheritance_$ownerId", "previous")
                    ?.let { row ->
                        ((runCatching { jsonDecodeAny(row.value) }.getOrNull() as? List<*>)
                            ?.getOrNull(0) as? Number)?.toDouble()
                    } ?: 0.0
            }
        } ?: { ownerId ->
            ((world.getState().meta["inheritancePrevious"] as? Map<*, *>)?.get(ownerId) as? Number)?.toDouble() ?: 0.0
        },
    )

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

    // ── W6a 메시지 인테이크 핸들러 (contact read seam은 nullable — null이면 stub-empty) ──
    // contactReader가 있으면 삭제 게이트용 메시지 reader(getMessageByID)도 어댑트해 주입한다.
    // infra `MessageReadRow` → 엔진 [opensamguk.engine.intake.MessageSnapshot] 매핑은 여기서 담당한다
    // (DTO는 infra↔engine 모듈 사이클 회피를 위해 의도적으로 분리, VotePollReadRow→VotePollState 패턴).
    private val message = MessageHandler(world, recorder, contactReader).apply {
        if (contactReader != null) {
            messageReader = { msgId ->
                contactReader.findMessage(msgId)?.let { row ->
                    opensamguk.engine.intake.MessageSnapshot(
                        id = row.id,
                        hasAction = row.hasAction,
                        type = row.type,
                        srcGeneralId = row.srcGeneralId,
                        srcNationId = row.srcNationId,
                        destGeneralId = row.destGeneralId,
                        destNationId = row.destNationId,
                        time = row.time,
                        validUntil = row.validUntil,
                        deletable = row.deletable,
                        receiverMessageId = row.receiverMessageId,
                        text = row.text,
                        srcArray = row.srcArray,
                        destArray = row.destArray,
                        option = row.option,
                    )
                }
            }
        }
    }

    // ── W6c 경매 개설 핸들러 (AuctionBidHandler와 동일 read repo 주입) ──
    private val auctionOpen = AuctionOpenHandler(world, recorder, auctionRepository, auctionBidRepository)

    // ── W5d 외교 서신 핸들러 (ng_diplomacy read seam은 nullable) ──
    private val diplomacyLetter = DiplomacyLetterHandler(world, recorder, diplomacyLetterRepository)

    // ── W6f 장수 선택 풀 핸들러 (RNG-bearing — 골든 게이트는 /parity-wave; read seam은 nullable) ──
    private val selectPool = SelectPoolHandler(world, recorder, selectPoolRepository)

    // ── W6d 건국 후보(거병) 핸들러 (RNG-bearing — 골든 게이트는 /parity-wave) ──
    private val buildNation = BuildNationCandidateHandler(world, recorder)

    // ── B1 장수생성(재야→일반) 핸들러 (RNG-bearing — 골든 게이트는 MakeGeneralGoldenTest) ──
    private val makeGeneral = MakeGeneralHandler(world, recorder)

    // ── B2 장수빙의 핸들러 ──
    private val claimNpc = ClaimNpcHandler(world, recorder)

    /**
     * Dispatch one command to its handler.
     *
     * @return the handler's [TurnDaemonCommandResult], or `null` when no engine handler is wired for
     *   this command type yet (control commands + not-yet-built handlers).
     */
    fun dispatch(command: TurnDaemonCommand): TurnDaemonCommandResult? = when (command) {
        is TurnDaemonCommand.ClaimNpc -> claimNpc.handle(command)
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
        is TurnDaemonCommand.BuyHiddenBuff -> inheritReset.handleBuyHiddenBuff(command)
        is TurnDaemonCommand.BuyRandomUnique -> inheritReset.handleBuyRandomUnique(command)
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
        // ── W6a 메시지 인테이크 바인딩 ──
        is TurnDaemonCommand.SendMessage -> message.handleSend(command)
        is TurnDaemonCommand.DeleteMessage -> message.handleDelete(command)
        // ── W6c 경매 개설 바인딩 ──
        is TurnDaemonCommand.AuctionOpenBuyRice -> auctionOpen.handleBuyRice(command)
        is TurnDaemonCommand.AuctionOpenSellRice -> auctionOpen.handleSellRice(command)
        is TurnDaemonCommand.AuctionOpenUnique -> auctionOpen.handleUnique(command)
        // ── W5d 외교 서신 바인딩 ──
        is TurnDaemonCommand.DiploSendLetter -> diplomacyLetter.handleSend(command)
        is TurnDaemonCommand.DiploRollbackLetter -> diplomacyLetter.handleRollback(command)
        is TurnDaemonCommand.DiploDestroyLetter -> diplomacyLetter.handleDestroy(command)
        // ── W6f 장수 선택 풀 바인딩 (RNG-bearing) ──
        is TurnDaemonCommand.SelectPoolPick -> selectPool.handlePick(command)
        is TurnDaemonCommand.SelectPoolUpdate -> selectPool.handleUpdate(command)
        // ── W6d 건국 후보(거병) 바인딩 (RNG-bearing) ──
        is TurnDaemonCommand.BuildNationCandidate -> buildNation.handle(command)
        // ── B1 장수생성 바인딩 ──
        is TurnDaemonCommand.MakeGeneral -> makeGeneral.handle(command)
        else -> null
    }

    /** Dispatch a batch (one drained tick's worth), returning only the non-null results in order. */
    fun dispatchAll(commands: List<TurnDaemonCommand>): List<TurnDaemonCommandResult> =
        commands.mapNotNull { dispatch(it) }
}

package opensamguk.engine.run

import opensamguk.common.constants.GameConst
import opensamguk.common.wire.CityGarrisonRecruit
import opensamguk.common.wire.CityTransport
import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.auction.AuctionBidHandler
import opensamguk.engine.auction.AuctionFinalizeHandler
import opensamguk.engine.auction.AuctionOpenHandler
import opensamguk.engine.betting.PlaceBetHandler
import opensamguk.engine.intake.BoardHandler
import opensamguk.engine.intake.AccountCommandHandler
import opensamguk.engine.intake.AdminGeneralModerationHandler
import opensamguk.engine.intake.AdminWorldSettingsHandler
import opensamguk.engine.intake.BuildNationCandidateHandler
import opensamguk.engine.intake.ClaimNpcHandler
import opensamguk.engine.intake.MakeGeneralHandler
import opensamguk.engine.intake.DiplomacyLetterHandler
import opensamguk.engine.intake.DiplomaticMessageHandler
import opensamguk.engine.intake.InheritResetHandler
import opensamguk.engine.intake.InstantActionHandler
import opensamguk.engine.intake.MessageHandler
import opensamguk.engine.intake.MessageSnapshot
import opensamguk.engine.intake.NationFinanceSetterHandler
import opensamguk.engine.intake.NpcPolicyHandler
import opensamguk.engine.intake.PersonnelHandler
import opensamguk.engine.intake.ProfileIconSyncHandler
import opensamguk.engine.intake.RaiseInvaderMessageHandler
import opensamguk.engine.intake.SelectPoolHandler
import opensamguk.engine.intake.TournamentEnrollHandler
import opensamguk.engine.intake.TroopHandler
import opensamguk.engine.intake.VoteHandler
import opensamguk.engine.intake.VotePollState
import opensamguk.engine.tournament.ProductionTournamentBettingPort
import opensamguk.engine.tournament.TournamentAdminHandler
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ProcessNationCommand
import opensamguk.engine.v2.V2CityLedgerStore
import opensamguk.engine.v2.V2CityTransportHandler
import opensamguk.engine.v2.V2GarrisonRecruitHandler
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
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.engine.turn.KvKey
import opensamguk.logic.betting.BettingInfo
import opensamguk.logic.util.jsonDecode
import opensamguk.logic.util.jsonDecodeAny
import opensamguk.logic.v2.command.V2CommandAvailability
import opensamguk.logic.v2.command.V2CommandRegistry
import opensamguk.logic.world.RaiseInvaderSpec
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException

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
 * Result publishing — W0-4부터 [TurnRunService]가 [dispatchEnvelopes]의 (requestId, result) 쌍을
 * durable outbox payload로 회신한다(P1 DECISION 해제).
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
    processNationCommand: ProcessNationCommand? = null,
    raiseInvader: (RaiseInvaderSpec) -> Int = { 0 },
    /**
     * OPENSAM-153 (v2 R4) — v2 도시 원장. null이면(v2 샌드박스 게이트 off) [v2GarrisonRecruit]도 null이고
     * `dispatch`가 [V2GarrisonRecruitHandler.unavailable]로 fail-closed deny한다(v1 동작 불변).
     */
    v2CityLedger: V2CityLedgerStore? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * PHP `inheritStor->getValue('previous')[0]`(Betting.php:133,142 / Auction.php:300) — game_kv
     * (table='inheritance', namespace='inheritance_{owner}', key='previous') 라이브 read.
     * [PlaceBetHandler]와 [AuctionBidHandler]가 동일 seam 을 공유한다(바퀴 20 정본).
     */
    private val persistedPreviousPointReader: (Int) -> Double = inheritanceRepository?.let { repo ->
        { ownerId: Int ->
            repo.findByTableAndNamespaceAndKey("inheritance", "inheritance_$ownerId", "previous")
                ?.let { row ->
                    ((runCatching { jsonDecodeAny(row.value) }.getOrNull() as? List<*>)
                        ?.getOrNull(0) as? Number)?.toDouble()
                } ?: 0.0
        }
    } ?: { ownerId ->
        (ownerScopedMetaValue(world.getState().meta["inheritancePrevious"] as? Map<*, *>, ownerId) as? Number)
            ?.toDouble()
            ?: 0.0
    }
    private val previousPointReader: (Int) -> Double = { ownerId ->
        recorder.effectiveInheritancePoint(ownerId, "previous")?.first
            ?: persistedPreviousPointReader(ownerId)
    }

    private val geniusRemainingReader: () -> Int = gameKvRepository?.let { repo ->
        {
            repo.findByTable("game_env").firstNotNullOfOrNull { row ->
                if (row.namespace == "game_env" && row.key == "genius") {
                    (runCatching { jsonDecodeAny(row.value) }.getOrNull() as? Number)?.toInt()
                } else {
                    null
                }
            } ?: GameConst.defaultMaxGenius
        }
    } ?: { GameConst.defaultMaxGenius }

    private val persistedLastStatResetReader: (Int) -> List<Int> = gameKvRepository?.let { repo ->
        { ownerId: Int ->
            repo.findByTable("user")
                .firstOrNull { it.namespace == "user_$ownerId" && it.key == "last_stat_reset" }
                ?.let { row ->
                    (runCatching { jsonDecodeAny(row.value) }.getOrNull() as? List<*>)
                        ?.mapNotNull { (it as? Number)?.toInt() }
                }
                ?: emptyList()
        }
    } ?: { ownerId ->
        (ownerScopedMetaValue(world.getState().meta["lastStatReset"] as? Map<*, *>, ownerId) as? List<*>)
            ?.mapNotNull { (it as? Number)?.toInt() }
            ?: emptyList()
    }
    private val lastStatResetReader: (Int) -> List<Int> = { ownerId ->
        (recorder.kvDirty()[KvKey("user", "user_$ownerId", "last_stat_reset")] as? List<*>)
            ?.mapNotNull { (it as? Number)?.toInt() }
            ?: persistedLastStatResetReader(ownerId)
    }

    private val auctionBid = AuctionBidHandler(
        world, recorder, auctionRepository, auctionBidRepository,
        previousPointReader = previousPointReader,
    )
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
        previousPointReader = previousPointReader,
    )

    // ── F4 Wave C2 (slice A) — single-actor intake handlers (per-run, world+recorder) ──────────────
    private val nationFinance = NationFinanceSetterHandler(world, recorder)
    private val npcPolicy = NpcPolicyHandler(world, recorder)
    private val tournamentEnroll = TournamentEnrollHandler(world, recorder)
    private val tournamentBettingPort =
        if (gameKvRepository != null && bettingRepository != null && inheritanceRepository != null) {
            ProductionTournamentBettingPort(world, recorder, gameKvRepository, bettingRepository, inheritanceRepository)
        } else {
            null
        }
    private val lastTournamentBettingIdReader: () -> Int = gameKvRepository?.let { repo ->
        {
            repo.findByTable("game_env").firstNotNullOfOrNull { row ->
                if (row.namespace == "game_env" && row.key == "last_tournament_betting_id") {
                    (runCatching { jsonDecodeAny(row.value) }.getOrNull() as? Number)?.toInt()
                } else {
                    null
                }
            } ?: 0
        }
    } ?: { 0 }
    private val tournamentAdmin = TournamentAdminHandler(
        world,
        recorder,
        lastBettingIdReader = lastTournamentBettingIdReader,
        bettingPort = tournamentBettingPort,
    )
    private val inheritReset = InheritResetHandler(
        world,
        recorder,
        previousPointReader = previousPointReader,
        lastStatResetReader = lastStatResetReader,
    )
    private val instantAction = InstantActionHandler(world, recorder)
    private val account = AccountCommandHandler(world, recorder)

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
    private val scopedMessageReader: ((Int) -> MessageSnapshot?)? = contactReader?.let { reader ->
        { msgId ->
            reader.findMessage(world.worldId, msgId)?.let { row ->
                MessageSnapshot(
                    id = row.id,
                    mailbox = row.mailbox,
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
    private val message = MessageHandler(world, recorder, contactReader).apply {
        messageReader = scopedMessageReader
    }
    private val diplomaticMessage = DiplomaticMessageHandler(
        world = world,
        recorder = recorder,
        processNationCommand = processNationCommand,
        messageReader = scopedMessageReader,
    )
    private val raiseInvaderMessage = RaiseInvaderMessageHandler(
        world = world,
        recorder = recorder,
        messageReader = scopedMessageReader,
        raiseInvader = raiseInvader,
    )

    // ── W6c 경매 개설 핸들러 (AuctionBidHandler와 동일 read repo 주입) ──
    private val auctionOpen = AuctionOpenHandler(world, recorder, auctionRepository, auctionBidRepository)

    // ── W5d 외교 서신 핸들러 (ng_diplomacy read seam은 nullable) ──
    private val diplomacyLetter = DiplomacyLetterHandler(world, recorder, diplomacyLetterRepository)
    private val personnel = PersonnelHandler(world, recorder)

    // ── W6f 장수 선택 풀 핸들러 (RNG-bearing — 골든 게이트는 /parity-wave; read seam은 nullable) ──
    private val selectPool = SelectPoolHandler(world, recorder, selectPoolRepository)

    // ── W6d 건국 후보(거병) 핸들러 (RNG-bearing — 골든 게이트는 /parity-wave) ──
    private val buildNation = BuildNationCandidateHandler(world, recorder)

    // ── B1 장수생성(재야→일반) 핸들러 (RNG-bearing — 골든 게이트는 MakeGeneralGoldenTest) ──
    private val makeGeneral = MakeGeneralHandler(
        world,
        recorder,
        previousPointReader = previousPointReader,
        geniusRemainingReader = geniusRemainingReader,
    )

    // ── B2 장수빙의 핸들러 ──
    private val claimNpc = ClaimNpcHandler(world, recorder)

    // ── OPENSAM-94 프로필 아이콘 typed sync 핸들러 (eligibility 재평가 + owner/npc predicate) ──
    private val profileIconSync = ProfileIconSyncHandler(world, recorder)
    private val adminGeneralModeration = AdminGeneralModerationHandler(world, recorder)
    private val adminWorldSettings = AdminWorldSettingsHandler(world, recorder)

    // ── OPENSAM-153 (v2 R4) — 도시병사 보충 핸들러 (원장 없으면 null, dispatch에서 fail-closed deny) ──
    private val v2GarrisonRecruit = v2CityLedger?.let { V2GarrisonRecruitHandler(world, recorder, it) }
    private val v2CityTransport = v2CityLedger?.let { V2CityTransportHandler(world, recorder, it) }

    /**
     * Dispatch one command to its handler.
     *
     * @return the handler's [TurnDaemonCommandResult], or `null` when no engine handler is wired for
     *   this command type yet (control commands + not-yet-built handlers).
     */
    fun dispatch(command: TurnDaemonCommand): TurnDaemonCommandResult? {
        val now = Instant.now(clock)
        return dispatch(command, now, now)
    }

    private fun dispatch(
        command: TurnDaemonCommand,
        sentAt: Instant,
        executionAt: Instant,
    ): TurnDaemonCommandResult? = when (command) {
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
        is TurnDaemonCommand.NpcPolicyUpdate -> npcPolicy.handle(command)
        is TurnDaemonCommand.TournamentEnroll -> tournamentEnroll.handle(command)
        is TurnDaemonCommand.TournamentStart -> tournamentAdmin.handleStart(command)
        is TurnDaemonCommand.TournamentReset -> tournamentAdmin.handleReset(command)
        is TurnDaemonCommand.InheritResetTurnTime -> inheritReset.handleResetTurnTime(command)
        is TurnDaemonCommand.InheritResetSpecialWar -> inheritReset.handleResetSpecialWar(command)
        is TurnDaemonCommand.InheritSetNextSpecialWar -> inheritReset.handleSetNextSpecialWar(command)
        is TurnDaemonCommand.ResetStat -> inheritReset.handleResetStat(command)
        is TurnDaemonCommand.BuyHiddenBuff -> inheritReset.handleBuyHiddenBuff(command)
        is TurnDaemonCommand.BuyRandomUnique -> inheritReset.handleBuyRandomUnique(command)
        is TurnDaemonCommand.DieOnPrestart -> instantAction.handleDieOnPrestart(command)
        is TurnDaemonCommand.DropItem -> instantAction.handleDropItem(command)
        is TurnDaemonCommand.InstantRetreat -> instantAction.handleInstantRetreat(command)
        is TurnDaemonCommand.CheckOwner -> instantAction.handleCheckOwner(command)
        is TurnDaemonCommand.SetMySetting -> account.handleSetting(command)
        is TurnDaemonCommand.Vacation -> account.handleVacation(command)
        is TurnDaemonCommand.ReadLatestMessage -> account.handleReadLatest(command)
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
        is TurnDaemonCommand.SendMessage -> message.handleSend(command, sentAt)
        is TurnDaemonCommand.DeleteMessage -> message.handleDelete(command, sentAt)
        is TurnDaemonCommand.AcceptDiplomaticMessage -> diplomaticMessage.handleAccept(command)
        is TurnDaemonCommand.DeclineDiplomaticMessage -> diplomaticMessage.handleDecline(command)
        is TurnDaemonCommand.AcceptRaiseInvaderMessage -> raiseInvaderMessage.handle(command)
        // ── W6c 경매 개설 바인딩 ──
        is TurnDaemonCommand.AuctionOpenBuyRice -> auctionOpen.handleBuyRice(command)
        is TurnDaemonCommand.AuctionOpenSellRice -> auctionOpen.handleSellRice(command)
        is TurnDaemonCommand.AuctionOpenUnique -> auctionOpen.handleUnique(command)
        // ── W5d 외교 서신 바인딩 ──
        is TurnDaemonCommand.DiploSendLetter -> diplomacyLetter.handleSend(command)
        is TurnDaemonCommand.DiploRollbackLetter -> diplomacyLetter.handleRollback(command)
        is TurnDaemonCommand.DiploDestroyLetter -> diplomacyLetter.handleDestroy(command)
        // ── W0-7 wire-계약 widen 스텁 — 명시적 deny-with-log (silent-drop 금지). 핸들러 구현은
        //    W1 에이전트 소관: diploRespondLetter=G, appoint/kick/changePermission=N. ──
        is TurnDaemonCommand.DiploRespondLetter -> diplomacyLetter.handleRespond(command)
        is TurnDaemonCommand.Appoint -> personnel.handleAppoint(command)
        is TurnDaemonCommand.Kick -> personnel.handleKick(command)
        is TurnDaemonCommand.ChangePermission -> personnel.handleChangePermission(command)
        // ── W6f 장수 선택 풀 바인딩 (RNG-bearing) ──
        is TurnDaemonCommand.SelectPoolPick -> selectPool.handlePick(command)
        is TurnDaemonCommand.SelectPoolUpdate -> selectPool.handleUpdate(command)
        is TurnDaemonCommand.SelectPoolRefresh -> selectPool.handleRefresh(command)
        // ── W6d 건국 후보(거병) 바인딩 (RNG-bearing) ──
        is TurnDaemonCommand.BuildNationCandidate -> buildNation.handle(command)
        is TurnDaemonCommand.MakeGeneral -> makeGeneral.handle(command)
        // ── OPENSAM-94 프로필 아이콘 typed sync 바인딩 (fanout, durable IMMEDIATE terminal 결과) ──
        is TurnDaemonCommand.ProfileIconSync -> profileIconSync.handle(command)
        is TurnDaemonCommand.AdminGeneralModeration -> adminGeneralModeration.handle(command)
        is TurnDaemonCommand.AdminWorldSettings -> adminWorldSettings.handle(command)
        // ── OPENSAM-153 (v2 R4) — 도시병사 보충. 원장 없음 = fail-closed deny (null 반환 금지: null이면
        //    FE result-poll이 RESOLVED를 영영 못 보고 PENDING에 갇힌다). ──
        is CityGarrisonRecruit -> v2PrecheckFailure(command)?.let {
            V2GarrisonRecruitHandler.rejected(command, it.reason, it.code)
        } ?: expirationFailure(command.expiresAt, executionAt)?.let {
            V2GarrisonRecruitHandler.rejected(command, it.reason, it.code)
        } ?: (v2GarrisonRecruit?.handle(command) ?: V2GarrisonRecruitHandler.unavailable(command))
        // ── OPENSAM-154 (v2 R5) — 도시 자원 수송. 같은 fail-closed 규약. ──
        is CityTransport -> v2PrecheckFailure(command)?.let {
            V2CityTransportHandler.rejected(command, it.reason, it.code)
        } ?: expirationFailure(command.expiresAt, executionAt)?.let {
            V2CityTransportHandler.rejected(command, it.reason, it.code)
        } ?: (v2CityTransport?.handle(command) ?: V2CityTransportHandler.unavailable(command))
        else -> null
    }

    /** Dispatch a batch (one drained tick's worth), returning only the non-null results in order. */
    fun dispatchAll(commands: List<TurnDaemonCommand>): List<TurnDaemonCommandResult> =
        commands.mapNotNull { dispatch(it) }

    /**
     * W0-4 인테이크 결과 회신 채널 — 드레인된 엔벨로프 배치를 디스패치하고, 각 결과를 엔벨로프의
     * `requestId`와 쌍으로 돌려준다. 컨트롤 커맨드(Run/Pause/… — [dispatch]가 null)는 쌍을 만들지
     * 않고, deny 결과(ok=false)도 성공과 동일하게 회신된다 — 페이지가 성공 토스트를 위조하지
     * 않으려면 deny가 반드시 돌아와야 한다. 순서는 드레인 순서를 보존한다(실행 순서 = 회신 순서).
     */
    fun dispatchEnvelopes(
        envelopes: List<TurnDaemonCommandEnvelope>,
    ): List<Pair<String, TurnDaemonCommandResult>> =
        envelopes.mapNotNull { env ->
            val sentAt = try {
                Instant.parse(env.sentAt)
            } catch (_: DateTimeParseException) {
                return@mapNotNull env.requestId to invalidSentAt(env.command)
            }
            dispatch(env.command, sentAt, Instant.now(clock))?.let { env.requestId to it }
        }
}

private fun invalidSentAt(command: TurnDaemonCommand): TurnDaemonCommandResult =
    CommandLifecycleResult(
        type = "executionRejected",
        ok = false,
        commandKind = CommandInboxRepository.CommandKind.IMMEDIATE.name,
        actionCode = command.type,
        reason = "명령 발행 시각이 올바르지 않습니다.",
        code = "COMMAND_SENT_AT_INVALID",
    )

private data class ExpirationFailure(val code: String, val reason: String)

private fun v2PrecheckFailure(command: CityGarrisonRecruit): V2CommandAvailability.Blocked? =
    V2CommandRegistry.precheck(
        V2CommandRegistry.garrisonRecruitSchema.canonicalId,
        mapOf("cityId" to command.cityId, "amount" to command.amount),
    ) as? V2CommandAvailability.Blocked

private fun v2PrecheckFailure(command: CityTransport): V2CommandAvailability.Blocked? =
    V2CommandRegistry.precheck(
        V2CommandRegistry.cityTransportSchema.canonicalId,
        buildMap {
            put("fromCityId", command.fromCityId)
            put("toCityId", command.toCityId)
            put("gold", command.gold)
            put("rice", command.rice)
            put("garrison", command.garrison)
            command.routeRevision?.let { put("routeRevision", it) }
        },
    ) as? V2CommandAvailability.Blocked

private fun expirationFailure(expiresAt: String?, executionAt: Instant): ExpirationFailure? {
    if (expiresAt == null) return null
    val expiry = try {
        Instant.parse(expiresAt)
    } catch (_: DateTimeParseException) {
        return ExpirationFailure("COMMAND_EXPIRY_INVALID", "명령 만료 시각이 올바르지 않습니다.")
    }
    return if (executionAt.isBefore(expiry)) null else ExpirationFailure("COMMAND_EXPIRED", "명령이 만료되었습니다.")
}

private fun ownerScopedMetaValue(map: Map<*, *>?, ownerId: Int): Any? =
    map?.get(ownerId) ?: map?.get(ownerId.toString())

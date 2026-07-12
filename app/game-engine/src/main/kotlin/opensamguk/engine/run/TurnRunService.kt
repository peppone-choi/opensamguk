package opensamguk.engine.run

import opensamguk.common.rng.RandUtil
import opensamguk.engine.auction.AuctionExpiryDaemon
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.engine.tournament.TournamentDaemon
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import opensamguk.infra.read.BoardPostRepository
import opensamguk.infra.read.DiplomacyLetterRepository
import opensamguk.infra.read.VotePollRepository
import opensamguk.infra.read.SelectPoolRepository
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventDispatcher
import opensamguk.logic.tick.MonthlyPipeline
import opensamguk.logic.tick.GameDate
import opensamguk.logic.tick.ServerClock
import java.time.Instant

data class TurnClockSnapshot(
    val currentYear: Int,
    val currentMonth: Int,
    val currentPhase: Int,
    val currentPhaseText: String,
    val tickSeconds: Int,
    val lastTurnTime: String,
    val nextRunTime: String,
)

/**
 * P1 Task F5 / P6 Task 6 — the daemon-side run orchestrator (steps 3-7 daemon side, design §12).
 *
 * One tick end-to-end:
 *
 *   [RedisCommandStream.readCommands] (drain the control commands enqueued by game-api) →
 *   [TurnDaemonLifecycle] drains ALL due generals through the [ReservedTurnHandler] in one pass →
 *   [JdbcFlushExecutor.flush] writes the post-state in ONE transaction (JDBC-only, never an
 *   `EntityManager`) → [RealtimePublisher.publishTurnCompleted] signals the SSE relay.
 *
 * When the [MonthlyPipeline] is wired (P6), the [TurnDaemonLifecycle.MonthBoundaryDriver]
 * interleaves ONE `runMonth` per crossed boundary between per-general drains; the flush still
 * fires exactly ONCE at the clean boundary.
 *
 * **Processed-count gated, NOT wall-clock** (research §1e / N5): the lifecycle drains every due
 * general in one pass and the flush fires exactly ONCE at the clean turn boundary — never mid-pass —
 * so a parity replay observes a DB state the PHP golden also produced.
 *
 * **The flush reads the [ReservedTurnHandler.recorder] — the SINGLE dirty source (design Risk #4).**
 * The handler applies the resolver's post-state to the world through the *dirty-free* apply path, so
 * the world's own dirty set is never the write path; the recorder's [RowPatch][opensamguk.engine.turn.RowPatch]
 * dirty-ids select which world rows to flush. Logs are pushed onto the world and drained from its log
 * list (the only world-dirty signal exercised in P1).
 *
 * **`commandResult` 회신 (W0-4, P1 DECISION 해제):** 드레인된 인테이크 엔벨로프의 결과는
 * (requestId, result) 쌍으로 [RealtimePublisher.publishCommandResult]에 회신된다 — per-requestId
 * string 키 + 짧은 TTL, game-api `GET /api/command/result/{requestId}` 폴링이 소비한다.
 * `turnCompleted` realtime pub/sub → SSE relay 왕복은 그대로 유지된다.
 */
open class TurnRunService(
    private val world: InMemoryTurnWorld,
    private val commandStream: RedisCommandStream,
    private val lifecycle: TurnDaemonLifecycle,
    private val handler: ReservedTurnHandler,
    private val flushExecutor: JdbcFlushExecutor,
    private val realtimePublisher: RealtimePublisher,
    /** The monthly pipeline (null = fallback to general-only drain, P1-P4 behaviour). */
    private val pipeline: MonthlyPipeline<RandUtil>? = null,
    /** The dynamic-event dispatcher consumed by [MonthlyPipeline.runMonth]. */
    private val eventDispatcher: EventDispatcher? = null,
    /** 월간 world-event 디스패치 컨텍스트 팩토리(WorldActionContext + env 키 threading). null이면 bare
     *  Context 폴백(P1-P4 / 테스트). prod는 `WorldEventContextFactory`가 주입한다 — 없으면 월경계 정산의
     *  UpdateCitySupply 등 cast-ctx leaf가 `ctx as? XContext`에서 throw(턴 동결)하거나 env-read leaf가
     *  무음 no-op한다. */
    private val worldContextFactory: ((MutableMap<String, Any?>) -> EventActionContext)? = null,
    /** How long [RedisCommandStream.readCommands] blocks for a control command before the tick proceeds. */
    private val commandBlockMs: Long = 0,
    /** JPA read repository for auction lookups (P6 T0.7). */
    private val auctionRepository: AuctionRepository? = null,
    /** JPA read repository for auction bid lookups (P6 T0.7). */
    private val auctionBidRepository: AuctionBidRepository? = null,
    /** board_post 조회용 JPA read 리포지토리 (F4 C2 슬라이스 C — 댓글의 글 is_secret read). */
    private val boardPostRepository: BoardPostRepository? = null,
    /** vote_poll/vote 조회용 JDBC read seam (F4 Wave 투표 — VoteCast/closeOldVote 설문 cast 가드). */
    private val votePollRepository: VotePollRepository? = null,
    /** diplomacy_letter 조회용 JDBC read seam (W5d 외교 서신 — send prev/rollback/destroy 가드). */
    private val diplomacyLetterRepository: DiplomacyLetterRepository? = null,
    /** 연락처/메시지 조회용 JDBC read seam (W6a 메시지 — DeleteMessage getMessageByID 게이트). */
    private val contactReader: opensamguk.infra.read.ContactReader? = null,
    /** game_kv read seam (P0-07 베팅 마스터 — PlaceBet 검증의 BettingInfo 조회). */
    private val gameKvRepository: opensamguk.infra.read.GameKvRepository? = null,
    /** ng_betting read seam (P0-07 — user별 누적 베팅 합, PHP Betting.php:135). */
    private val bettingRepository: opensamguk.infra.read.BettingRepository? = null,
    /** inheritance KV read seam (P0-07 — `inheritance_{owner}` previous[0], PHP Betting.php:133,142). */
    private val inheritanceRepository: opensamguk.infra.read.InheritanceRepository? = null,
    private val selectPoolRepository: SelectPoolRepository? = null,
    private val tournamentDaemon: TournamentDaemon? = null,
) {

    /**
     * Routes drained intake commands (auction bid/finalize, and the P6/P7 commands that follow) to
     * their engine handlers. Built per-run against the live [world] (mirrors the sibling per-run
     * handlers — the world is per-run state, not a Spring bean). 결과는 W0-4부터
     * [RealtimePublisher.publishCommandResult]로 per-requestId 회신된다(위 헤더 참조).
     */
    private val commandDispatcher = if (auctionRepository != null && auctionBidRepository != null && boardPostRepository != null) {
        TurnDaemonCommandDispatcher(
            world, handler.recorder, auctionRepository, auctionBidRepository, boardPostRepository,
            // votePollRepository는 옵셔널 — null이면 VoteHandler가 기본 stub("설문 없음")로 동작한다.
            votePollRepository,
            // diplomacyLetterRepository는 옵셔널 — null이면 DiplomacyLetterHandler가 stub-empty("서신 없음")로 동작한다.
            diplomacyLetterRepository = diplomacyLetterRepository,
            // contactReader는 옵셔널 — null이면 MessageHandler가 stub('메시지가 없습니다')로 동작한다(삭제 게이트만 영향).
            contactReader = contactReader,
            // P0-07 베팅 read seam 3종 — null이면 PlaceBetHandler가 stub('해당 베팅이 없습니다')로 동작한다.
            gameKvRepository = gameKvRepository,
            bettingRepository = bettingRepository,
            inheritanceRepository = inheritanceRepository,
            selectPoolRepository = selectPoolRepository,
        )
    } else {
        null
    }

    /**
     * Scans and expires auctions whose closeDate has passed. Built per-run against the live [world].
     * Runs after command dispatch and before the monthly boundary / flush.
     */
    private val auctionExpiryDaemon = if (auctionRepository != null && auctionBidRepository != null) {
        AuctionExpiryDaemon(auctionRepository, auctionBidRepository)
    } else {
        null
    }

    /** Outcome of one [runTick]: the resolved turns + whether a `turnCompleted` was published. */
    data class TickResult(
        val handled: List<ReservedTurnHandler.HandledTurn>,
        val flushedGenerals: Int,
        val flushedCities: Int,
        val flushedLogs: Int,
        val turnCompletedAt: String,
        val lastTurnTime: String,
    )

    /**
     * Drive ONE tick. The control commands drained from [commandStream] gate when game-api asks the
     * daemon to run; in P1 they are consumed (the stream cursor advances) and the tick proceeds.
     *
     * When [pipeline] and [eventDispatcher] are wired (P6), the [MonthBoundaryDriver] interleaves
     * the per-general drain with one `runMonth` per crossed boundary. The flush still fires exactly
     * ONCE after all drains and monthlies.
     */
    /** The next due run time (`lastTurnTime + tickSeconds`); the daemon loop waits until this arrives. */
    open fun nextRunTime(): Instant = lifecycle.nextRunTime()

    open fun clockSnapshot(): TurnClockSnapshot {
        val state = world.getState()
        val phase = state.currentPhase.coerceIn(1, 3)
        return TurnClockSnapshot(
            currentYear = state.currentYear,
            currentMonth = state.currentMonth,
            currentPhase = phase,
            currentPhaseText = GameDate(state.currentYear, state.currentMonth, phase).phaseText,
            tickSeconds = state.tickSeconds,
            lastTurnTime = state.lastTurnTime.toString(),
            nextRunTime = nextRunTime().toString(),
        )
    }

    open fun runIntakeCommands(blockMs: Long = 1): Int {
        val envelopes = commandStream.readEnvelopes(blockMs)
        if (envelopes.isEmpty()) return 0

        val intakeResults = commandDispatcher?.dispatchEnvelopes(envelopes).orEmpty()

        val state = world.getState()
        val base = buildFlushPayload()
        val worldState = base.worldStateUpdate.toMutableMap()
        worldState["id"] = state.id
        worldState["current_year"] = state.currentYear
        worldState["current_month"] = state.currentMonth
        worldState["current_phase"] = state.currentPhase
        worldState["last_turn_time"] = state.lastTurnTime.toString()
        worldState["max_nation_id"] = (state.meta["maxNationId"] as? Number)?.toInt() ?: 0
        worldState["max_general_id"] = (state.meta["maxGeneralId"] as? Number)?.toInt() ?: 0
        val payload = base.copy(worldStateUpdate = worldState)
        flushExecutor.flush(payload)
        handler.recorder.clear()
        intakeResults.forEach { (requestId, result) ->
            realtimePublisher.publishCommandResult(requestId, result, sentAtIso = Instant.now().toString())
        }
        return envelopes.size
    }

    open fun runTick(runTime: Instant = lifecycle.nextRunTime()): TickResult {
        // 1. drain the control-command stream (run/pause/troopJoin/...) AND route each command to its
        //    engine handler via [commandDispatcher] (P6: the intake seam that was previously dropped).
        //    Control commands (run/pause/...) advance the cursor and return null from the dispatcher;
        //    intake commands (auction bid/finalize, …) route to their handler. The reserved
        //    general-turn ACTIONS live in the general_turn ring (ReservedTurnRepository), NOT on this
        //    stream.
        //
        //    W0-4 인테이크 결과 회신: 엔벨로프째 드레인해 각 (requestId, result) 쌍을
        //    [RealtimePublisher.publishCommandResult]로 SET한다(짧은 TTL — game-api 폴링이 읽는다).
        //    deny(ok=false)도 회신한다 — 페이지가 성공 토스트를 위조하지 않으려면 deny가 돌아와야
        //    한다. 이 publish는 Redis 휘발성 회신이며 DB 쓰기가 아니다(one-daemon-write-rule 비위반).
        val envelopes = commandStream.readEnvelopes(commandBlockMs)
        val intakeResults = commandDispatcher?.dispatchEnvelopes(envelopes).orEmpty()

        // 2. month boundary interleave (if pipeline is wired)
        val handled: List<ReservedTurnHandler.HandledTurn>
        val crossed: Int
        if (pipeline != null && eventDispatcher != null) {
            val driver = TurnDaemonLifecycle.MonthBoundaryDriver(
                drain = { upto -> lifecycle.runTick(upto) },
                runMonth = { nextTurn ->
                    val state = world.getState()
                    val startYear = (state.meta["startYear"] as? Number)?.toInt() ?: 0
                    val startTime = Instant.parse(
                        state.meta["startTime"] as? String ?: Instant.now().toString()
                    )
                    val turnTerm = state.tickSeconds / 60
                    val oldDate = ServerClock.turnDate(
                        ServerClock.subTurn(nextTurn, turnTerm),
                        startYear,
                        startTime,
                        turnTerm,
                    )
                    pipeline.runMonth(
                        nextTurn = nextTurn,
                        startYear = startYear,
                        startTime = startTime,
                        turnTerm = turnTerm,
                        oldYear = oldDate.year,
                        oldMonth = oldDate.month,
                        oldPhase = oldDate.phase,
                        dispatcher = { target, env ->
                            val supplier = {
                                mutableMapOf<String, Any?>(
                                    "year" to env.year,
                                    "month" to env.month,
                                    "phase" to env.phase,
                                    "currentEventID" to env.currentEventID,
                                )
                            }
                            // worldContextFactory가 있으면 WorldActionContext(+ env 키)를 공급 — 월간
                            // world-event leaf(UpdateCitySupply 등)가 그 ctx를 캐스팅/읽는다. 없으면 bare.
                            if (worldContextFactory != null) {
                                eventDispatcher.run(target, contextFactory = worldContextFactory, envSupplier = supplier)
                            } else {
                                eventDispatcher.run(target, envSupplier = supplier)
                            }
                        },
                    )
                },
                runMonthWhen = { nextTurn ->
                    val state = world.getState()
                    val startYear = (state.meta["startYear"] as? Number)?.toInt() ?: 0
                    val startTime = Instant.parse(
                        state.meta["startTime"] as? String ?: Instant.now().toString()
                    )
                    val turnTerm = state.tickSeconds / 60
                    ServerClock.turnDate(nextTurn, startYear, startTime, turnTerm).phase == 1
                },
            )
            val state = world.getState()
            val isUnitedState = state.meta["isunited"] as? Int ?: 0
            crossed = driver.run(state.lastTurnTime, runTime, state.tickSeconds / 60, isUnitedState)
            handled = emptyList() // lifecycle.runTick inside driver already handles generals
        } else {
            // Fallback: original behaviour when pipeline is not wired
            handled = lifecycle.runTick(runTime)
            crossed = 0
        }

        tournamentDaemon?.processTournament(world, handler.recorder, runTime)
        auctionExpiryDaemon?.checkExpiredAuctions(world, handler.recorder, runTime)

        // 3. flush the recorder's dirty rows + the world's logs in ONE transaction (JDBC-only).
        //
        // world_state 행에는 **post-tick 클럭**(새 연/월 + 이번 runTime = lastTurnTime)을 동봉한다 —
        // PHP 도 턴 트랜잭션 안에서 새 연/월을 기록한다. 이걸 step 4 이후(다음 틱 flush)로 미루면
        // world_state 가 항상 한 틱 구값이라, 재기동 시 마지막으로 처리된 월이 1회 재적용된다
        // (2026-06-12 s1 실증 — lastTurnTime 영속화 직후에도 1틱 지연 잔존). 로그 스탬핑(toLogRow)은
        // 여전히 flush 전 state(구 연/월)를 읽는다 — 골든이 핀한 라벨링이라 불변.
        val previousTurnTime = world.getState().lastTurnTime
        val preState = world.getState()
        val startYear = (preState.meta["startYear"] as? Number)?.toInt() ?: 0
        val startTime = Instant.parse(preState.meta["startTime"] as? String ?: Instant.now().toString())
        val turnTerm = preState.tickSeconds / 60
        val newDate = ServerClock.turnDate(runTime, startYear, startTime, turnTerm)
        val payload = buildFlushPayload().copy(
            worldStateUpdate = linkedMapOf(
                "id" to preState.id,
                "current_year" to newDate.year,
                "current_month" to newDate.month,
                "current_phase" to newDate.phase,
                "last_turn_time" to runTime.toString(),
                "isunited" to ((preState.meta["isunited"] as? Number)?.toInt() ?: 0),
                "max_nation_id" to ((preState.meta["maxNationId"] as? Number)?.toInt() ?: 0),
                "max_general_id" to ((preState.meta["maxGeneralId"] as? Number)?.toInt() ?: 0),
            ),
        )
        flushExecutor.flush(payload)
        // 수명이 긴 recorder를 리셋해 이번 tick의 델타가 다음 tick에 재방출되지 않게 한다 — 특히
        // INSERT 전용 채널(board/betting/auction_bid/message)은 그러지 않으면 매 tick 행을 중복 INSERT한다.
        // flush 성공 시에만: 위에서 throw되면 이 호출을 건너뛰어 다음 tick이 재시도한다.
        handler.recorder.clear()

        intakeResults.forEach { (requestId, result) ->
            realtimePublisher.publishCommandResult(requestId, result, sentAtIso = Instant.now().toString())
        }

        // 4. advance the world calendar and publish the coarse turnCompleted realtime signal.
        world.setCurrentDate(newDate.year, newDate.month, newDate.phase)
        world.setLastTurnTime(runTime)
        val atIso = runTime.toString()
        val lastTurnTimeIso = previousTurnTime.toString()
        val turnNumber = computeTurnNumber(previousTurnTime, startYear, startTime, turnTerm)
        realtimePublisher.publishTurnCompleted(
            atIso,
            lastTurnTimeIso,
            newDate.year,
            newDate.month,
            turnNumber,
            newDate.phase,
            newDate.phaseText,
        )

        return TickResult(
            handled = handled,
            flushedGenerals = payload.updatedGenerals.size,
            flushedCities = payload.updatedCities.size,
            flushedLogs = payload.logEntries.size,
            turnCompletedAt = atIso,
            lastTurnTime = lastTurnTimeIso,
        )
    }

    /**
     * Map the tick's accumulated state → an infra [FlushPayload], CONVERGED onto the single superset
     * builder [DatabaseHooks.toFlushPayload] (T0.3). Before this convergence `buildFlushPayload`
     * flushed ONLY general+city+logs and silently DROPPED every nation/rank/kv/diplomacy/deleted-nation
     * delta — so a nation gold change, a rank bump, a setNationMeta KV write, or a diplomacy state
     * transition ran in memory and vanished at flush. The recorder ([ReservedTurnHandler.recorder]) is
     * the lone dirty source for the dirty rows + rank + KV deltas; the world's drained [DirtyState]
     * carries created/deleted lifecycle effects + logs.
     */
    private fun buildFlushPayload(): FlushPayload {
        val dirty = world.consumeDirtyState()
        return DatabaseHooks.toFlushPayload(world, handler.recorder, dirty)
    }

    /**
     * Compute the absolute turn number (0-based) from the install epoch.
     * Mirrors [ServerClock.turnDate] math: `num = intdiv(cutTurn - startTime, turnTerm*60)`.
     */
    private fun computeTurnNumber(
        turnTime: Instant,
        startYear: Int,
        startTime: Instant,
        turnTerm: Int,
    ): Int {
        val curturn = ServerClock.cutTurn(turnTime, turnTerm)
        val num = Math.floorDiv(curturn.epochSecond - startTime.epochSecond, turnTerm.toLong() * 60L)
        return num.toInt()
    }
}

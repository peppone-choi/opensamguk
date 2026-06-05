package opensamguk.engine.config

import opensamguk.common.rng.RandUtil
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.run.MonthlyPostUpdateHook
import opensamguk.engine.run.TurnRunService
import opensamguk.engine.turn.AiTurnAdapter
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ProcessNationCommand
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import opensamguk.infra.read.BoardPostRepository
import opensamguk.infra.read.VotePollRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.domain.LastTurn
import opensamguk.engine.world.WorldEventContextFactory
import opensamguk.logic.event.EventDispatcher
import opensamguk.logic.event.EventStore
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.tick.CheckStatistic
import opensamguk.logic.tick.MonthScopedRng
import opensamguk.logic.tick.MonthlyClock
import opensamguk.logic.tick.MonthlyPipeline
import opensamguk.logic.tick.PreUpdateMonthly
import opensamguk.logic.tick.ServerClock
import org.springframework.beans.factory.annotation.Value
import java.time.Instant
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * F-LOOP — the production assembly seam: the `@Bean TurnRunService` that finally wires the daemon's
 * run orchestrator with ALL its real collaborators in the RUNNING engine, plus the supporting beans
 * the per-run handler/lifecycle/AI/nation-pass need.
 *
 * **Why this config exists.** Every piece of the turn loop already existed and was tested individually
 * (the `@Bean InMemoryTurnWorld` in [BootstrapConfig], the `@Lazy monthlyPipeline`/`monthlyPostUpdateHook`
 * in [EngineEventConfig], [TurnRunService] / [TurnDaemonLifecycle] / [ReservedTurnHandler] /
 * [RedisCommandStream] / [RealtimePublisher]), but NOTHING assembled them into a Spring bean and NOTHING
 * scheduled the drain → tick. [TurnDaemonRunner] consumes the `TurnRunService` this config builds.
 *
 * **Single-dirty-source preserved.** The same [ChangeRecorder] instance is threaded into the handler,
 * the nation processor, AND the AI adapter, so all per-turn mutations land on ONE recorder — the lone
 * dirty source the flush reads (P2 Risk #4). The world is the `@Bean` from [BootstrapConfig]; everything
 * else here is per-game state that happens to live for the daemon's lifetime (the engine runs ONE game).
 *
 * **One-daemon-write rule.** Construction is JDBC/read-repo only ([JdbcFlushExecutor],
 * [ReservedTurnRepository], the JPA read repos); the WRITE path is exclusively the recorder → flush
 * executor. No `EntityManager` write — [opensamguk.engine.flush.DaemonNoEntityManagerTest] stays green.
 *
 * The per-run collaborators (handler, lifecycle, AI adapter, nation processor) are `@Lazy` so they
 * resolve against the `@Lazy`-initialized world bean only when the loop actually starts — keeping
 * `@SpringBootTest` context loads (which never start the loop) from forcing a full world materialization
 * beyond what [BootstrapConfig] already does.
 */
@Configuration
class DaemonLoopConfig {

    /** The infra JDBC flush executor (JDBC-only write path; not auto-configured by infra). */
    @Bean
    fun jdbcFlushExecutor(
        jdbc: NamedParameterJdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ): JdbcFlushExecutor = JdbcFlushExecutor(jdbc, TransactionTemplate(transactionManager))

    /** Reads the reserved `(actionCode, argJson)` for a due general / chief from the rings. */
    @Bean
    fun reservedTurnRepository(jdbc: NamedParameterJdbcTemplate): ReservedTurnRepository =
        ReservedTurnRepository(jdbc)

    /**
     * vote_poll/vote 설문 상태 read seam (F4 Wave 투표). VoteCast/closeOldVote 설문 cast 가드의 read 경로.
     * JDBC read 전용 — write 경로는 [JdbcFlushExecutor] step-8e 뿐(one-daemon-write 규칙).
     */
    @Bean
    fun votePollRepository(jdbc: NamedParameterJdbcTemplate): VotePollRepository =
        VotePollRepository(jdbc)

    @Bean
    fun redisCommandStream(
        template: StringRedisTemplate,
        @Value("\${opensamguk.profile}") profile: String,
    ): RedisCommandStream = RedisCommandStream(template, profile)

    @Bean
    fun realtimePublisher(
        template: StringRedisTemplate,
        @Value("\${opensamguk.profile}") profile: String,
    ): RealtimePublisher = RealtimePublisher(template, profile)

    /**
     * The fully-wired daemon run orchestrator — the one bean [TurnDaemonRunner] drives.
     *
     * Construction mirrors the proven gate path (`TurnRunServiceIT` + `AiSelectionGateIT`):
     *  - the per-general AI window opens via [AiTurnAdapter.beginGeneralTurn] (ONE GeneralAI per general),
     *  - the general pass replaces the reserved command via [AiTurnAdapter.chooseGeneralTurn] for NPCs,
     *  - the nation pass (officer_level>=5) runs BEFORE the general pass via [ProcessNationCommand] with
     *    the [AiTurnAdapter.chooseNationTurn] interpose,
     *  - the [MonthlyPipeline] + [EventDispatcher] interleave one runMonth per crossed boundary,
     *  - the flush fires exactly ONCE at the clean boundary (P2 contract — handled inside runTick).
     */
    @Bean
    @Lazy
    fun turnRunService(
        world: InMemoryTurnWorld,
        commandStream: RedisCommandStream,
        flushExecutor: JdbcFlushExecutor,
        realtimePublisher: RealtimePublisher,
        reservedTurnRepository: ReservedTurnRepository,
        generalActionPipeline: GeneralActionPipeline,
        eventDispatcher: EventDispatcher,
        eventStore: EventStore,
        auctionRepository: AuctionRepository,
        auctionBidRepository: AuctionBidRepository,
        boardPostRepository: BoardPostRepository,
        votePollRepository: VotePollRepository,
    ): TurnRunService {
        val state = world.getState()
        val hiddenSeed = state.meta["hiddenSeed"] as? String ?: ""
        val startYear = (state.meta["startYear"] as? Number)?.toInt() ?: state.currentYear
        val turnTerm = state.tickSeconds / 60

        val registry = CommandRegistry(generalActionPipeline)

        // ONE GeneralAI per general per turn — its single RandUtil is threaded through BOTH the nation
        // pass (chooseNationTurn, stream PREFIX) and the general pass (chooseGeneralTurn, continuation).
        // Built BEFORE the handler so the handler's general-pass aiHook can reference it.
        val ai = AiTurnAdapter(
            world = world,
            registry = registry,
            hiddenSeed = hiddenSeed,
            startYear = startYear,
            turnTerm = turnTerm,
            pipeline = generalActionPipeline,
            reservedCommandNameOf = { gid -> reservedTurnRepository.readReserved(gid, 0).actionCode },
        )

        // The scenario number selects the founding `secretlimit` (>= 1000 ⇒ 1, the live-server branch).
        // Prefer the seeded world meta (ScenarioSeedRunner), fall back to the SCENARIO_CODE env fence
        // (default scenario_1010 ⇒ 1010 ⇒ secretlimit 1), else 0.
        val scenario = (state.meta["scenario"] as? Number)?.toInt()
            ?: System.getenv("SCENARIO_CODE")?.removePrefix("scenario_")?.toIntOrNull()
            ?: 0

        // The general-pass AI interpose (R-SEAM §2): the handler gates this hook on isAiControlled
        // internally, so a human general runs its reserved command verbatim and an NPC runs the AI choice.
        val handler = ReservedTurnHandler(
            world = world,
            registry = registry,
            hiddenSeed = hiddenSeed,
            startYear = startYear,
            scenario = scenario,
            aiHook = { generalId, reserved -> ai.chooseGeneralTurn(generalId, reserved) },
        )

        // The nation pass writes through the SAME recorder the handler owns — the lone dirty source the
        // flush reads (P2 Risk #4). The handler exposes its internal ChangeRecorder via `.recorder`.
        val nationProcessor = ProcessNationCommand(world, handler.recorder, hiddenSeed)

        // The monthly pipeline is PER-RUN state: its PostUpdate hook writes through `handler.recorder` —
        // the SAME lone dirty source as the handler + nation processor (single-dirty-source, P2 Risk #4).
        // It is therefore built HERE, NOT as a cross-config @Bean: the previous `@Lazy @Bean monthlyPipeline`
        // (EngineEventConfig) could not see the per-run recorder, and the `@Lazy` exposure forced a Spring
        // CGLIB class proxy whose Objenesis shell had a null `monthlyRngFactory` + an unsatisfiable
        // `ChangeRecorder` bean dependency — both froze the turn-daemon clock on the first month boundary
        // (the engine never reached a clean tick in prod before this). Building it inline (this whole bean
        // is `@Lazy`, so the world is already seeded) mirrors `nationProcessor` and removes the proxy entirely.
        val startTime = Instant.parse(state.meta["startTime"] as? String ?: state.lastTurnTime.toString())
        val monthlyPipeline = MonthlyPipeline(
            monthlyRngFactory = { year, month -> MonthScopedRng.forMonth(hiddenSeed, year, month) },
            clock = MonthlyClock { nextTurn, st -> ServerClock.turnDate(nextTurn, startYear, st, turnTerm) },
            preUpdateMonthly = PreUpdateMonthly { true },
            checkStatistic = CheckStatistic { },
            postUpdateMonthly = MonthlyPostUpdateHook(world, handler.recorder, generalActionPipeline),
        )

        val lifecycle = TurnDaemonLifecycle(
            world = world,
            handler = handler,
            nationProcessor = nationProcessor,
            reservedNationActionOf = { nationId, officerLevel ->
                reservedTurnRepository.readReservedNationTurn(nationId, officerLevel, 0)
            },
            chooseNationTurn = { generalId, reserved ->
                // The lifecycle computes lastNationTurnOf(nation, officerLevel) AFTER this hook for the
                // RESERVED path, but the AI's chooseNationTurn needs the pre-turn LastTurn now. Recompute
                // it the SAME way the lifecycle does (the chief ring slot `turn_last_{officer_level}`,
                // PHP `:271`) so the AI sees the identical pre-turn state.
                val g = world.getGeneralById(generalId)
                val raw = g?.let { world.getNationById(it.nationId)?.meta?.get("turn_last_${it.officerLevel}") }
                @Suppress("UNCHECKED_CAST")
                val lastTurn = LastTurn.fromRaw(raw as? Map<String, Any?>)
                ai.chooseNationTurn(generalId, reserved, lastTurn)
            },
            beginGeneralTurn = { generalId -> ai.beginGeneralTurn(generalId) },
            reservedActionOf = { generalId -> reservedTurnRepository.readReserved(generalId, 0) },
        )

        // 월간 world-event 디스패치 컨텍스트 팩토리 — UpdateCitySupply 등 cast-ctx leaf에 WorldActionContext를
        // 공급하고 RaiseDisaster/특기/유산 등 env-read leaf의 world-view/hiddenSeed/startYear/cityConst/
        // eventStore 키를 심는다. 이게 없으면 월경계 정산이 크래시(cast)하거나 무음 no-op(env-read)한다.
        val mapName = state.meta["map"] as? String ?: "che"
        val worldContextFactory = WorldEventContextFactory.create(
            world = world,
            recorder = handler.recorder,
            pipeline = generalActionPipeline,
            hiddenSeed = hiddenSeed,
            startYear = startYear,
            mapName = mapName,
            eventStore = eventStore,
        )

        return TurnRunService(
            world = world,
            commandStream = commandStream,
            // commandBlockMs를 명시적 finite 값으로 전달 — 미전달 시 기본 0이며, Spring Data Redis에서
            // block(Duration.ofMillis(0)) == XREAD BLOCK 0 == 무한 블록이 되어 빈 스트림에서 영원히
            // 대기하다 Lettuce 기본 60s command-timeout 초과 → RedisCommandTimeoutException → runTick이
            // 턴 진행 전에 abort → 턴 영구 동결(prod 회귀). 250ms = 드레인 후 즉시 반환(XADD poke 시 즉시
            // 깨어남), idle 페이싱은 TurnDaemonRunner의 Thread.sleep(idlePollMs)가 담당. E2E 검증값.
            commandBlockMs = 250L,
            lifecycle = lifecycle,
            handler = handler,
            flushExecutor = flushExecutor,
            realtimePublisher = realtimePublisher,
            pipeline = monthlyPipeline,
            eventDispatcher = eventDispatcher,
            worldContextFactory = worldContextFactory,
            auctionRepository = auctionRepository,
            auctionBidRepository = auctionBidRepository,
            boardPostRepository = boardPostRepository,
            votePollRepository = votePollRepository,
        )
    }
}

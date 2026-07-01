package opensamguk.engine.config

import opensamguk.common.constants.EffectiveGameConst
import opensamguk.common.constants.GameConst
import opensamguk.common.constants.GameUnitConst
import opensamguk.common.rng.RandUtil
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.run.MonthlyPostUpdateHook
import opensamguk.engine.run.TurnRunService
import opensamguk.engine.turn.AiTurnAdapter
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LifecycleEnv
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.ProcessNationCommand
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.RulerSuccessionHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import opensamguk.infra.read.BoardPostRepository
import opensamguk.infra.read.DiplomacyLetterRepository
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
import opensamguk.logic.tick.CheckStatisticCalculator
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

    /**
     * diplomacy_letter 조회용 JDBC read seam (W5d 외교 서신). send의 prev 체인 / rollback / destroy 가드의
     * read 경로(findLetter / countNewerLetters). JDBC read 전용 — write 경로는 [JdbcFlushExecutor]
     * step-8f 뿐(one-daemon-write 규칙).
     */
    @Bean
    fun diplomacyLetterRepository(jdbc: NamedParameterJdbcTemplate): DiplomacyLetterRepository =
        DiplomacyLetterRepository(jdbc)

    /**
     * 연락처/메시지 read seam (W6a 메시지). 데몬 [MessageHandler]의 삭제 게이트(getMessageByID)의 read 경로.
     * JDBC read 전용 — write 경로(message INSERT/UPDATE, general.newmsg)는 [JdbcFlushExecutor]뿐(one-daemon-write).
     */
    @Bean
    fun contactReader(jdbc: NamedParameterJdbcTemplate): opensamguk.infra.read.ContactReader =
        opensamguk.infra.read.ContactReader(jdbc)

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
        diplomacyLetterRepository: DiplomacyLetterRepository,
        contactReader: opensamguk.infra.read.ContactReader,
        gameKvRepository: opensamguk.infra.read.GameKvRepository,
        bettingRepository: opensamguk.infra.read.BettingRepository,
        inheritanceRepository: opensamguk.infra.read.InheritanceRepository,
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

        // ONE recorder shared by the handler + the ruler-succession hook (single dirty source, P2 Risk #4).
        // Built here (not inside RTH) so the succession handler diffs into the SAME recorder the reserved
        // turns + nation pass use — the nextRuler hook (군주 사망 후계/멸망) writes heir-promote / 재야-reset /
        // markNationDeleted deltas that must flush alongside the rest of the tick.
        val recorder = ChangeRecorder()
        val rulerSuccession = RulerSuccessionHandler(world, recorder, hiddenSeed)

        // The general-pass AI interpose (R-SEAM §2): the handler gates this hook on isAiControlled
        // internally, so a human general runs its reserved command verbatim and an NPC runs the AI choice.
        val handler = ReservedTurnHandler(
            world = world,
            registry = registry,
            hiddenSeed = hiddenSeed,
            startYear = startYear,
            scenario = scenario,
            // 외교 제의 서신 validUntil(= date + max(30, turnterm*3)분) 공식이 읽는 per-game turnterm.
            turnTerm = turnTerm,
            // 군주(officer_level==12) 사망 시 후계 선정/승계 또는 국가 멸망 (func.php:1807 nextRuler).
            nextRuler = { generalId, env -> rulerSuccession.succeed(generalId, env) },
            recorder = recorder,
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
            checkStatistic = CheckStatistic {
                val generals = world.listGenerals().map { g ->
                    val statisticMeta = g.meta +
                        mapOf(
                            "personal" to (g.role.personality ?: g.meta["personal"] ?: g.meta["personal_code"] ?: "None"),
                            "special" to (g.meta["special"] ?: g.meta["special_code"] ?: "None"),
                            "special2" to (g.role.specialWar ?: g.meta["special2"] ?: g.meta["special2_code"] ?: "None"),
                        ) +
                        if (g.recentWarTime != null) mapOf("recent_war" to g.recentWarTime.toString()) else emptyMap()
                    PerTurnOverlay.toLogicGeneral(g).copy(meta = statisticMeta)
                }
                val nations = world.listNations().map { PerTurnOverlay.toLogicNation(it) }
                val cities = world.listCities().map { PerTurnOverlay.toLogicCity(it) }
                val row = CheckStatisticCalculator.compute(
                    year = state.currentYear,
                    month = state.currentMonth,
                    generals = generals,
                    nations = nations,
                    cities = cities,
                    nationTypeNameOf = { type -> type.removePrefix("che_") },
                    personalityNameOf = { p -> GameConst.personalityNameOf(p.toString()) },
                    specialDomesticNameOf = { s -> opensamguk.logic.world.SpecialityHelper.domesticName(s) },
                    specialWarNameOf = { s -> opensamguk.logic.world.SpecialityHelper.warName(s) },
                    crewtypeShortNameOf = { c -> GameUnitConst.byId(c)?.name ?: "$c" },
                )
                // aux는 Map<String, Any?> — kotlinx Json.encodeToString은 런타임에
                // SerializationException("Serializer for class 'Any'")을 던져 연경계(새 달 == 1월)
                // tick을 영구 동결시킨다(2026-06-09 prod s1/spep 회귀). MetaJson 경로만 허용.
                handler.recorder.recordStatisticInsert(StatisticInsertColumns.from(row))
            },
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
            // 라이브 LifecycleEnv — per-general 꼬리(killturn 감소/리셋 + updateTurnTime)가 PHP `$gameStor` 값으로
            // 동작하도록 매 틱 LIVE world state에서 산정한다(이전 stub은 baselineKillturn=0/turnTerm=1 하드코딩이라
            // killturn 리셋·turntime advance가 발산했다).
            //  - turnTerm = tickSeconds/60 (PHP `$gameStor->turnterm`; TurnRunService.kt:174와 동일 산식).
            //  - baselineKillturn = EffectiveGameConst.killturn(turnterm, npcmode) — PHP `$gameStor->killturn`의
            //    원본 산식(ResetHelper.php:264-265: killturn = 4800/turnterm, npcmode==1 → intdiv(,3)). opensamguk
            //    seed는 npcmode를 world_state에 영속하지 않으므로(빼섭 1030 외 표준 시나리오 npcmode=0) 0으로 고정 —
            //    fabricate가 아니라 표준-모드 PHP 기본값이며, npcmode==1(빼섭) 변형이 도입되면 meta 키로 흘려야 한다.
            //  - isunited = state.meta["isunited"] (TurnRunService.kt:197과 동일 convention; 미존재 시 0).
            //  - year/month = state.current{Year,Month}. autorunMode는 per-general 값이라 여기서는 false 기본 —
            //    AI 교체 시 ReservedTurnHandler.handle이 HandledTurn.autorunMode로 노출하고, runTick은 그 신호로
            //    killturn 분기를 결정한다(env.autorunMode는 사용 안 함; LifecycleEnv.autorunMode는 호환용 기본값).
            lifecycleEnvOf = { state, date ->
                val turnTerm = state.tickSeconds / 60
                LifecycleEnv(
                    baselineKillturn = EffectiveGameConst.killturn(turnTerm, npcmode = 0),
                    year = state.currentYear,
                    month = state.currentMonth,
                    turnTerm = turnTerm,
                    isunited = state.meta["isunited"] as? Int ?: 0,
                    turnTimeHm = date,
                )
            },
            pullNationTurnOf = { nationId, officerLevel ->
                reservedTurnRepository.pullNationTurn(nationId, officerLevel)
            },
            pullGeneralTurnOf = { generalId ->
                reservedTurnRepository.pullGeneralTurn(generalId)
            },
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
            diplomacyLetterRepository = diplomacyLetterRepository,
            contactReader = contactReader,
            gameKvRepository = gameKvRepository,
            bettingRepository = bettingRepository,
            inheritanceRepository = inheritanceRepository,
        )
    }
}

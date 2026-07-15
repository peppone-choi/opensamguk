package opensamguk.engine.config

import opensamguk.common.constants.EffectiveGameConst
import opensamguk.common.constants.GameConst
import opensamguk.common.constants.GameUnitConst
import opensamguk.common.rng.RandUtil
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.run.MonthlyPreUpdateHook
import opensamguk.engine.run.MonthlyPostUpdateHook
import opensamguk.engine.run.TurnRunService
import opensamguk.engine.run.LiveRemainNationEnv
import opensamguk.engine.tournament.ProductionTournamentBettingPort
import opensamguk.engine.tournament.TournamentDaemon
import opensamguk.engine.turn.AiTurnAdapter
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.EngineGeneralActionPipelineBuilder
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
import opensamguk.infra.read.MessageRepository
import opensamguk.infra.read.VotePollRepository
import opensamguk.infra.read.SelectPoolRepository
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.nation.NationActionResolverRegistry
import opensamguk.logic.actions.nation.NationCommand
import opensamguk.logic.actions.nation.withNationAux
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.diplomacy.DiplomacyConst
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.util.numberFormat
import opensamguk.engine.world.WorldEventContextFactory
import opensamguk.logic.event.EventDispatcher
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.EventTarget
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import opensamguk.logic.tick.CheckStatistic
import opensamguk.logic.tick.MonthScopedRng
import opensamguk.logic.tick.MonthlyClock
import opensamguk.logic.tick.CheckStatisticCalculator
import opensamguk.logic.tick.MonthlyPipeline
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

    @Bean
    fun selectPoolRepository(jdbc: NamedParameterJdbcTemplate): SelectPoolRepository =
        SelectPoolRepository(jdbc)

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
        messageRepository: MessageRepository,
        contactReader: opensamguk.infra.read.ContactReader,
        gameKvRepository: opensamguk.infra.read.GameKvRepository,
        bettingRepository: opensamguk.infra.read.BettingRepository,
        inheritanceRepository: opensamguk.infra.read.InheritanceRepository,
        @Value("\${opensamguk.profile}") profile: String,
        selectPoolRepository: SelectPoolRepository,
    ): TurnRunService {
        installNationActionResolvers(generalActionPipeline)

        val state = world.getState()
        val hiddenSeed = state.meta["hiddenSeed"] as? String ?: ""
        val startYear = (state.meta["startYear"] as? Number)?.toInt() ?: state.currentYear
        val turnTerm = state.tickSeconds / 60

        val registry = CommandRegistry(generalActionPipeline)
        val pipelineBuilder = EngineGeneralActionPipelineBuilder(world, startYear)

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
            pipelineBuilder = pipelineBuilder,
            reservedCommandNameOf = { gid -> reservedTurnRepository.readReserved(gid, 0).actionCode },
        )

        // The scenario number selects the founding `secretlimit` (>= 1000 ⇒ 1, the live-server branch).
        // Prefer the seeded world meta (ScenarioSeedRunner), fall back to the SCENARIO_CODE env fence
        // (default scenario_1010 ⇒ 1010 ⇒ secretlimit 1), else 0.
        val scenario = (state.meta["scenario"] as? Number)?.toInt()
            ?: System.getenv("SCENARIO_CODE")?.removePrefix("scenario_")?.toIntOrNull()
            ?: 0

        var nextMessageId = messageRepository.findMaxId()
        var nextAuctionId = auctionRepository.findAll().mapNotNull { it.id }.maxOrNull() ?: 0

        // ONE recorder shared by the handler + the ruler-succession hook (single dirty source, P2 Risk #4).
        // Built here (not inside RTH) so the succession handler diffs into the SAME recorder the reserved
        // turns + nation pass use — the nextRuler hook (군주 사망 후계/멸망) writes heir-promote / 재야-reset /
        // markNationDeleted deltas that must flush alongside the rest of the tick.
        val recorder = ChangeRecorder(
            messageIdAllocator = { ++nextMessageId },
            auctionIdAllocator = { ++nextAuctionId },
            kvWriteObserver = world::applyKvDirtyFree,
            initialInheritancePoints = state.meta["inheritancePoints"] as? Map<*, *> ?: emptyMap<Any?, Any?>(),
        )
        eventStore.bindMutationSink(recorder::recordEventMutation)
        val rulerSuccession = RulerSuccessionHandler(world, recorder, hiddenSeed)

        val mapName = state.meta["map"] as? String ?: "che"
        val worldContextFactory = WorldEventContextFactory.create(
            world = world,
            recorder = recorder,
            pipeline = generalActionPipeline,
            hiddenSeed = hiddenSeed,
            startYear = startYear,
            mapName = mapName,
            eventStore = eventStore,
        )

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
            pipelineBuilder = pipelineBuilder,
            dynamicEventHandler = { target: EventTarget ->
                eventDispatcher.run(
                    target = target,
                    contextFactory = worldContextFactory,
                    envSupplier = {
                        val live = world.getState()
                        val eventEnv = LinkedHashMap(live.meta).apply {
                            this["year"] = live.currentYear
                            this["month"] = live.currentMonth
                            this["phase"] = live.currentPhase
                            this["startyear"] = startYear
                            this["startYear"] = startYear
                            this["develcost"] = EffectiveGameConst.develcost(live.currentYear, startYear)
                            this["develCost"] = EffectiveGameConst.develcost(live.currentYear, startYear)
                        }
                        LiveRemainNationEnv(eventEnv) { world.listNations().size }
                    },
                )
            },
        )

        // The nation pass writes through the SAME recorder the handler owns — the lone dirty source the
        // flush reads (P2 Risk #4). The handler exposes its internal ChangeRecorder via `.recorder`.
        val nationProcessor = ProcessNationCommand(
            world = world,
            recorder = handler.recorder,
            hiddenSeed = hiddenSeed,
            // Logic bridge: every ported NationCommand.resolve body runs live when no explicit
            // NationActionResolverRegistry leaf is registered (closes silent no-op gap).
            registry = registry,
            startYear = startYear,
            turnTerm = turnTerm,
            pipelineBuilder = pipelineBuilder,
        )

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
            preUpdateMonthly = MonthlyPreUpdateHook(world, handler.recorder, profile),
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
                    nationTypeNameOf = { type -> if (type == GameConst.neutralNationType || type == "None") "-" else type.removePrefix("che_") },
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
            postUpdateMonthly = MonthlyPostUpdateHook(
                world,
                handler.recorder,
                generalActionPipeline,
                auctionRepository = auctionRepository,
                auctionBidRepository = auctionBidRepository,
                eventDispatcher = eventDispatcher,
            ),
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
                ai.chooseNationTurn(generalId, reserved, lastTurn).also {
                    ai.drainNationPassDeltas(recorder)
                }
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
                ai.drainGeneralPassDeltas(recorder)
                reservedTurnRepository.pullGeneralTurn(generalId)
            },
            reservedActionOf = { generalId -> reservedTurnRepository.readReserved(generalId, 0) },
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
            selectPoolRepository = selectPoolRepository,
            tournamentDaemon = TournamentDaemon(
                gameKvRepository = gameKvRepository,
                bettingFactory = { liveWorld, liveRecorder ->
                    ProductionTournamentBettingPort(
                        world = liveWorld,
                        recorder = liveRecorder,
                        gameKvRepository = gameKvRepository,
                        bettingRepository = bettingRepository,
                        inheritanceRepository = inheritanceRepository,
                    )
                },
            ),
        )
    }

    /**
     * Live nation-pass resolvers. Logic-side `NationCommand.resolve(GeneralAction…)` is golden-path;
     * the daemon only runs codes registered here via [ProcessNationCommand].
     */
    private fun installNationActionResolvers(pipeline: GeneralActionPipeline) {
        NationActionResolverRegistry.register("che_선전포고") { ctx ->
            val me = ctx.nation.id
            val you = (ctx.args["destNationID"] as? Number)?.toInt() ?: return@register
            val destNation = ctx.destNation ?: return@register
            if (destNation.id != you) return@register

            ctx.setDiplomacyBidirectional(me, you, DiplomacyState.DECLARATION, DiplomacyConst.DEFAULT_DECLARE_WAR_TERM)
            val actorName = ctx.generalName.ifEmpty { ctx.nation.name }
            val destName = ctx.destName.ifEmpty { destNation.name }
            val josaEul = JosaUtil.pick(destName, "을", "를")
            val josaI = JosaUtil.pick(actorName, "이", "가")
            ctx.addActionLog("<D><b>$destName</b></>$josaEul 선전포고했습니다.")
            ctx.addGlobalActionLog("<D><b>$actorName</b></>$josaI <D><b>$destName</b></>에 선전포고했습니다.")
            ctx.addGlobalHistoryLog(
                "<D><b>$actorName</b></>가 <D><b>$destName</b></>에게 ${ctx.year}년 ${ctx.month}월에 선전포고했습니다.",
            )
        }
        NationActionResolverRegistry.register("che_불가침수락") { ctx ->
            val me = ctx.nation.id
            val you = (ctx.args["destNationID"] as? Number)?.toInt() ?: return@register
            val destNation = ctx.destNation ?: return@register
            if (destNation.id != you) return@register
            val year = (ctx.args["year"] as? Number)?.toInt() ?: return@register
            val month = (ctx.args["month"] as? Number)?.toInt() ?: return@register

            val recvAssist = destNation.meta["recv_assist"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val respAssist = LinkedHashMap<String, Any?>()
            (destNation.meta["resp_assist"] as? Map<*, *>)?.forEach { (key, value) ->
                if (key is String) respAssist[key] = value
            }
            val recvForMe = recvAssist["n$me"] as? List<*>
            respAssist["n$me"] = listOf(me, recvForMe?.getOrNull(1) ?: 0)
            ctx.recordKv("nation_env", you.toString(), "resp_assist", respAssist)

            val currentMonth = ctx.year * 12 + ctx.month - 1
            val reqMonth = year * 12 + month
            ctx.setDiplomacyBidirectional(me, you, DiplomacyState.NON_AGGRESSION, reqMonth - currentMonth)

            val destName = ctx.destName.ifEmpty { destNation.name }
            val josaWa = JosaUtil.pick(destName, "와")
            ctx.addActionLog(
                "<D><b>$destName</b></>$josaWa <C>$year</>년 <C>$month</>월까지 불가침에 성공했습니다.",
            )
        }
        NationActionResolverRegistry.register("che_종전수락") { ctx ->
            val me = ctx.nation.id
            val you = (ctx.args["destNationID"] as? Number)?.toInt() ?: return@register
            val destNation = ctx.destNation ?: return@register
            if (destNation.id != you) return@register

            ctx.setDiplomacyBidirectional(me, you, DiplomacyState.TRADE, 0)
        }
        NationActionResolverRegistry.register("che_불가침파기수락") { ctx ->
            val me = ctx.nation.id
            val you = (ctx.args["destNationID"] as? Number)?.toInt() ?: return@register
            val destNation = ctx.destNation ?: return@register
            if (destNation.id != you) return@register

            ctx.setDiplomacyBidirectional(me, you, DiplomacyState.TRADE, 0)
        }

        // 급습 — che_급습.php:157-194 (action log, exp/ded+5, strategic_cmd_limit, term-3).
        // 타 장수 broadcast PLAIN 은 엔진 스코프(다른 ActionLogger) — actor 골든 broadcastLines=[].
        NationActionResolverRegistry.register("che_급습") { ctx ->
            val me = ctx.nation.id
            val you = (ctx.args["destNationID"] as? Number)?.toInt() ?: return@register
            val destNation = ctx.destNation ?: return@register
            if (destNation.id != you) return@register
            val pre = ctx.diplomacyOf(me, you) ?: return@register
            val commandName = "급습"
            val generalName = ctx.generalName.ifEmpty { "장수" }
            val destName = ctx.destName.ifEmpty { destNation.name }
            val josaUl = JosaUtil.pick(commandName, "을")
            val josaYi = JosaUtil.pick(generalName, "이")

            ctx.addActionLog("$commandName 발동! <1>${ctx.date}</>")
            applyExpDed(ctx, pipeline, magnitude = 5.0)
            ctx.nation = ctx.nation.copy(meta = ctx.nation.meta + ("strategic_cmd_limit" to 9))
            ctx.setDiplomacyBidirectional(me, you, pre.state, pre.term - 3)
            ctx.addGeneralHistoryLog("<D><b>$destName</b></>에 <M>$commandName</>$josaUl 발동")
            ctx.addNationalHistoryLog(
                "<Y>$generalName</>$josaYi <D><b>$destName</b></>에 <M>$commandName</>$josaUl 발동",
            )
            ctx.addDestNationalHistoryLog(
                "<D><b>${ctx.nation.name}</b></>의 <Y>$generalName</>$josaYi 아국에 <M>$commandName</>$josaUl 발동",
            )
        }
        // 이호경식 — che_이호경식.php:152-190.
        NationActionResolverRegistry.register("che_이호경식") { ctx ->
            val me = ctx.nation.id
            val you = (ctx.args["destNationID"] as? Number)?.toInt() ?: return@register
            val destNation = ctx.destNation ?: return@register
            if (destNation.id != you) return@register
            val pre = ctx.diplomacyOf(me, you) ?: return@register
            val commandName = "이호경식"
            val generalName = ctx.generalName.ifEmpty { "장수" }
            val destName = ctx.destName.ifEmpty { destNation.name }
            val josaUl = JosaUtil.pick(commandName, "을")
            val josaYi = JosaUtil.pick(generalName, "이")

            ctx.addActionLog("$commandName 발동! <1>${ctx.date}</>")
            applyExpDed(ctx, pipeline, magnitude = 5.0)
            val newTerm = if (pre.state == DiplomacyState.WAR) 3 else pre.term + 3
            ctx.setDiplomacyBidirectional(me, you, DiplomacyState.DECLARATION, newTerm)
            ctx.nation = ctx.nation.copy(meta = ctx.nation.meta + ("strategic_cmd_limit" to 9))
            ctx.addGeneralHistoryLog("<D><b>$destName</b></>에 <M>$commandName</>$josaUl 발동")
            ctx.addNationalHistoryLog(
                "<Y>$generalName</>$josaYi <D><b>$destName</b></>에 <M>$commandName</>$josaUl 발동",
            )
            ctx.addDestNationalHistoryLog(
                "<D><b>${ctx.nation.name}</b></>의 <Y>$generalName</>$josaYi 아국에 <M>$commandName</>$josaUl 발동",
            )
        }

        // 물자원조 — che_물자원조.php run (deterministic): clamp amounts, transfer, surlimit+12, exp/ded+5.
        NationActionResolverRegistry.register("che_물자원조") { ctx ->
            val you = (ctx.args["destNationID"] as? Number)?.toInt() ?: return@register
            val destNation = ctx.destNation ?: return@register
            if (destNation.id != you) return@register
            @Suppress("UNCHECKED_CAST")
            val amountList = (ctx.args["amountList"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
            var goldAmount = amountList.getOrElse(0) { 0 }
            var riceAmount = amountList.getOrElse(1) { 0 }
            val nation = ctx.nation
            goldAmount = goldAmount.coerceIn(0, (nation.gold - GameConst.basegold).coerceAtLeast(0))
            riceAmount = riceAmount.coerceIn(0, (nation.rice - GameConst.baserice).coerceAtLeast(0))
            val destName = ctx.destName.ifEmpty { destNation.name }
            val josaRo = JosaUtil.pick(destName, "로")
            val goldText = numberFormat(goldAmount)
            val riceText = numberFormat(riceAmount)
            ctx.addActionLog("<D><b>$destName</b></>$josaRo 금<C>$goldText</> 쌀<C>$riceText</>을 지원했습니다.")
            ctx.addActionLog("<D><b>$destName</b></>$josaRo 물자를 지원합니다. <1>${ctx.date}</>")
            val nextSurlimit = metaInt(nation.meta, "surlimit") + 12
            ctx.nation = nation.copy(
                gold = nation.gold - goldAmount,
                rice = nation.rice - riceAmount,
                meta = nation.meta + ("surlimit" to nextSurlimit),
            )
            ctx.destNation = destNation.copy(
                gold = destNation.gold + goldAmount,
                rice = destNation.rice + riceAmount,
            )
            applyExpDed(ctx, pipeline, magnitude = 5.0)
        }

        // 피장파장 — che_피장파장.php:201-242 logs + exp/ded + nation_env delay KV (delayCnt=60).
        NationActionResolverRegistry.register("che_피장파장") { ctx ->
            val you = (ctx.args["destNationID"] as? Number)?.toInt() ?: return@register
            val destNation = ctx.destNation ?: return@register
            if (destNation.id != you) return@register
            val commandType = ctx.args["commandType"] as? String ?: return@register
            val targetName = commandType.removePrefix("che_")
            val commandName = "피장파장"
            ctx.addActionLog("<G><b>$targetName</b></> 전략의 $commandName 발동! <1>${ctx.date}</>")
            applyExpDed(ctx, pipeline, magnitude = 10.0) // 5*(1+1)
            val yearMonth = NationCommand.joinYearMonth(ctx.year, ctx.month)
            val genCount = metaInt(ctx.nation.meta, "gennum").coerceAtLeast(GameConst.initialNationGenLimit)
            // PHP: round(sqrt(genCount*2)*10) then valueFit min = round(delayCnt*1.2)
            var targetPost = phpRound(kotlin.math.sqrt(genCount * 2.0) * 10)
            val minDelay = phpRound(60 * 1.2) // delayCnt*1.2
            if (targetPost < minDelay) targetPost = minDelay
            val nextKey = "next_execute_$targetName"
            ctx.recordKv("nation_env", ctx.nation.id.toString(), nextKey, yearMonth + targetPost)
            // dest delay = max(existing, yearMonth) + 60
            val destExisting = (destNation.meta[nextKey] as? Number)?.toInt() ?: 0
            val destDelay = maxOf(destExisting, yearMonth) + 60
            ctx.recordKv("nation_env", you.toString(), nextKey, destDelay)
            val generalName = ctx.generalName.ifEmpty { "장수" }
            val destName = ctx.destName.ifEmpty { destNation.name }
            val josaUl = JosaUtil.pick(commandName, "을")
            val josaYi = JosaUtil.pick(generalName, "이")
            ctx.addGeneralHistoryLog(
                "<D><b>$destName</b></>에 <G><b>$targetName</b></> <M>$commandName</>$josaUl 발동",
            )
            ctx.addNationalHistoryLog(
                "<Y>$generalName</>$josaYi <D><b>$destName</b></>에 <G><b>$targetName</b></> <M>$commandName</>$josaUl 발동",
            )
        }

        // event_*연구 family — gold/rice/aux unlock + exp/ded + action log (PHP deterministic run).
        // costs/preReq mirror the logic leaf classes (event_*.php getCost / getPreReqTurn).
        registerEventResearch(pipeline, "event_상병연구", "상병 연구", "can_상병사용", 100_000, 100_000, preReqTurn = 23)
        registerEventResearch(pipeline, "event_극병연구", "극병 연구", "can_극병사용", 100_000, 100_000, preReqTurn = 23)
        registerEventResearch(pipeline, "event_원융노병연구", "원융노병 연구", "can_원융노병사용", 100_000, 100_000, preReqTurn = 23)
        registerEventResearch(pipeline, "event_화륜차연구", "화륜차 연구", "can_화륜차사용", 100_000, 100_000, preReqTurn = 23)
        registerEventResearch(pipeline, "event_무희연구", "무희 연구", "can_무희사용", 100_000, 100_000, preReqTurn = 23)
        registerEventResearch(pipeline, "event_산저병연구", "산저병 연구", "can_산저병사용", 50_000, 50_000, preReqTurn = 11)
        registerEventResearch(pipeline, "event_화시병연구", "화시병 연구", "can_화시병사용", 50_000, 50_000, preReqTurn = 11)
        registerEventResearch(pipeline, "event_음귀병연구", "음귀병 연구", "can_음귀병사용", 50_000, 50_000, preReqTurn = 11)
        registerEventResearch(pipeline, "event_대검병연구", "대검병 연구", "can_대검병사용", 50_000, 50_000, preReqTurn = 11)
    }

    private fun registerEventResearch(
        pipeline: GeneralActionPipeline,
        code: String,
        actionName: String,
        auxKey: String,
        reqGold: Int,
        reqRice: Int,
        preReqTurn: Int,
    ) {
        NationActionResolverRegistry.register(code) { ctx ->
            val nation = ctx.nation
            val mag = 5.0 * (preReqTurn + 1)
            applyExpDed(ctx, pipeline, magnitude = mag)
            ctx.nation = nation.copy(
                gold = nation.gold - reqGold,
                rice = nation.rice - reqRice,
                meta = withNationAux(nation.meta, auxKey to 1),
            )
            ctx.addActionLog("<M>$actionName</> 완료")
        }
    }

    private fun applyExpDed(
        ctx: opensamguk.logic.actions.nation.NationActionResolveContext,
        pipeline: GeneralActionPipeline,
        magnitude: Double,
    ) {
        val general = ctx.general ?: return
        val actorPipeline = ctx.pipeline ?: pipeline
        val expRes = addExperience(general, magnitude, actorPipeline)
        val dedRes = addDedication(expRes.general, magnitude, actorPipeline)
        ctx.general = dedRes.general
        expRes.plainLog?.let { ctx.addActionLog(it) }
        dedRes.plainLog?.let { ctx.addActionLog(it) }
    }
}

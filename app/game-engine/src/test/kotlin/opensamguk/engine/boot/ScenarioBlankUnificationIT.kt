package opensamguk.engine.boot

import opensamguk.common.constants.GameConst
import opensamguk.common.wire.MakeGeneralOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.config.EngineEventConfig
import opensamguk.engine.intake.MakeGeneralHandler
import opensamguk.engine.intake.PersonnelHandler
import opensamguk.engine.run.MonthlyPostUpdateHook
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.ProcessNationCommand
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.world.WorldEventContextFactory
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.EventCondition
import opensamguk.logic.event.EventDispatcher
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.WorldActions
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.tick.CheckStatistic
import opensamguk.logic.tick.GameDate
import opensamguk.logic.tick.MonthScopedRng
import opensamguk.logic.tick.MonthlyClock
import opensamguk.logic.tick.MonthlyPipeline
import opensamguk.logic.tick.PreUpdateMonthly
import opensamguk.logic.util.phpRound
import opensamguk.logic.war.searchDistanceListToDest
import opensamguk.logic.world.CityConstRegistry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScenarioBlankUnificationIT {

    private data class PlayerSpec(
        val role: String,
        val leadership: Int,
        val strength: Int,
        val intel: Int,
        val politics: Int,
        val charm: Int,
    )

    private data class CreatedPlayer(val spec: PlayerSpec, val generalId: Int, val userId: Int)

    private data class GameMonth(val year: Int, val month: Int)

    private data class BotOrder(val generalId: Int, val actionCode: String, val targetCityId: Int)

    private data class MoveCandidate(val order: BotOrder, val distance: Int, val gold: Int)

    private class ScenarioMonthlyRunner(
        private val world: InMemoryTurnWorld,
        private val recorder: ChangeRecorder,
        pipeline: GeneralActionPipeline,
        hiddenSeed: String,
        private val startYear: Int,
        private val eventStore: EventStore,
    ) {
        private val eventDispatcher = EventDispatcher(eventStore, WorldActions.register(EventActionFactory()))
        private val worldContextFactory = WorldEventContextFactory.create(
            world = world,
            recorder = recorder,
            pipeline = pipeline,
            hiddenSeed = hiddenSeed,
            startYear = startYear,
            mapName = world.getState().meta["map"] as? String ?: "che",
            eventStore = eventStore,
        )
        private var nextDate = GameDate(startYear, 1, 1)
        private val monthlyPipeline = MonthlyPipeline(
            monthlyRngFactory = { year, month -> MonthScopedRng.forMonth(hiddenSeed, year, month) },
            clock = MonthlyClock { _, _ -> nextDate },
            preUpdateMonthly = PreUpdateMonthly { true },
            checkStatistic = CheckStatistic { },
            postUpdateMonthly = MonthlyPostUpdateHook(world, recorder, pipeline),
        )

        init {
            eventStore.bindMutationSink(recorder::recordEventMutation)
        }

        fun run(date: GameMonth) {
            nextDate = GameDate(date.year, date.month, 1)
            world.setCurrentDate(date.year, date.month, 1)
            val oldYear = if (date.month == 1) date.year - 1 else date.year
            val oldMonth = if (date.month == 1) 12 else date.month - 1
            monthlyPipeline.runMonth(
                nextTurn = Instant.EPOCH,
                startYear = startYear,
                startTime = Instant.EPOCH,
                turnTerm = 60,
                oldYear = oldYear,
                oldMonth = oldMonth,
                oldPhase = 1,
                dispatcher = { target, env ->
                    val supplier = {
                        mutableMapOf<String, Any?>(
                            "year" to env.year,
                            "month" to env.month,
                            "phase" to env.phase,
                            "currentEventID" to env.currentEventID,
                            EventCondition.REMAIN_NATION_COUNT_KEY to world.listNations().size,
                        )
                    }
                    eventDispatcher.run(target, contextFactory = worldContextFactory, envSupplier = supplier)
                },
            )
        }
    }

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var named: NamedParameterJdbcTemplate
    private lateinit var dataSource: DataSource
    private var dockerAvailable = false

    @BeforeAll
    fun setUpClass() {
        dockerAvailable = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        if (!dockerAvailable) return

        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        dataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        jdbc = JdbcTemplate(dataSource)
        named = NamedParameterJdbcTemplate(dataSource)
    }

    @AfterAll
    fun tearDownClass() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `scenario_0 player setup reaches production unification tail and survives reload`() {
        assumeTrue(dockerAvailable, "Docker unavailable - scenario blank unification IT skipped")

        val bootstrap = SeedBootstrap("scenario_0")
        assertTrue(bootstrap.ensureSeeded(jdbc))
        val loader = WorldSnapshotLoader(jdbc, bootstrap)
        var world = InMemoryTurnWorld(loader.buildSnapshot())
        var recorder = recorderFor(world)
        val roles = roleSpecs()
        val startYear = (world.getState().meta["startYear"] as Number).toInt()
        val hiddenSeed = world.getState().meta["hiddenSeed"] as String
        val pipeline = GeneralActionPipeline()
        val registry = CommandRegistry(pipeline)
        val players = createPlayers(world, recorder, roles)
        val groups = players.chunked(roles.size)
        val turns = ReservedTurnHandler(
            world,
            registry,
            hiddenSeed,
            startYear,
            scenario = 0,
            recorder = recorder,
        )

        foundPlayerNations(world, turns, groups, startYear)
        appointDiplomats(world, recorder, groups)
        groups.flatten().forEach { runRoleCommand(turns, it, startYear) }

        assertEquals(60, world.listGenerals().count { it.nationId > 0 })
        assertEquals(6, world.listNations().count { it.level == 1 })
        assertEquals(6, world.listCities().count { it.nationId > 0 })
        flush(world, recorder)

        world = InMemoryTurnWorld(loader.buildSnapshot())
        recorder = recorderFor(world)
        assertEquals(60, world.listGenerals().count { it.nationId > 0 })
        assertEquals(6, world.listNations().count { it.level == 1 })
        assertPlayerOwnership(world, players)

        val winnerNationId = world.listNations().filter { it.level > 0 }.minOf { it.id }
        forceAllCityOwnershipForTailGate(world, recorder, winnerNationId)
        val eventStore = EventStore()
        val eventDispatcher = EventDispatcher(eventStore, WorldActions.register(EventActionFactory()))
        MonthlyPostUpdateHook(
            world,
            recorder,
            pipeline,
            eventDispatcher = eventDispatcher,
        ).run(MonthScopedRng.forMonth(hiddenSeed, startYear, 3))

        assertEquals(2, (world.getState().meta["isunited"] as Number).toInt())
        flush(world, recorder)

        assertEquals(2, jdbc.queryForObject("SELECT isunited FROM world_state ORDER BY id LIMIT 1", Int::class.java))
        assertEquals(winnerNationId, jdbc.queryForObject("SELECT winner_nation FROM ng_games WHERE server_id = ?", Int::class.java, world.archiveServerId()))
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM statistic", Int::class.java)!! >= 1)
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM yearbook_history", Int::class.java)!! >= 1)
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM ng_old_nations WHERE nation IN (0, ?)", Int::class.java, winnerNationId)!! >= 2)
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM emperior WHERE server_id = ?", Int::class.java, world.archiveServerId())!! >= 1)
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM inheritance_result", Int::class.java)!! >= 1)
        assertTrue(jdbc.queryForObject("""SELECT count(*) FROM game_kv WHERE "table" = 'inheritance' AND key = 'previous'""", Int::class.java)!! >= 1)
        assertTrue(
            jdbc.queryForObject(
                "SELECT count(*) FROM log_entry WHERE text LIKE '%전토를 통일%'",
                Int::class.java,
            )!! >= 1,
        )

        world = InMemoryTurnWorld(loader.buildSnapshot())
        assertEquals(2, (world.getState().meta["isunited"] as Number).toInt())
        assertPlayerOwnership(world, players)
    }

    private fun forceAllCityOwnershipForTailGate(world: InMemoryTurnWorld, recorder: ChangeRecorder, winnerNationId: Int) {
        for (nation in world.listNations()) {
            if (nation.id == winnerNationId || nation.level == 0) continue
            val post = nation.copy(level = 0)
            recorder.diffNation(PerTurnOverlay.toLogicNation(nation), PerTurnOverlay.toLogicNation(post))
            world.applyNationDirtyFree(post)
        }
        for (city in world.listCities()) {
            if (city.nationId == winnerNationId) continue
            val post = city.copy(nationId = winnerNationId)
            recorder.diffCity(PerTurnOverlay.toLogicCity(city), PerTurnOverlay.toLogicCity(post))
            world.applyCityDirtyFree(post)
        }
    }

    private fun createPlayers(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        roles: List<PlayerSpec>,
    ): List<CreatedPlayer> {
        val makeGeneral = MakeGeneralHandler(
            world,
            recorder,
            nowProvider = { Instant.parse("0180-01-01T00:00:00Z") },
        )
        return (0 until NATION_COUNT).flatMap { nationIndex ->
            roles.mapIndexed { roleIndex, spec ->
                val idx = nationIndex * roles.size + roleIndex + 1
                val userId = 20_000 + idx
                val result = makeGeneral.handle(
                    TurnDaemonCommand.MakeGeneral(
                        userId = userId,
                        name = "${spec.role}%02d".format(idx),
                        leadership = spec.leadership,
                        strength = spec.strength,
                        intel = spec.intel,
                        politics = spec.politics,
                        charm = spec.charm,
                    ),
                )
                CreatedPlayer(
                    spec,
                    assertIs<MakeGeneralOk>(result, "idx=$idx role=${spec.role} result=$result").generalId,
                    userId,
                )
            }
        }
    }

    private fun foundPlayerNations(
        world: InMemoryTurnWorld,
        turns: ReservedTurnHandler,
        groups: List<List<CreatedPlayer>>,
        startYear: Int,
    ): List<Int> {
        val nationIds = groups.mapIndexed { index, group ->
            val leaderId = group.first().generalId
            assertAllowed(turns.handle(leaderId, ReservedTurn("che_거병", ""), startYear, 1, "00:00"))
            val nationId = world.getGeneralById(leaderId)!!.nationId
            group.drop(1).forEach { follower ->
                assertAllowed(
                    turns.handle(
                        follower.generalId,
                        ReservedTurn("che_임관", """{"destNationID":$nationId}"""),
                        startYear,
                        1,
                        "00:00",
                    ),
                )
            }
            assertEquals(index + 1, nationId)
            nationId
        }

        world.setCurrentDate(startYear, 2, 1)
        nationIds.forEachIndexed { index, nationId ->
            assertAllowed(
                turns.handle(
                    groups[index].first().generalId,
                    ReservedTurn(
                        "che_건국",
                        """{"nationName":"자동국${index + 1}","nationType":"che_중립","colorType":$index}""",
                    ),
                    startYear,
                    2,
                    "00:00",
                ),
            )
            assertEquals(1, world.getNationById(nationId)!!.level)
        }
        return nationIds
    }

    private fun appointDiplomats(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        groups: List<List<CreatedPlayer>>,
    ) {
        val personnel = PersonnelHandler(world, recorder)
        for (group in groups) {
            val leaderId = group.first().generalId
            val diplomatId = group.single { it.spec.role == "외교" }.generalId
            val result = personnel.handleAppoint(
                TurnDaemonCommand.Appoint(
                    generalId = leaderId,
                    destGeneralId = diplomatId,
                    destCityId = 0,
                    officerLevel = 11,
                ),
            )
            assertTrue(result.ok, "nation leader $leaderId failed to appoint diplomat $diplomatId: ${result.reason}")
            assertEquals(11, world.getGeneralById(diplomatId)!!.officerLevel)
        }
    }

    private fun assertPlayerOwnership(world: InMemoryTurnWorld, players: List<CreatedPlayer>) {
        for (player in players) {
            assertEquals(player.userId.toString(), world.getGeneralById(player.generalId)?.userId)
            assertEquals(player.userId.toLong(), world.getAccessLog(player.generalId)?.userId)
        }
        assertEquals(60, world.listAccessLogs().mapNotNull { it.userId }.toSet().size)
    }

    private fun runRoleCommand(turns: ReservedTurnHandler, player: CreatedPlayer, year: Int) {
        when (player.spec.role) {
            "군주" -> assertAllowed(turns.handle(player.generalId, ReservedTurn("che_상업투자", ""), year, 2, "00:00"))
            "내정농지" -> assertAllowed(turns.handle(player.generalId, ReservedTurn("che_농지개간", ""), year, 2, "00:00"))
            "내정상업" -> assertAllowed(turns.handle(player.generalId, ReservedTurn("che_상업투자", ""), year, 2, "00:00"))
            "내정치안" -> assertAllowed(turns.handle(player.generalId, ReservedTurn("che_치안강화", ""), year, 2, "00:00"))
            "내정성벽" -> assertAllowed(turns.handle(player.generalId, ReservedTurn("che_성벽보수", ""), year, 2, "00:00"))
            "내정수비" -> assertAllowed(turns.handle(player.generalId, ReservedTurn("che_수비강화", ""), year, 2, "00:00"))
            "내정기술" -> assertAllowed(turns.handle(player.generalId, ReservedTurn("che_기술연구", ""), year, 2, "00:00"))
            "전쟁" -> {
                assertAllowed(
                    turns.handle(
                        player.generalId,
                        ReservedTurn("che_징병", """{"crewType":1100,"amount":1000}"""),
                        year,
                        2,
                        "00:00",
                    ),
                )
                assertAllowed(turns.handle(player.generalId, ReservedTurn("che_훈련", ""), year, 2, "00:00"))
            }
            "징병" -> assertAllowed(
                turns.handle(
                    player.generalId,
                    ReservedTurn("che_징병", """{"crewType":1100,"amount":800}"""),
                    year,
                    2,
                    "00:00",
                ),
            )
            "외교" -> assertAllowed(turns.handle(player.generalId, ReservedTurn("che_농지개간", ""), year, 2, "00:00"))
        }
    }

    private fun ensureAttackersReady(
        turns: ReservedTurnHandler,
        world: InMemoryTurnWorld,
        nationId: Int,
        playerIds: List<Int>,
        date: GameMonth,
    ) {
        val rejectedSupplyCities = mutableSetOf<Int>()
        val supplyAttempts = ArrayDeque<String>()
        fun recordSupplyAttempt(value: String) {
            if (supplyAttempts.size == 20) supplyAttempts.removeFirst()
            supplyAttempts.addLast(value)
        }
        repeat(MAX_SUPPLY_STEPS) {
            val players = playerIds.mapNotNull(world::getGeneralById).filter { it.nationId == nationId }
            val targets = attackableTargets(world, nationId)
            if (targets.isEmpty() || chooseWarOrder(world, nationId, playerIds, date) != null) return

            val cityConst = CityConstRegistry.of(world.getState().meta["map"] as? String ?: "che")
            val targetIds = targets.mapTo(HashSet()) { it.id }
            val frontCityIds = world.listCities()
                .filter { city ->
                    city.nationId == nationId &&
                        cityConst.byId(city.id)?.path?.keys.orEmpty().any { it in targetIds }
                }
                .mapTo(HashSet()) { it.id }
            if (frontCityIds.isEmpty()) return

            val supplyCities = world.listCities()
                .filter { it.nationId == nationId && it.id !in rejectedSupplyCities }
                .sortedWith(
                    compareByDescending<opensamguk.engine.turn.City> { it.id in frontCityIds }
                        .thenByDescending { it.population },
                )
            val supplyCity = supplyCities.firstOrNull()
                ?: error("no usable supply city remains for nation $nationId")
            val stationed = players.filter { it.cityId == supplyCity.id }.sortedByDescending { it.gold }
            if (stationed.isNotEmpty()) {
                val failures = mutableListOf<String>()
                for (general in stationed) {
                    val beforeFunding = world.getGeneralById(general.id)!!
                    val requiredCampaignRice = phpRound(beforeFunding.crew / 100.0)
                    if (beforeFunding.crew >= MIN_ATTACK_CREW && beforeFunding.rice < requiredCampaignRice) {
                        val shortfall = requiredCampaignRice - beforeFunding.rice
                        val purchaseAmount = maxOf(100, ((shortfall + 99) / 100) * 100)
                            .coerceAtMost(GameConst.maxResourceActionAmount)
                        val trade = turns.handle(
                            general.id,
                            ReservedTurn("che_군량매매", """{"buyRice":true,"amount":$purchaseAmount}"""),
                            date.year,
                            date.month,
                            "00:00",
                        )
                        if (trade.fellBack) {
                            failures += "${general.id}:rice purchase failed: ${trade.denyReason}"
                            continue
                        }
                        val afterPurchase = world.getGeneralById(general.id)!!
                        assertTrue(
                            afterPurchase.rice > beforeFunding.rice,
                            "general=${general.id} automatic rice purchase must increase rice",
                        )
                        if (chooseWarOrder(world, nationId, playerIds, date) != null) return
                        failures += "${general.id}:rice purchase did not produce an executable war order"
                        continue
                    }
                    if (beforeFunding.gold < PREPARATION_GOLD) {
                        val neededGold = PREPARATION_GOLD - beforeFunding.gold
                        val saleAmount = maxOf(100, ((neededGold + 89) / 90) * 100)
                            .coerceAtMost(GameConst.maxResourceActionAmount)
                        if (beforeFunding.rice < PREPARATION_RICE + saleAmount) {
                            failures += "${general.id}:not enough rice to fund recruitment and preserve campaign supply"
                            continue
                        }
                        val trade = turns.handle(
                            general.id,
                            ReservedTurn("che_군량매매", """{"buyRice":false,"amount":$saleAmount}"""),
                            date.year,
                            date.month,
                            "00:00",
                        )
                        if (trade.fellBack) {
                            failures += "${general.id}:rice sale failed: ${trade.denyReason}"
                            continue
                        }
                        val afterFunding = world.getGeneralById(general.id)!!
                        assertTrue(
                            afterFunding.gold > beforeFunding.gold,
                            "general=${general.id} automatic rice sale must increase gold",
                        )
                    }
                    val outcome = turns.handle(
                        general.id,
                        ReservedTurn("che_징병", """{"crewType":1100,"amount":$RECRUIT_AMOUNT}"""),
                        date.year,
                        date.month,
                        "00:00",
                    )
                    if (!outcome.fellBack) {
                        val beforeTraining = world.getGeneralById(general.id)!!
                        val afterTraining = if (beforeTraining.train < GameConst.maxTrainByCommand) {
                            assertAllowed(
                                turns.handle(general.id, ReservedTurn("che_훈련", ""), date.year, date.month, "00:00"),
                                "general=${general.id} must train after recruitment",
                            )
                            world.getGeneralById(general.id)!!.also {
                                assertTrue(
                                    it.train > beforeTraining.train,
                                    "general=${general.id} training must increase after che_훈련",
                                )
                            }
                        } else {
                            beforeTraining
                        }
                        if (afterTraining.atmos < GameConst.maxAtmosByCommand) {
                            assertAllowed(
                                turns.handle(general.id, ReservedTurn("che_사기진작", ""), date.year, date.month, "00:00"),
                                "general=${general.id} must raise morale after training",
                            )
                            val afterMorale = world.getGeneralById(general.id)!!
                            assertTrue(
                                afterMorale.atmos > afterTraining.atmos,
                                "general=${general.id} morale must increase after che_사기진작",
                            )
                        }
                        if (chooseWarOrder(world, nationId, playerIds, date) != null) return
                        val afterPreparation = world.getGeneralById(general.id)!!
                        val cityConst = CityConstRegistry.of(world.getState().meta["map"] as? String ?: "che")
                        val targetIds = attackableTargets(world, nationId).mapTo(HashSet()) { it.id }
                        val adjacentTargets = cityConst.byId(afterPreparation.cityId)?.path?.keys.orEmpty()
                            .filter { it in targetIds }
                        failures += "${general.id}:recruited but no executable war order " +
                            "city=${afterPreparation.cityId} crew=${afterPreparation.crew} rice=${afterPreparation.rice} " +
                            "gold=${afterPreparation.gold} train=${afterPreparation.train} atmos=${afterPreparation.atmos} " +
                            "adjacentTargets=$adjacentTargets"
                        continue
                    }
                    failures += "${general.id}:${outcome.denyReason}"
                }
                rejectedSupplyCities += supplyCity.id
                recordSupplyAttempt("reject ${supplyCity.id}: $failures")
                if (supplyCities.size == 1) {
                    error("recruitment failed in the last supply city ${supplyCity.id}: $failures")
                }
                return@repeat
            }

            val ownedCities = world.listCities().filter { it.nationId == nationId }.associate { it.id to it.nationId }
            val startYear = (world.getState().meta["startYear"] as Number).toInt()
            val moveCost = (date.year - startYear + 10) * 2
            val move = players.filter { it.gold >= moveCost }.mapNotNull { general ->
                val route = searchDistanceListToDest(general.cityId, supplyCity.id, ownedCities)
                val nextCityId = route.entries.firstOrNull()?.value?.firstOrNull()?.first ?: return@mapNotNull null
                MoveCandidate(
                    BotOrder(general.id, "che_이동", nextCityId),
                    distance = route.keys.first(),
                    gold = general.gold,
                )
            }.minWithOrNull(compareBy<MoveCandidate> { it.distance }.thenByDescending { it.gold })
            if (move == null) {
                rejectedSupplyCities += supplyCity.id
                recordSupplyAttempt("unreachable ${supplyCity.id}")
                return@repeat
            }
            recordSupplyAttempt(
                "move ${move.order.generalId}:${world.getGeneralById(move.order.generalId)?.cityId}" +
                    "->${move.order.targetCityId} toward ${supplyCity.id} d=${move.distance}",
            )
            assertAllowed(
                turns.handle(
                    move.order.generalId,
                    ReservedTurn("che_이동", """{"destCityID":${move.order.targetCityId}}"""),
                    date.year,
                    date.month,
                    "00:00",
                ),
                "supply move general=${move.order.generalId} target=${move.order.targetCityId}",
            )
        }
        error(
            "supply move limit reached: rejected=${rejectedSupplyCities.size}, attempts=$supplyAttempts, " +
                "players=${playerIds.mapNotNull(world::getGeneralById).map { "${it.id}@${it.cityId}" }}",
        )
    }

    private fun chooseWarOrder(
        world: InMemoryTurnWorld,
        nationId: Int,
        playerIds: List<Int>,
        date: GameMonth,
    ): BotOrder? {
        val targets = attackableTargets(world, nationId)
        val attackers = playerIds.mapNotNull(world::getGeneralById)
            .filter {
                it.nationId == nationId &&
                    it.crew >= MIN_ATTACK_CREW &&
                    it.rice >= phpRound(it.crew / 100.0)
            }
            .sortedByDescending { it.crew }
        val startYear = (world.getState().meta["startYear"] as Number).toInt()
        val moveCost = (date.year - startYear + 10) * 2
        val cityConst = CityConstRegistry.of(world.getState().meta["map"] as? String ?: "che")

        for (attacker in attackers) {
            val neighborIds = cityConst.byId(attacker.cityId)?.path?.keys.orEmpty()
            val target = targets.firstOrNull { it.id in neighborIds } ?: continue
            return BotOrder(attacker.id, "che_출병", target.id)
        }

        val targetIds = targets.mapTo(HashSet()) { it.id }
        val frontCities = world.listCities()
            .filter { city ->
                city.nationId == nationId &&
                    cityConst.byId(city.id)?.path?.keys.orEmpty().any { it in targetIds }
            }
            .sortedBy { it.id }
        val ownedCities = world.listCities()
            .filter { it.nationId == nationId }
            .associate { it.id to it.nationId }
        val moveCandidates = mutableListOf<MoveCandidate>()
        for (attacker in attackers.filter { it.gold >= moveCost }) {
            for (front in frontCities) {
                if (front.id == attacker.cityId) continue
                val route = searchDistanceListToDest(attacker.cityId, front.id, ownedCities)
                val nextCityId = route.entries.firstOrNull()?.value?.firstOrNull()?.first ?: continue
                moveCandidates += MoveCandidate(
                    BotOrder(attacker.id, "che_이동", nextCityId),
                    distance = route.keys.first(),
                    gold = attacker.gold,
                )
            }
        }
        return moveCandidates.minWithOrNull(compareBy<MoveCandidate> { it.distance }.thenByDescending { it.gold })?.order
    }

    private fun attackableTargets(world: InMemoryTurnWorld, nationId: Int) =
        world.listCities()
            .filter { city ->
                city.nationId == 0 ||
                    (city.nationId != nationId && world.getDiplomacy(nationId, city.nationId)?.state == 0)
            }
            .sortedWith(compareBy({ it.nationId != 0 }, { it.id }))

    private fun describeBotState(
        world: InMemoryTurnWorld,
        nationId: Int,
        playerIds: List<Int>,
    ): String {
        val activeNationIds = world.listNations().filter { it.level > 0 }.map { it.id }.sorted()
        return "cities=${world.listCities().groupingBy { it.nationId }.eachCount()}, " +
            "targets=${attackableTargets(world, nationId).map { "${it.id}:${it.nationId}:p${it.population}:s${it.supplyState}" }}, " +
            "players=${playerIds.mapNotNull(world::getGeneralById).map { "${it.id}@${it.cityId}:c${it.crew}:g${it.gold}:r${it.rice}" }}, " +
            "diplomacy=${activeNationIds.filter { it != nationId }.associateWith { world.getDiplomacy(nationId, it)?.let { row -> "${row.state}/${row.term}" } }}"
    }

    private fun declareNeighborWar(
        world: InMemoryTurnWorld,
        nationTurns: ProcessNationCommand,
        nationId: Int,
        diplomatId: Int,
        date: GameMonth,
    ): Int {
        val diplomat = world.getGeneralById(diplomatId)!!
        assertTrue(diplomat.name.startsWith("외교"), "general $diplomatId must be the diplomacy-role player")
        assertTrue(diplomat.officerLevel > 4, "diplomacy-role player must hold a chief office")
        val candidates = world.listNations().filter { it.level > 0 && it.id != nationId }.sortedBy { it.id }
        for (target in candidates) {
            nationTurns.process(
                generalId = diplomatId,
                officerLevel = diplomat.officerLevel,
                nationCommand = ChosenCommand("che_선전포고", linkedMapOf("destNationID" to target.id)),
                lastTurn = LastTurn(),
                year = date.year,
                month = date.month,
                date = "00:00",
            )
            val forward = world.getDiplomacy(nationId, target.id)
            val reverse = world.getDiplomacy(target.id, nationId)
            if (forward?.state == 1 && reverse?.state == 1) {
                assertEquals(24, forward.term)
                assertEquals(24, reverse.term)
                return target.id
            }
        }
        error(
            "no neighboring nation accepted che_선전포고; active=${candidates.map { it.id }} " +
                "cities=${world.listCities().groupingBy { it.nationId }.eachCount()}",
        )
    }

    private fun settleDeclaration(
        world: InMemoryTurnWorld,
        monthlyRunner: ScenarioMonthlyRunner,
        from: GameMonth,
        nationId: Int,
        targetNationId: Int,
    ): GameMonth {
        var date = from
        repeat(24) {
            monthlyRunner.run(date)
            date = date.next()
        }
        assertEquals(0, world.getDiplomacy(nationId, targetNationId)?.state)
        assertEquals(0, world.getDiplomacy(targetNationId, nationId)?.state)
        return date
    }

    private fun GameMonth.next(): GameMonth =
        if (month == 12) GameMonth(year + 1, 1) else GameMonth(year, month + 1)

    private fun flush(world: InMemoryTurnWorld, recorder: ChangeRecorder) {
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        JdbcFlushExecutor(named, TransactionTemplate(DataSourceTransactionManager(dataSource))).flush(payload)
    }

    private fun recorderFor(world: InMemoryTurnWorld): ChangeRecorder {
        var nextMessageId = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM message", Int::class.java) ?: 0
        var nextAuctionId = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM ng_auction", Int::class.java) ?: 0
        return ChangeRecorder(
            messageIdAllocator = { ++nextMessageId },
            auctionIdAllocator = { ++nextAuctionId },
            kvWriteObserver = world::applyKvDirtyFree,
        )
    }

    private fun countWhere(table: String, predicate: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table WHERE $predicate", Int::class.java) ?: 0

    private fun assertAllowed(outcome: ReservedTurnHandler.HandledTurn, context: String = "") {
        assertFalse(
            outcome.fellBack,
            listOf(context, "${outcome.definition.key} fell back: ${outcome.denyReason}").filter { it.isNotBlank() }.joinToString(" "),
        )
    }

    private fun roleSpecs(): List<PlayerSpec> = listOf(
        PlayerSpec("군주", leadership = 65, strength = 60, intel = 55, politics = 50, charm = 45),
        PlayerSpec("내정농지", leadership = 40, strength = 35, intel = 75, politics = 75, charm = 50),
        PlayerSpec("내정상업", leadership = 35, strength = 35, intel = 80, politics = 75, charm = 50),
        PlayerSpec("내정치안", leadership = 45, strength = 75, intel = 45, politics = 65, charm = 45),
        PlayerSpec("내정성벽", leadership = 45, strength = 80, intel = 45, politics = 60, charm = 45),
        PlayerSpec("내정수비", leadership = 50, strength = 80, intel = 45, politics = 55, charm = 45),
        PlayerSpec("내정기술", leadership = 35, strength = 35, intel = 80, politics = 75, charm = 50),
        PlayerSpec("전쟁", leadership = 80, strength = 80, intel = 50, politics = 35, charm = 30),
        PlayerSpec("징병", leadership = 80, strength = 50, intel = 50, politics = 45, charm = 50),
        PlayerSpec("외교", leadership = 45, strength = 35, intel = 65, politics = 80, charm = 50),
    )

    private companion object {
        const val NATION_COUNT = 6
        const val MIN_ATTACK_CREW = 100
        const val RECRUIT_AMOUNT = 5_000
        const val PREPARATION_GOLD = 700
        const val PREPARATION_RICE = 200
        const val MAX_BOT_STEPS = 2_000
        const val MAX_SUPPLY_STEPS = 100
    }
}

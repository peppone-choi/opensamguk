package opensamguk.engine.turn

import opensamguk.common.constants.EffectiveGameConst
import opensamguk.common.world.WorldId
import opensamguk.engine.boot.SeedBootstrap
import opensamguk.engine.boot.WorldSnapshotLoader
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.world.isFoundableCityLevel
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.sql.DataSource
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HanAiLifecycleReplayIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var loader: WorldSnapshotLoader
    private var dockerAvailable = false

    @BeforeAll
    fun setUpClass() {
        dockerAvailable = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        if (!dockerAvailable) return

        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val dataSource: DataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .migrate()
        jdbc = JdbcTemplate(dataSource)
        val bootstrap = SeedBootstrap(scenarioCode = "scenario_1010", worldId = WorldId(1))
        assertTrue(bootstrap.ensureSeeded(jdbc), "fresh database must seed scenario_1010")
        loader = WorldSnapshotLoader(jdbc, bootstrap, WorldId(1))
    }

    @AfterAll
    fun tearDownClass() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `scenario 1010 Han NPC lifecycle is observable replayable and within country turn budget`() {
        assumeTrue(dockerAvailable, "Docker unavailable — Han lifecycle replay IT skipped (not failed)")

        val first = replay(loader.buildSnapshot())
        val second = replay(loader.buildSnapshot())

        assertEquals(first.orderedCommands, second.orderedCommands, "same seed and world must replay ordered AI commands")
        assertEquals(first.stateHash, second.stateHash, "same seed and world must replay the final in-memory state")
        assertEquals(first.counts, second.counts, "same seed and world must replay category counters")
        println("HAN_LIFECYCLE_COUNTS ${first.counts}")
        println(
            "HAN_LIFECYCLE_ACTIONS " + first.orderedCommands
                .groupingBy { it.split('|')[3] }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .joinToString(),
        )
        assertTrue(first.initialNationlessNpcCount > 0, "scenario_1010 must retain its opening nationless NPC population")
        assertEquals(0, first.initialWanderingNpcCount, "scenario_1010 has no active level-0 wandering NPC")
        assertTrue(
            listOf(Category.ATTACK, Category.DEPLOY, Category.FOUND, Category.WANDER, Category.MOVE)
                .sumOf { first.counts.getValue(it) } > 0,
            "the natural opening must execute at least one observable NPC lifecycle category",
        )

        assertTrue(first.countryTurnNanos.isNotEmpty(), "the real nation-pass AI must run")
        assertTrue(
            first.countryTurnNanos.all { it < COUNTRY_TURN_BUDGET_NANOS },
            "every country-turn choice must remain under 5s; max=${first.countryTurnNanos.maxOrNull()!! / 1_000_000.0}ms",
        )

        println(
            "HAN_LIFECYCLE cycles=$CYCLES counts=${first.counts} commands=${first.orderedCommands.size} " +
                "stateHash=${first.stateHash} countryTurnMaxMs=${first.countryTurnNanos.maxOrNull()!! / 1_000_000.0}",
        )
    }

    @Test
    fun `controlled Han lifecycle lanes attack deploy found wander and move deterministically`() {
        assumeTrue(dockerAvailable, "Docker unavailable — Han lifecycle replay IT skipped (not failed)")
        val base = loader.buildSnapshot()
        val lanes = listOf(
            Category.ATTACK to attackFixture(base),
            Category.DEPLOY to deployFixture(base),
            Category.FOUND to foundingFixture(base),
            Category.WANDER to wanderingMoveFixture(base),
        )

        for ((category, fixture) in lanes) {
            val first = replay(fixture.snapshot, cycles = 1, runTimeOf = { fixture.runTime })
            val second = replay(fixture.snapshot, cycles = 1, runTimeOf = { fixture.runTime })
            assertEquals(first.orderedCommands, second.orderedCommands, "$category ordered commands must replay")
            assertEquals(first.stateHash, second.stateHash, "$category state must replay")
            println("HAN_CONTROLLED category=$category counts=${first.counts} commands=${first.orderedCommands} hash=${first.stateHash}")
            assertTrue(first.counts.getValue(category) > 0, "$category must execute through the real Han lifecycle")
            assertTrue(fixture.verifyResolved(first), "$category must mutate through the resolved action, not selection alone")
            if (category == Category.WANDER) {
                assertTrue(first.counts.getValue(Category.MOVE) > 0, "wandering AI must resolve the che_이동 move command")
            }
            assertTrue(first.countryTurnNanos.all { it < COUNTRY_TURN_BUDGET_NANOS }, "$category country turn exceeded 5s")
        }
    }

    private fun replay(
        snapshot: WorldSnapshot,
        cycles: Int = CYCLES,
        runTimeOf: (InMemoryTurnWorld) -> Instant = { world ->
            world.listGenerals().maxOf { it.turnTime }.plus(1, ChronoUnit.SECONDS)
        },
    ): ReplayResult {
        assertEquals("han", snapshot.state.config["mapName"], "scenario_1010 must exercise the Han variant")
        assertEquals(780, snapshot.cities.size, "playable-Han evidence must use the full city graph")
        assertEquals(229, snapshot.generals.size, "playable-Han evidence must use the active NPC roster")

        val world = InMemoryTurnWorld(snapshot)
        val state = world.getState()
        val hiddenSeed = state.meta["hiddenSeed"] as String
        val startYear = (state.meta["startYear"] as Number).toInt()
        val turnTerm = state.tickSeconds / 60
        val pipeline = GeneralActionPipeline()
        val registry = CommandRegistry(pipeline)
        val pipelineBuilder = EngineGeneralActionPipelineBuilder(world, startYear)
        val recorder = ChangeRecorder(kvWriteObserver = world::applyKvDirtyFree)
        val adapter = AiTurnAdapter(
            world = world,
            registry = registry,
            hiddenSeed = hiddenSeed,
            startYear = startYear,
            turnTerm = turnTerm,
            pipeline = pipeline,
            pipelineBuilder = pipelineBuilder,
            reservedCommandNameOf = { "휴식" },
        )
        val orderedCommands = ArrayList<String>()
        val countryTurnNanos = ArrayList<Long>()
        val resolvedGeneralActions = ArrayList<String>()

        val handler = ReservedTurnHandler(
            world = world,
            registry = registry,
            hiddenSeed = hiddenSeed,
            startYear = startYear,
            scenario = 1010,
            turnTerm = turnTerm,
            recorder = recorder,
            aiHook = { generalId, reserved ->
                adapter.chooseGeneralTurn(generalId, reserved).also { chosen ->
                    orderedCommands += commandRecord("general", generalId, chosen, world)
                }
            },
            pipelineBuilder = pipelineBuilder,
        )
        val nationProcessor = ProcessNationCommand(
            world = world,
            recorder = recorder,
            hiddenSeed = hiddenSeed,
            registry = registry,
            startYear = startYear,
            turnTerm = turnTerm,
            pipelineBuilder = pipelineBuilder,
        )
        val lifecycle = TurnDaemonLifecycle(
            world = world,
            handler = handler,
            nationProcessor = nationProcessor,
            reservedNationActionOf = { _, _ -> ReservedTurn("휴식", "") },
            chooseNationTurn = { generalId, reserved ->
                val general = checkNotNull(world.getGeneralById(generalId))
                val raw = world.getNationById(general.nationId)?.meta?.get("turn_last_${general.officerLevel}")
                @Suppress("UNCHECKED_CAST")
                val lastTurn = LastTurn.fromRaw(raw as? Map<String, Any?>)
                lateinit var chosen: ChosenCommand
                countryTurnNanos += measureNanoTime {
                    chosen = adapter.chooseNationTurn(generalId, reserved, lastTurn)
                }
                orderedCommands += commandRecord("nation", generalId, chosen, world)
                adapter.drainNationPassDeltas(recorder)
                chosen
            },
            beginGeneralTurn = adapter::beginGeneralTurn,
            lifecycleEnvOf = { live, date ->
                LifecycleEnv(
                    baselineKillturn = EffectiveGameConst.killturn(live.tickSeconds / 60, npcmode = 0),
                    year = live.currentYear,
                    month = live.currentMonth,
                    turnTerm = live.tickSeconds / 60,
                    isunited = (live.meta["isunited"] as? Number)?.toInt() ?: 0,
                    turnTimeHm = date,
                )
            },
            pullGeneralTurnOf = { adapter.drainGeneralPassDeltas(recorder) },
            observeHandledTurn = { handled ->
                if (!handled.fellBack) resolvedGeneralActions += handled.definition.key
            },
            reservedActionOf = { ReservedTurn("휴식", "") },
        )

        val initialNationlessNpcCount = world.listGenerals().count { it.npcState >= 2 && it.nationId == 0 }
        val initialWanderingNpcCount = world.listGenerals().count { general ->
            general.npcState >= 2 && world.getNationById(general.nationId)?.level == 0
        }
        repeat(cycles) {
            val started = System.nanoTime()
            val runTime = runTimeOf(world)
            val handled = lifecycle.runTick(runTime)
            assertTrue(handled.isNotEmpty(), "cycle ${it + 1} must drain existing single turns")
            println("HAN_LIFECYCLE_CYCLE cycle=${it + 1} handled=${handled.size} elapsedMs=${(System.nanoTime() - started) / 1_000_000.0}")
        }

        val counts = Category.entries.associateWith { category -> orderedCommands.count { matches(category, it) } }
        return ReplayResult(
            orderedCommands = orderedCommands,
            stateHash = stateHash(world),
            counts = counts,
            countryTurnNanos = countryTurnNanos,
            initialNationlessNpcCount = initialNationlessNpcCount,
            initialWanderingNpcCount = initialWanderingNpcCount,
            resolvedGeneralActions = resolvedGeneralActions,
            finalGeneralCityIds = world.listGenerals().associate { it.id to it.cityId },
            finalCityNationIds = world.listCities().associate { it.id to it.nationId },
            finalNationCapitalCityIds = world.listNations().associate { it.id to it.capitalCityId },
        )
    }

    private fun matches(category: Category, record: String): Boolean {
        val fields = record.split('|')
        val action = fields[3]
        val reason = fields.getOrElse(5) { "" }
        return when (category) {
            Category.ATTACK -> action == "che_출병"
            Category.DEPLOY -> action == "che_발령"
            Category.FOUND -> action in setOf("che_거병", "che_건국", "cr_건국", "che_무작위건국")
            Category.WANDER -> action == "che_이동" && reason == "do방랑군이동"
            Category.MOVE -> action == "che_이동"
            Category.OTHER -> action !in setOf(
                "che_출병", "che_발령", "che_거병", "che_건국", "cr_건국", "che_무작위건국", "che_이동",
            )
        }
    }

    private fun attackFixture(base: WorldSnapshot): ControlledFixture {
        val han = CityConstRegistry.of("han")
        require(421 in checkNotNull(han.byId(3)).path)
        require(CityConstRegistry.of("che").byId(421) == null)
        val actorNation = base.nations.first { nation -> base.generals.any { it.nationId == nation.id } }
        val defenderNation = base.nations.first { nation -> nation.id != actorNation.id && base.generals.any { it.nationId == nation.id } }
        val actor = base.generals.first { it.nationId == actorNation.id }
        val defender = base.generals.first { it.nationId == defenderNation.id }
        val at = actor.turnTime
        val future = at.plus(Duration.ofDays(3650))
        val generals = base.generals.map { general ->
            when (general.id) {
                actor.id -> general.copy(
                    officerLevel = 1, npcState = 2, cityId = 3, crew = 9_999, crewTypeId = 1100,
                    stats = GeneralStats(leadership = 70, strength = 70, intelligence = 70),
                    train = 100, atmos = 100, rice = 100_000, gold = 100_000, turnTime = at,
                )
                defender.id -> general.copy(
                    cityId = 421, crew = 1, crewTypeId = 1100, train = 100, atmos = 100,
                    rice = 100_000, turnTime = future,
                )
                else -> general.copy(nationId = 0, officerLevel = 1, npcState = 0, turnTime = future)
            }
        }
        val nations = base.nations.map { nation ->
            when (nation.id) {
                actorNation.id -> nation.copy(gold = 100_000, rice = 100_000)
                defenderNation.id -> nation.copy(gold = 100_000, rice = 100_000, capitalCityId = 421)
                else -> nation
            }
        }
        val diplomacy = base.diplomacy
            .filterNot {
                setOf(it.fromNationId, it.toNationId) == setOf(actorNation.id, defenderNation.id)
            } + listOf(
            TurnDiplomacy(actorNation.id, defenderNation.id, state = 0, term = 0),
            TurnDiplomacy(defenderNation.id, actorNation.id, state = 0, term = 0),
        )
        return ControlledFixture(
            base.copy(
                state = activeState(base, at, generalPriority = listOf("출병")),
                generals = generals,
                cities = base.cities.map {
                    when (it.id) {
                        3 -> it.copy(
                            nationId = actorNation.id,
                            supplyState = 1, frontState = 3,
                            population = 100_000, populationMax = 100_000,
                            agriculture = 20_000, agricultureMax = 20_000,
                            commerce = 20_000, commerceMax = 20_000,
                            security = 20_000, securityMax = 20_000,
                            defence = 20_000, defenceMax = 20_000,
                            wall = 20_000, wallMax = 20_000,
                            meta = LinkedHashMap(it.meta).apply {
                                put("trust", 100)
                                put("pop", 100_000)
                                put("pop_max", 100_000)
                            },
                        )
                        421 -> it.copy(
                            nationId = defenderNation.id,
                            supplyState = 1,
                            frontState = 3,
                            defence = 0,
                            wall = 0,
                        )
                        29 -> it.copy(nationId = defenderNation.id, supplyState = 1, frontState = 0)
                        else -> it.copy(nationId = 0, supplyState = 0, frontState = 0)
                    }
                },
                nations = nations,
                diplomacy = diplomacy,
            ),
            at.plusSeconds(1),
            verifyResolved = {
                "che_출병" in it.resolvedGeneralActions &&
                    it.finalNationCapitalCityIds[defenderNation.id] == 29
            },
        )
    }

    private fun deployFixture(base: WorldSnapshot): ControlledFixture {
        val nation = base.nations.first { row -> base.cities.count { it.nationId == row.id } >= 2 }
        val owned = base.cities.filter { it.nationId == nation.id }.take(2)
        val enemyCity = base.cities.first { it.nationId != 0 && it.nationId != nation.id }
        val members = base.generals.filter { it.nationId == nation.id }.take(2)
        require(members.size == 2)
        val chief = members[0]
        val at = chief.turnTime
        val future = at.plus(Duration.ofDays(3650))
        val generals = base.generals.map { general ->
            when (general.id) {
                chief.id -> general.copy(officerLevel = 12, npcState = 2, cityId = owned[0].id, turnTime = at)
                members[1].id -> general.copy(officerLevel = 1, npcState = 2, cityId = enemyCity.id, troopId = 0, turnTime = future)
                else -> general.copy(npcState = 0, turnTime = future)
            }
        }
        val cities = base.cities.map {
            when (it.id) {
                owned[0].id -> it.copy(supplyState = 1, frontState = 1)
                else -> if (it.nationId == nation.id) it.copy(supplyState = 0, frontState = 0) else it
            }
        }
        return ControlledFixture(
            base.copy(
                state = activeState(
                    base,
                    at,
                    nationPriority = listOf("NPC구출발령"),
                ),
                generals = generals,
                cities = cities,
                nations = base.nations.map {
                    if (it.id == nation.id) it.copy(capitalCityId = owned[0].id, chiefGeneralId = chief.id) else it
                },
            ),
            at.plusSeconds(1),
            verifyResolved = { it.finalGeneralCityIds[members[1].id] == owned[0].id },
        )
    }

    private fun foundingFixture(base: WorldSnapshot): ControlledFixture {
        val han = CityConstRegistry.of("han")
        val city = checkNotNull(base.cities.firstOrNull { it.id == 75 && it.nationId == 0 })
        require(isFoundableCityLevel(checkNotNull(han.byId(city.id)).level))
        val actors = base.generals.take(2)
        val nationId = base.nations.maxOf { it.id } + 1
        val at = actors[0].turnTime
        val future = at.plus(Duration.ofDays(3650))
        val generals = base.generals.map { general ->
            when (general.id) {
                actors[0].id -> general.copy(
                    nationId = nationId, cityId = city.id, officerLevel = 12, npcState = 2, crew = 10_000, turnTime = at,
                )
                actors[1].id -> general.copy(nationId = nationId, cityId = city.id, officerLevel = 1, npcState = 2, turnTime = future)
                else -> general.copy(turnTime = future)
            }
        }
        val wandering = Nation(
            id = nationId, name = "Han replay founder", color = "#123456", level = 0, capitalCityId = 0,
            chiefGeneralId = actors[0].id, gold = 100_000, rice = 100_000, meta = linkedMapOf("gennum" to 2),
        )
        return ControlledFixture(
            base.copy(
                state = activeState(base, at),
                generals = generals,
                cities = base.cities.map { if (it.id == city.id) it.copy(nationId = 0, defence = 0) else it },
                nations = base.nations + wandering,
            ),
            at.plusSeconds(1),
            verifyResolved = {
                "che_건국" in it.resolvedGeneralActions && it.finalCityNationIds[city.id] != 0
            },
        )
    }

    private fun wanderingMoveFixture(base: WorldSnapshot): ControlledFixture {
        val han = CityConstRegistry.of("han")
        require(421 in checkNotNull(han.byId(3)).path)
        require(CityConstRegistry.of("che").byId(421) == null)
        val actors = base.generals.take(2)
        val actor = actors[0]
        val nationId = base.nations.maxOf { it.id } + 1
        val at = actor.turnTime
        val future = at.plus(Duration.ofDays(3650))
        val generals = base.generals.map { general ->
            if (general.id == actor.id) {
                general.copy(
                    nationId = nationId, cityId = 3, officerLevel = 12, npcState = 2, crew = 0,
                    gold = 100_000, rice = 100_000, turnTime = at,
                    meta = LinkedHashMap(general.meta).apply { put("aux", linkedMapOf("movingTargetCityID" to 421)) },
                )
            } else if (general.id == actors[1].id) {
                general.copy(nationId = nationId, cityId = 3, officerLevel = 12, npcState = 2, turnTime = future)
            } else general.copy(turnTime = future)
        }
        val wandering = Nation(
            id = nationId, name = "Han replay wanderer", color = "#654321", level = 0, capitalCityId = 0,
            chiefGeneralId = actor.id, gold = 100_000, rice = 100_000, meta = linkedMapOf("gennum" to 2),
        )
        return ControlledFixture(
            base.copy(
                state = activeState(base, at),
                generals = generals,
                cities = base.cities.map {
                    when (it.id) {
                        3, 421 -> it.copy(nationId = 0, supplyState = 0, frontState = 0)
                        in checkNotNull(han.byId(3)).path.keys -> it.copy(nationId = base.nations.first().id)
                        else -> it
                    }
                },
                nations = base.nations + wandering,
            ),
            at.plusSeconds(1),
            verifyResolved = {
                "che_이동" in it.resolvedGeneralActions && it.finalGeneralCityIds[actor.id] == 421
            },
        )
    }

    private fun activeState(
        base: WorldSnapshot,
        at: Instant,
        generalPriority: List<String>? = null,
        nationPriority: List<String>? = null,
    ): TurnWorldState {
        return base.state.copy(
            currentYear = 200,
            lastTurnTime = at,
            meta = LinkedHashMap(base.state.meta).apply {
                put("hiddenSeed", "00000000000000000000000000000000")
                if (generalPriority != null) put("npc_general_policy", linkedMapOf("priority" to generalPriority))
                if (nationPriority != null) put("npc_nation_policy", linkedMapOf("priority" to nationPriority))
            },
        )
    }

    private fun fullyDeveloped(city: City): City = city.copy(
        population = 100_000,
        populationMax = 100_000,
        agriculture = 20_000,
        agricultureMax = 20_000,
        commerce = 20_000,
        commerceMax = 20_000,
        security = 20_000,
        securityMax = 20_000,
        defence = 20_000,
        defenceMax = 20_000,
        wall = 20_000,
        wallMax = 20_000,
        meta = LinkedHashMap(city.meta).apply {
            put("trust", 100)
            put("pop", 100_000)
            put("pop_max", 100_000)
        },
    )

    private fun commandRecord(pass: String, generalId: Int, command: ChosenCommand, world: InMemoryTurnWorld): String {
        val category = when (command.actionCode) {
            "che_출병" -> Category.ATTACK
            "che_발령" -> Category.DEPLOY
            "che_거병", "che_건국", "cr_건국", "che_무작위건국" -> Category.FOUND
            "che_이동" -> {
                val nation = world.getGeneralById(generalId)?.nationId?.let(world::getNationById)
                if (nation?.level == 0) Category.WANDER else Category.MOVE
            }
            else -> Category.OTHER
        }
        return "$pass|${category.name}|$generalId|${command.actionCode}|${canonical(command.args)}|${command.reason ?: ""}"
    }

    private fun stateHash(world: InMemoryTurnWorld): String {
        val state = world.getState()
        val canonicalState = buildString {
            append("state|").append(state.currentYear).append('|').append(state.currentMonth).append('|')
                .append(state.currentPhase).append('|').append(canonical(state.config)).append('|').append(canonical(state.meta)).append('\n')
            world.listGenerals().sortedBy { it.id }.forEach { g ->
                append("g|").append(g.id).append('|').append(g.nationId).append('|').append(g.cityId).append('|')
                    .append(g.troopId).append('|').append(g.experience).append('|').append(g.dedication).append('|')
                    .append(g.officerLevel).append('|').append(g.injury).append('|').append(g.gold).append('|')
                    .append(g.rice).append('|').append(g.crew).append('|').append(g.crewTypeId).append('|')
                    .append(g.train).append('|').append(g.atmos).append('|').append(g.npcState).append('|')
                    .append(g.turnTime).append('|').append(canonical(g.meta)).append('\n')
            }
            world.listCities().sortedBy { it.id }.forEach { c -> append("c|").append(c).append('|').append(canonical(c.meta)).append('\n') }
            world.listNations().sortedBy { it.id }.forEach { n -> append("n|").append(n).append('|').append(canonical(n.meta)).append('\n') }
            world.listTroops().sortedBy { it.id }.forEach { append("t|").append(it).append('\n') }
            world.listDiplomacy().sortedWith(compareBy({ it.fromNationId }, { it.toNationId })).forEach {
                append("d|").append(it).append('|').append(canonical(it.meta)).append('\n')
            }
            world.peekLogs().forEach { append("l|").append(it).append('|').append(canonical(it.meta)).append('\n') }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonicalState.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun canonical(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries.sortedBy { it.key.toString() }
            .joinToString(prefix = "{", postfix = "}") { "${it.key}:${canonical(it.value)}" }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        else -> value.toString()
    }

    private enum class Category { ATTACK, DEPLOY, FOUND, WANDER, MOVE, OTHER }

    private data class ReplayResult(
        val orderedCommands: List<String>,
        val stateHash: String,
        val counts: Map<Category, Int>,
        val countryTurnNanos: List<Long>,
        val initialNationlessNpcCount: Int,
        val initialWanderingNpcCount: Int,
        val resolvedGeneralActions: List<String>,
        val finalGeneralCityIds: Map<Int, Int>,
        val finalCityNationIds: Map<Int, Int>,
        val finalNationCapitalCityIds: Map<Int, Int?>,
    )

    private data class ControlledFixture(
        val snapshot: WorldSnapshot,
        val runTime: Instant,
        val verifyResolved: (ReplayResult) -> Boolean,
    )

    private companion object {
        const val CYCLES = 3
        const val COUNTRY_TURN_BUDGET_NANOS = 5_000_000_000L
    }
}

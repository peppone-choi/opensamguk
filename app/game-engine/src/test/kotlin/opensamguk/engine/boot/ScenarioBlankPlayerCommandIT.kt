package opensamguk.engine.boot

import opensamguk.common.wire.MakeGeneralOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.intake.MakeGeneralHandler
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
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
class ScenarioBlankPlayerCommandIT {

    private data class PlayerSpec(
        val role: String,
        val leadership: Int,
        val strength: Int,
        val intel: Int,
        val politics: Int,
        val charm: Int,
    )

    private data class CreatedPlayer(val spec: PlayerSpec, val generalId: Int, val userId: Int)

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
        val jdbcUrl = "${postgres.jdbcUrl}&sslmode=disable"
        dataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure()
            .dataSource(jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
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
    fun `scenario_0 supports many player-created nations through real commands`() {
        assumeTrue(dockerAvailable, "Docker unavailable - scenario blank player command IT skipped")

        val bootstrap = SeedBootstrap(scenarioCode = "scenario_0", worldId = opensamguk.common.world.WorldId(1))
        assertTrue(bootstrap.ensureSeeded(jdbc))
        assertEquals("scenario_0", jdbc.queryForObject("SELECT scenario_code FROM world_state WHERE id = 1", String::class.java))
        assertEquals(94, count("city"))
        assertEquals(0, count("nation"))
        assertEquals(0, count("general"))
        assertEquals(94, countWhere("city", "nation_id = 0"))

        val loader = WorldSnapshotLoader(jdbc, bootstrap, opensamguk.common.world.WorldId(1))
        val world = InMemoryTurnWorld(loader.buildSnapshot())
        val recorder = ChangeRecorder()
        val makeGeneral = MakeGeneralHandler(
            world,
            recorder,
            nowProvider = { Instant.parse("0180-01-01T00:00:00Z") },
        )
        val roles = roleSpecs()
        val specs = (0 until 6).flatMap { nationIndex ->
            roles.mapIndexed { roleIndex, spec -> nationIndex * roles.size + roleIndex + 1 to spec }
        }
        val players = specs.map { (idx, spec) ->
            val userId = 10_000 + idx
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
            val ok = assertIs<MakeGeneralOk>(result, "idx=$idx role=${spec.role} result=$result")
            val created = world.getGeneralById(ok.generalId)!!
            val creationBonus =
                created.stats.leadership - spec.leadership +
                    created.stats.strength - spec.strength +
                    created.stats.intelligence - spec.intel
            assertTrue(created.stats.leadership >= spec.leadership, "${spec.role} leadership bonus must be non-negative")
            assertTrue(created.stats.strength >= spec.strength, "${spec.role} strength bonus must be non-negative")
            assertTrue(created.stats.intelligence >= spec.intel, "${spec.role} intelligence bonus must be non-negative")
            assertTrue(creationBonus in 3..5, "${spec.role} creation bonus must total 3..5, actual=$creationBonus")
            assertEquals(spec.politics, created.stats.politics, "${spec.role} initial politics mismatch")
            assertEquals(spec.charm, created.stats.charm, "${spec.role} initial charm mismatch")
            CreatedPlayer(spec, ok.generalId, userId)
        }

        val startYear = (world.getState().meta["startYear"] as Number).toInt()
        val hiddenSeed = world.getState().meta["hiddenSeed"] as String
        val registry = CommandRegistry(GeneralActionPipeline())
        val turns = ReservedTurnHandler(
            world,
            registry,
            hiddenSeed,
            startYear,
            scenario = 0,
            recorder = recorder,
        )
        val groups = players.chunked(roles.size)
        val leaderNationIds = groups.mapIndexed { index, group ->
            val leaderId = group.first().generalId
            assertAllowed(turns.handle(leaderId, ReservedTurn("che_거병", ""), startYear, 1, "00:00"))
            val nationId = world.getGeneralById(leaderId)!!.nationId
            group.drop(1).forEach { followerId ->
                assertAllowed(
                    turns.handle(
                        followerId.generalId,
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
        leaderNationIds.forEachIndexed { index, nationId ->
            val leaderId = groups[index].first().generalId
            assertAllowed(
                turns.handle(
                    leaderId,
                    ReservedTurn(
                        "che_건국",
                        """{"nationName":"테스트국${index + 1}","nationType":"che_중립","colorType":$index}""",
                    ),
                    startYear,
                    2,
                    "00:00",
                ),
            )
            val nation = world.getNationById(nationId)!!
            assertEquals(1, nation.level)
            assertEquals("테스트국${index + 1}", nation.name)
            assertEquals(roles.size, (nation.meta["gennum"] as Number).toInt())
        }

        groups.flatten().forEach { runRoleCommand(turns, world, it, startYear) }

        assertEquals(60, world.listGenerals().count { it.nationId > 0 })
        assertEquals(6, world.listNations().count { it.level == 1 })
        assertEquals(6, world.listCities().count { it.nationId > 0 })
        assertEquals(6, world.listGenerals().count { it.name.startsWith("전쟁") && it.crew > 0 && it.train > 40.0 })
        assertEquals(6, world.listGenerals().count { it.name.startsWith("징병") && it.crew > 0 })
        val expectedCities = world.listCities().filter { it.nationId > 0 }.associateBy { it.id }
        val expectedNations = world.listNations().filter { it.level > 0 }.associateBy { it.id }
        val expectedGeneralStats = world.listGenerals().associate { it.id to it.stats }

        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        assertEquals(60, payload.createdGenerals.size)
        assertEquals(6, payload.createdNations.size)
        assertTrue(payload.updatedCities.size >= 6)
        assertTrue(payload.logEntries.isNotEmpty())
        JdbcFlushExecutor(named, TransactionTemplate(DataSourceTransactionManager(dataSource))).flush(payload)

        val restarted = WorldSnapshotLoader(jdbc, bootstrap, opensamguk.common.world.WorldId(1)).buildSnapshot()
        assertEquals(60, restarted.generals.count { it.nationId > 0 })
        assertEquals(6, restarted.nations.count { it.level == 1 })
        assertEquals(6, restarted.cities.count { it.nationId > 0 })
        val restartedAccessLogs = restarted.accessLogs.associateBy { it.generalId }
        for (player in players) {
            val actual = restarted.generals.single { it.id == player.generalId }
            assertEquals(player.userId.toString(), actual.userId, "general ${player.generalId} owner restart mismatch")
            assertEquals(player.userId.toLong(), restartedAccessLogs.getValue(player.generalId).userId)
            assertEquals(expectedGeneralStats.getValue(player.generalId), actual.stats, "${player.spec.role} stats restart mismatch")
        }
        assertEquals(60, restartedAccessLogs.values.mapNotNull { it.userId }.toSet().size)
        for ((cityId, expected) in expectedCities) {
            val actual = restarted.cities.single { it.id == cityId }
            assertEquals(expected.agriculture, actual.agriculture, "city $cityId agriculture restart mismatch")
            assertEquals(expected.commerce, actual.commerce, "city $cityId commerce restart mismatch")
            assertEquals(expected.security, actual.security, "city $cityId security restart mismatch")
            assertEquals(expected.wall, actual.wall, "city $cityId wall restart mismatch")
            assertEquals(expected.defence, actual.defence, "city $cityId defence restart mismatch")
        }
        for ((nationId, expected) in expectedNations) {
            assertEquals(expected.tech, restarted.nations.single { it.id == nationId }.tech, "nation $nationId tech restart mismatch")
        }
        assertEquals(6, restarted.generals.count { it.name.startsWith("전쟁") && it.crew > 0 && it.train > 40.0 })
        assertEquals(6, restarted.generals.count { it.name.startsWith("징병") && it.crew > 0 })
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM log_entry", Int::class.java)!! > 0)
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

    private fun runRoleCommand(
        turns: ReservedTurnHandler,
        world: InMemoryTurnWorld,
        player: CreatedPlayer,
        startYear: Int,
    ) {
        val beforeGeneral = world.getGeneralById(player.generalId)!!
        val beforeCity = world.getCityById(beforeGeneral.cityId)!!
        val beforeNation = world.getNationById(beforeGeneral.nationId)!!
        when (player.spec.role) {
            "군주" -> assertAllowed(turns.handle(player.generalId, ReservedTurn("che_상업투자", ""), startYear, 2, "00:00"))
            "내정농지" -> assertAllowed(turns.handle(player.generalId, ReservedTurn("che_농지개간", ""), startYear, 2, "00:00"))
            "내정상업" -> assertAllowed(turns.handle(player.generalId, ReservedTurn("che_상업투자", ""), startYear, 2, "00:00"))
            "내정치안" -> assertAllowed(turns.handle(player.generalId, ReservedTurn("che_치안강화", ""), startYear, 2, "00:00"))
            "내정성벽" -> assertAllowed(turns.handle(player.generalId, ReservedTurn("che_성벽보수", ""), startYear, 2, "00:00"))
            "내정수비" -> assertAllowed(turns.handle(player.generalId, ReservedTurn("che_수비강화", ""), startYear, 2, "00:00"))
            "내정기술" -> assertAllowed(turns.handle(player.generalId, ReservedTurn("che_기술연구", ""), startYear, 2, "00:00"))
            "전쟁" -> {
                assertAllowed(
                    turns.handle(
                        player.generalId,
                        ReservedTurn("che_징병", """{"crewType":1100,"amount":1000}"""),
                        startYear,
                        2,
                        "00:00",
                    ),
                )
                assertAllowed(turns.handle(player.generalId, ReservedTurn("che_훈련", ""), startYear, 2, "00:00"))
            }
            "징병" -> assertAllowed(
                turns.handle(
                    player.generalId,
                    ReservedTurn("che_징병", """{"crewType":1100,"amount":800}"""),
                    startYear,
                    2,
                    "00:00",
                ),
            )
            "외교" -> assertAllowed(turns.handle(player.generalId, ReservedTurn("che_농지개간", ""), startYear, 2, "00:00"))
        }

        val afterGeneral = world.getGeneralById(player.generalId)!!
        val afterCity = world.getCityById(afterGeneral.cityId)!!
        val afterNation = world.getNationById(afterGeneral.nationId)!!
        when (player.spec.role) {
            "군주", "내정상업" -> assertTrue(afterCity.commerce > beforeCity.commerce, "${player.spec.role} must increase commerce")
            "내정농지", "외교" -> assertTrue(afterCity.agriculture > beforeCity.agriculture, "${player.spec.role} must increase agriculture")
            "내정치안" -> assertTrue(afterCity.security > beforeCity.security, "내정치안 must increase security")
            "내정성벽" -> assertTrue(afterCity.wall > beforeCity.wall, "내정성벽 must increase wall")
            "내정수비" -> assertTrue(afterCity.defence > beforeCity.defence, "내정수비 must increase defence")
            "내정기술" -> assertTrue(afterNation.tech > beforeNation.tech, "내정기술 must increase nation tech")
            "전쟁" -> {
                assertTrue(afterGeneral.crew > beforeGeneral.crew, "전쟁 must recruit crew")
                assertTrue(afterGeneral.train > beforeGeneral.train, "전쟁 must train recruited crew")
            }
            "징병" -> assertTrue(afterGeneral.crew > beforeGeneral.crew, "징병 must increase crew")
        }
    }

    private fun assertAllowed(outcome: ReservedTurnHandler.HandledTurn) {
        assertFalse(outcome.fellBack, "${outcome.definition.key} fell back: ${outcome.denyReason}")
    }

    private fun count(table: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java) ?: 0

    private fun countWhere(table: String, predicate: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table WHERE $predicate", Int::class.java) ?: 0
}

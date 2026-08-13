package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.engine.run.TurnRunService
import opensamguk.infra.persistence.JdbcFlushExecutor
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FullRehydrateTurnGateIT {

    private val continuousWorldId = WorldId(501)
    private val restartWorldId = WorldId(502)
    private val poisonWorldId = WorldId(503)
    private val start = Instant.parse("0200-01-01T00:00:00Z")
    private val firstTick = start.plusSeconds(3600)
    private val secondTick = start.plusSeconds(7200)
    private val config = FullRehydrateFixtureConfig(
        generalId = 1501,
        cityId = 2501,
        nationId = 3501,
        secondNationId = 3502,
        hiddenSeed = "00000000000000000000000000000000",
        startYear = 200,
        start = start,
    )

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var fixtures: FullRehydrateFixtureFactory
    private lateinit var seeder: FullRehydrateWorldSeeder
    private lateinit var persistence: FullRehydratePersistenceSignatures
    private lateinit var flushAssertions: FullRehydrateFlushAssertions

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
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
        val jdbc = JdbcTemplate(dataSource)
        val namedJdbc = NamedParameterJdbcTemplate(dataSource)
        val executor = JdbcFlushExecutor(namedJdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)))
        fixtures = FullRehydrateFixtureFactory(jdbc, namedJdbc, executor, config)
        seeder = FullRehydrateWorldSeeder(jdbc, namedJdbc, config)
        persistence = FullRehydratePersistenceSignatures(jdbc, namedJdbc, config, poisonWorldId)
        flushAssertions = FullRehydrateFlushAssertions(executor)
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `restart between identical reserved turns matches the uninterrupted world`() {
        seeder.seedWorld(continuousWorldId, "기준세계", "기준장수")
        seeder.seedWorld(restartWorldId, "기준세계", "기준장수")
        seeder.seedWorld(poisonWorldId, "독성세계", "독성장수")
        val poisonBefore = persistence.poisonWorld()
        seeder.reserveTwoTurns(continuousWorldId, "continuous")
        seeder.reserveTwoTurns(restartWorldId, "restart")

        val continuous = fixtures.create(continuousWorldId)

        val continuousFirst = continuous.service.runTick(firstTick)
        assertResolved(continuousFirst, "che_기술연구")
        flushAssertions.assertTechnologyFlush(continuousFirst)
        fixtures.create(restartWorldId).let { preRestart ->
            val restartFirst = preRestart.service.runTick(firstTick)
            assertResolved(restartFirst, "che_기술연구")
            flushAssertions.assertTechnologyFlush(restartFirst)
        }

        assertEquals(firstTick, continuous.world.getState().lastTurnTime)
        assertEquals(firstTick.toString(), persistence.persistedClock(continuousWorldId))
        assertEquals(firstTick.toString(), persistence.persistedClock(restartWorldId))
        assertEquals(
            persistence.reservedTurns(continuousWorldId),
            persistence.reservedTurns(restartWorldId),
            "the general_turn queue must be durable before the restart boundary",
        )
        assertEquals("continuous-2", persistence.queuedRequestId(continuousWorldId))
        assertEquals("restart-2", persistence.queuedRequestId(restartWorldId))
        assertEquals(
            persistence.commandResults(continuousWorldId),
            persistence.commandResults(restartWorldId),
            "the terminal-result channel must be durable before the restart boundary",
        )

        val continuousSecond = continuous.service.runTick(secondTick)
        assertResolved(continuousSecond, "che_농지개간")
        flushAssertions.assertFarmFlush(continuousSecond)
        val restarted = fixtures.create(restartWorldId)
        val restartSecond = restarted.service.runTick(secondTick)
        assertResolved(restartSecond, "che_농지개간")
        flushAssertions.assertFarmFlush(restartSecond)

        assertEquals(secondTick, continuous.world.getState().lastTurnTime)
        assertEquals(secondTick, restarted.world.getState().lastTurnTime)
        assertEquals(
            fullRehydrateHotStateSignature(continuous.world),
            fullRehydrateHotStateSignature(restarted.world),
            "N + 1 must see the same hot world whether N stayed resident or was rehydrated from PostgreSQL",
        )
        val continuousReloaded = fixtures.create(continuousWorldId).world
        val restartReloaded = fixtures.create(restartWorldId).world
        assertEquals(
            fullRehydrateHotStateSignature(continuous.world),
            fullRehydrateHotStateSignature(continuousReloaded),
            "the uninterrupted N + 1 hot state must survive a second discard/reload",
        )
        assertEquals(
            fullRehydrateHotStateSignature(restarted.world),
            fullRehydrateHotStateSignature(restartReloaded),
            "the restarted N + 1 hot state must survive a second discard/reload",
        )
        assertEquals(
            fullRehydrateHotStateSignature(continuousReloaded),
            fullRehydrateHotStateSignature(restartReloaded),
            "the two durable N + 1 snapshots must be equivalent after both worlds reload",
        )
        assertEquals(persistence.persistedState(continuousWorldId), persistence.persistedState(restartWorldId))
        assertEquals(persistence.rank(continuousWorldId), persistence.rank(restartWorldId))
        assertEquals(persistence.reservedTurns(continuousWorldId), persistence.reservedTurns(restartWorldId))
        assertEquals(persistence.commandResults(continuousWorldId), persistence.commandResults(restartWorldId))
        assertEquals(persistence.commandOutbox(continuousWorldId), persistence.commandOutbox(restartWorldId))
        assertEquals(listOf("continuous-1", "continuous-2"), persistence.commandResultRequestIds(continuousWorldId))
        assertEquals(listOf("restart-1", "restart-2"), persistence.commandResultRequestIds(restartWorldId))
        assertEquals(listOf("continuous-1", "continuous-2"), persistence.commandOutboxRequestIds(continuousWorldId))
        assertEquals(listOf("restart-1", "restart-2"), persistence.commandOutboxRequestIds(restartWorldId))
        val continuousLogs = persistence.koreanLogHex(continuousWorldId)
        assertTrue(continuousLogs.isNotEmpty(), "the turn must persist non-empty Korean action logs")
        assertEquals(continuousLogs, persistence.koreanLogHex(restartWorldId))
        assertFalse(
            restarted.world.listGenerals().any { it.name == "독성장수" },
            "the restarted world must never load the same local id from the poison world",
        )
        assertFalse(restarted.world.listCities().any { it.name == "독성세계 도시" })
        assertFalse(restarted.world.listNations().any { it.name.startsWith("독성세계") })
        assertFalse(restarted.world.listTroops().any { it.name == "독성세계 부대" })
        assertEquals(poisonBefore, persistence.poisonWorld(), "neither world may flush same-local-id poison rows")
    }

    private fun assertResolved(result: TurnRunService.TickResult, actionCode: String) {
        assertEquals(1, result.handled.size, "one due general must run at this deterministic tick")
        assertEquals(actionCode, result.handled.single().definition.key)
        assertFalse(result.handled.single().fellBack, "$actionCode must resolve rather than fall back")
    }
}

package opensamguk.engine.boot

import opensamguk.logic.vision.ScoutInputCodec
import opensamguk.logic.vision.ScoutReports

import java.nio.file.Path
import kotlin.test.*
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.hwiha.*
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.seed.HanWorldArtifactsResolver
import opensamguk.logic.input.*
import opensamguk.logic.world.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** Real database boundary for `action.scout`: the notebook survives flush and a cold reload, and only the actor row changes. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScoutPersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private lateinit var fixture: EnlistmentFixture
    private val bundle by lazy { HanWorldArtifactsResolver(Path.of("../..")).artifacts(HanWorldVariant.V3_1133) }
    private val context by lazy { VisionContext(bundle.projection.topology, bundle.landMarchMetrics, bundle.commanderyIndex) }

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable)
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        flush = JdbcFlushExecutor(NamedParameterJdbcTemplate(source), TransactionTemplate(DataSourceTransactionManager(source)))
        fixture = EnlistmentFixture(jdbc, flush)
    }
    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }
    private fun cold(id: Int) = InMemoryTurnWorld(fixture.load(id))

    @Test fun `scout notebook persists through the recorder flush and reloads cold`() {
        val id = 731; fixture.seed(id)
        jdbc.update("UPDATE general SET user_id='77' WHERE world_id=? AND id=1", id)
        var world = cold(id)
        val index = context.commanderies
        val origin = requireNotNull(index.commanderyOf(world.positionOf(1)))
        val target = index.commanderies[index.neighbours(origin).first()]
        val others = world.listGenerals().filter { it.id != 1 }
        val recorder = ChangeRecorder()
        assertEquals(TurnOutcome.Applied(ScoutInputCodec.INPUT_ID),
            ScoutHandler(world, recorder, context).handle(1, """{"commanderyId":"${target.id}"}""", 77))
        val written = requireNotNull(ScoutReports.read(world.getGeneralById(1)!!.meta))
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
        world = cold(id)
        assertEquals(written, ScoutReports.read(world.getGeneralById(1)!!.meta))
        assertEquals(target.id, written.reports.single().commanderyId)
        assertEquals(others, world.listGenerals().filter { it.id != 1 })
    }
}

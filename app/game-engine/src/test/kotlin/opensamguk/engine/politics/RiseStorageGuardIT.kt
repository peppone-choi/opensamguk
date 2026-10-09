package opensamguk.engine.politics

import kotlin.test.*
import opensamguk.engine.boot.EnlistmentFixture
import opensamguk.engine.campaign.DomesticContext
import opensamguk.engine.campaign.PoliticalHandler
import opensamguk.engine.campaign.TurnOutcome
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.logic.input.*
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

/** Real PostgreSQL rejection/readback preserves all non-renown rise guards. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RiseStorageGuardIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private lateinit var fixture: EnlistmentFixture

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable,
            "Docker unavailable: rise rejection PostgreSQL readback NOT verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        flush = JdbcFlushExecutor(NamedParameterJdbcTemplate(source),
            TransactionTemplate(DataSourceTransactionManager(source)))
        fixture = EnlistmentFixture(jdbc, flush)
    }

    @AfterAll fun cleanup() { if (this::postgres.isInitialized) postgres.stop() }

    private fun seed(id: Int) {
        fixture.seed(id)
        jdbc.update("""UPDATE general SET user_id=42,
            meta=jsonb_set(jsonb_set(meta,'{lord}','false'),'{personPolicy,renownCapacity}',?::jsonb)
            WHERE world_id=? AND id=1""", "0", id)
    }

    private val delivered by lazy {
        val source = checkNotNull(javaClass.classLoader.getResource("command-catalog/input-catalog.json")).readText()
        val row = Regex("""("inputId":\s*"action\.rise"[\s\S]*?"deliveryState":\s*")(PLANNED|DOMAIN_READY|HANDLER_READY|UI_READY)(")""")
        assertTrue(row.containsMatchIn(source))
        InputCatalog.parse(row.replace(source, "${'$'}1HANDLER_READY${'$'}3"))
    }

    private fun assertRowsSame(expected: WorldSnapshot, actual: WorldSnapshot) {
        assertEquals(expected.generals, actual.generals)
        assertEquals(expected.nations, actual.nations)
        assertEquals(expected.cities, actual.cities)
        assertEquals(expected.retainers, actual.retainers)
        assertEquals(expected.bugoks, actual.bugoks)
        assertEquals(expected.troops, actual.troops)
        assertEquals(expected.diplomacy, actual.diplomacy)
        assertEquals(expected.generalPositionSnapshot!!.statesByGeneralId,
            actual.generalPositionSnapshot!!.statesByGeneralId)
    }

    private fun rejectAndReload(id: Int, expected: String, raw: String = "{}", owner: Int = 42,
        catalog: InputCatalog = delivered) {
        val before = fixture.load(id)
        val world = InMemoryTurnWorld(before)
        val recorder = ChangeRecorder()
        val outcome = assertIs<TurnOutcome.Rejected>(PoliticalHandler(world, recorder, DomesticContext(), catalog)
            .handle(PoliticalInput.RISE, 1, raw, "rise-reject-$id", owner))
        assertEquals(expected, outcome.code)
        assertTrue(recorder.dirtyGeneralIds().isEmpty())
        assertTrue(recorder.dirtyCityIds().isEmpty())
        assertTrue(recorder.dirtyNationIds().isEmpty())
        assertTrue(world.peekLogs().isEmpty())
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
        assertRowsSame(before, fixture.load(id))
        assertRowsSame(before, fixture.load(id)) // A second cold read does not manufacture a transition.
    }

    @Test fun `foreign troop ownership is rejected with exact persisted resources relations and position`() {
        val id = 7455
        seed(id)
        jdbc.update("INSERT INTO troop(world_id,troop_leader,nation,name) VALUES (?,1,1,'foreign troop')", id)
        jdbc.update("UPDATE general SET troop_id=1 WHERE world_id=? AND id IN (1,2)", id)
        rejectAndReload(id, PoliticalFailure.STATE_UNAVAILABLE.name)
    }

    @Test fun `unowned actor and forged arguments have no durable nation or relationship effects`() {
        seed(7456)
        rejectAndReload(7456, "FORBIDDEN", owner = 99)
        seed(7457)
        rejectAndReload(7457, PoliticalFailure.INVALID_INPUT.name, "{\"nationId\":1}")
    }

    @Test fun `the approved production catalog still preserves every row when the county has an owner`() {
        seed(7458)
        jdbc.update("UPDATE city SET nation_id=1 WHERE world_id=? AND id=(SELECT city_id FROM general WHERE world_id=? AND id=1)", 7458, 7458)
        rejectAndReload(7458, PoliticalFailure.COUNTY_NOT_AVAILABLE.name, catalog = InputCatalog.load())
    }
}

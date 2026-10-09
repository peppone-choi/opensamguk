package opensamguk.engine.boot

import kotlin.test.*
import opensamguk.engine.campaign.DomesticContext
import opensamguk.engine.campaign.RetireHandler
import opensamguk.engine.campaign.TurnOutcome
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.council.CurrentRulerBinding
import opensamguk.logic.input.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/** Cold reload of authoritative eligibility fields, with retirement still closed in production. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class RetireEligibilityPersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private lateinit var fixture: EnlistmentFixture

    @BeforeAll fun setup() {
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        flush = JdbcFlushExecutor(NamedParameterJdbcTemplate(source),
            TransactionTemplate(DataSourceTransactionManager(source)))
        fixture = EnlistmentFixture(jdbc, flush)
    }

    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    @Test fun `persisted age boundary and ruler survive reload while planned rejection preserves identity and retinue`() {
        val id = 777
        fixture.seed(id)
        jdbc.update("UPDATE general SET nation_id=1 WHERE world_id=? AND id IN (1,2)", id)
        jdbc.update("UPDATE general SET user_id=42,age=59,officer_level=12 WHERE world_id=? AND id=1", id)
        val meta = CurrentRulerBinding.with(mapOf("gennum" to 3, "keep" to 42), 1, "synthetic-seed-777",
            CurrentRulerBinding.SCENARIO_SEED_SOURCE)
        jdbc.update("UPDATE nation SET meta=?::jsonb WHERE world_id=? AND id=1", MetaJson.encode(meta), id)
        for (generalId in listOf(1, 2)) jdbc.update("""INSERT INTO general_turn
            (world_id,general_id,turn_idx,action_code,arg,request_id)
            VALUES (?,?,1,?,'{"stat":"strength"}'::jsonb,?)""",
            id, generalId, PersonalInput.SELF_TRAIN, "synthetic-reservation-$generalId")
        val request = RetireRequest(1, 2)
        var world = InMemoryTurnWorld(fixture.load(id))
        assertEquals(59, world.getGeneralById(1)!!.age)
        assertEquals(1, world.getNationById(1)!!.chiefGeneralId)
        assertEquals(RetireFailure.AGE_TOO_YOUNG, assertIs<RetireAssessment.Rejected>(
            RetireRules.assess(request, DomesticContext().projection(world))).reason)

        jdbc.update("UPDATE general SET age=60 WHERE world_id=? AND id=1", id)
        world = InMemoryTurnWorld(fixture.load(id))
        assertEquals(60, world.getGeneralById(1)!!.age)
        assertIs<RetireAssessment.Eligible>(RetireRules.assess(request, DomesticContext().projection(world)))
        val generals = world.listGenerals()
        val retainers = world.listRetainers()
        val bugoks = world.listBugoks()
        val nation = world.getNationById(1)
        val reservations = jdbc.queryForList("SELECT * FROM general_turn WHERE world_id=? ORDER BY general_id,turn_idx", id)
        val recorder = ChangeRecorder()
        val denied = assertIs<TurnOutcome.Rejected>(RetireHandler(world, recorder, DomesticContext())
            .handle(1, """{"successorGeneralId":2}""", "retire-777", 42))
        assertEquals(InputRejection.NOT_DELIVERED.name, denied.code)
        assertFalse(recorder.isDirty)
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
        val restored = InMemoryTurnWorld(fixture.load(id))
        assertEquals(generals, restored.listGenerals())
        assertEquals(retainers, restored.listRetainers())
        assertEquals(bugoks, restored.listBugoks())
        assertEquals(nation, restored.getNationById(1))
        assertEquals(reservations,
            jdbc.queryForList("SELECT * FROM general_turn WHERE world_id=? ORDER BY general_id,turn_idx", id))
        assertIs<RetireAssessment.Eligible>(RetireRules.assess(request, DomesticContext().projection(restored)))
    }
}

package opensamguk.engine.invariance

import kotlin.test.*
import opensamguk.engine.campaign.TurnOutcome
import opensamguk.engine.invariance.PersonalTurnFlushInvarianceFixture.Probe
import opensamguk.engine.invariance.PersonalTurnFlushInvarianceFixture.Schedule
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assumptions
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** GH893 / OPENSAM-292 G4; actual SQL flush and independent production reload. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersonalTurnFlushInvarianceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var fixture: PersonalTurnFlushInvarianceFixture

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable,
            "Docker unavailable: G4 actual PostgreSQL flush invariance NOT verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        fixture = PersonalTurnFlushInvarianceFixture(JdbcTemplate(source),
            TransactionTemplate(DataSourceTransactionManager(source)))
    }
    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    @Test fun `G4 same ordered personal turns survive batch and per actor flush with identical persisted and cold state`() {
        // Explicit test-only mutation runs must fail this unchanged equivalence assertion.
        val probe = System.getenv("OPENSAMGUK_G4_FLUSH_PROBE")?.let(Probe::valueOf) ?: Probe.NONE
        val batch = fixture.run(Schedule.BATCH, probe)
        val separate = fixture.run(Schedule.PER_ACTOR, probe)
        fixture.assertEquivalent(batch, separate)
        listOf(batch, separate).forEach { run ->
            run.handled.forEach { assertIs<TurnOutcome.Applied>(it.inputOutcome) }
            assertEquals(71, run.coldStrength)
            assertEquals(71, run.hotStrength)
            val generals = run.persisted["general"].associateBy { it["id"].asInt() }
            assertEquals(960, generals.getValue(1)["gold"].asInt())
            assertEquals(1040, generals.getValue(10)["gold"].asInt())
        }
    }

    @Test fun `G4 oracle detects a real missed single actor SQL write despite correct hot state`() {
        val batch = fixture.run(Schedule.BATCH, Probe.DROP_SINGLE_GENERAL_UPDATE)
        val separate = fixture.run(Schedule.PER_ACTOR, Probe.DROP_SINGLE_GENERAL_UPDATE)
        assertEquals(71, batch.coldStrength)
        assertEquals(71, separate.hotStrength)
        assertEquals(70, separate.coldStrength)
        separate.handled.forEach { assertIs<TurnOutcome.Applied>(it.inputOutcome) }
        assertNotEquals(fixture.hash(batch.persisted), fixture.hash(separate.persisted))
        assertNotEquals(fixture.hash(batch.cold), fixture.hash(separate.cold))
        assertFailsWith<AssertionError> { fixture.assertEquivalent(batch, separate) }
    }

    @Test fun `G4 oracle detects flush only next actor decision perturbation required by the approved contract`() {
        val batch = fixture.run(Schedule.BATCH, Probe.FLUSH_ONLY_NEXT_ACTOR)
        val separate = fixture.run(Schedule.PER_ACTOR, Probe.FLUSH_ONLY_NEXT_ACTOR)
        assertIs<TurnOutcome.Applied>(batch.handled.last().inputOutcome)
        val rejection = assertIs<TurnOutcome.Rejected>(separate.handled.last().inputOutcome)
        assertEquals("TRAINING_MAXED", rejection.code)
        assertNotEquals(fixture.hash(batch.persisted), fixture.hash(separate.persisted))
        assertNotEquals(fixture.hash(batch.cold), fixture.hash(separate.cold))
        assertFailsWith<AssertionError> { fixture.assertEquivalent(batch, separate) }
    }
}

package opensamguk.engine.boot

import kotlin.test.*
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.hwiha.*
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.logic.input.*
import opensamguk.logic.renown.RenownRules
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

/** Created game characters on the archived map; no historical provenance or playable scenario claim. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreatedPersonPersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private lateinit var fixture: EnlistmentFixture

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable,
            "Docker unavailable: HWIHA enlistment database roundtrip NOT verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        flush = JdbcFlushExecutor(NamedParameterJdbcTemplate(source), TransactionTemplate(DataSourceTransactionManager(source)))
        fixture = EnlistmentFixture(jdbc, flush)
    }
    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    @Test fun `created policy and actual five stats survive flush and cold reload then possession`() {
        fixture.seed(81)
        val snapshot = fixture.load(81)
        val world = InMemoryTurnWorld(snapshot)
        val recorder = ChangeRecorder()
        val cityId = snapshot.generals.first().cityId
        val request = opensamguk.common.wire.TurnDaemonCommand.MakeGeneral(
            userId = 77, name = "창작검증", leadership = 55, strength = 55, intel = 55,
            politics = 54, charm = 56, character = "Random", inheritCity = cityId)
        val result = assertIs<opensamguk.common.wire.MakeGeneralOk>(
            opensamguk.engine.intake.MakeGeneralHandler(world, recorder,
                previousPointReader = { 100000.0 }, nowProvider = { snapshot.state.lastTurnTime }).handle(request))
        val created = world.getGeneralById(result.generalId)!!
        assertEquals(PersonPolicyState(30, false, "opensamguk:created-general", "v1", created.id),
            PersonPolicyState.read(created.meta))
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
        val cold = InMemoryTurnWorld(fixture.load(81))
        val reloaded = cold.getGeneralById(created.id)!!
        assertEquals(created.stats, reloaded.stats)
        assertEquals(created.meta[PersonPolicyState.META_KEY], reloaded.meta[PersonPolicyState.META_KEY])
        assertEquals(false, reloaded.meta[LordStatus.META_KEY])
        assertEquals(world.positionOf(created.id), cold.positionOf(created.id))
        val budget = assertIs<EnlistmentPolicyResult.Ready>(EnlistmentPolicyReader(cold)
            .current(EnlistmentRequest(created.id, EnlistmentMode.RANDOM)))
        val st = created.stats
        assertEquals(RenownRules.personCost(st.leadership, st.strength, st.intelligence, st.politics, st.charm), budget.policy.actorCardCost)

        // Fixture transition models a prior rejection and released NPC; possession must preserve it.
        val reduced = PersonPolicyState.read(reloaded.meta)!!.copy(renownCapacity = 29)
        jdbc.update("UPDATE general SET user_id=NULL,npc_state=2,meta=jsonb_set(meta,'{hwihaPersonPolicy,renownCapacity}','29') WHERE world_id=81 AND id=?", created.id)
        val claimWorld = InMemoryTurnWorld(fixture.load(81))
        val claimRecorder = ChangeRecorder()
        val claim = opensamguk.engine.intake.ClaimNpcHandler(claimWorld, claimRecorder).handle(
            opensamguk.common.wire.TurnDaemonCommand.ClaimNpc(generalId = created.id, userId = 78L, userNick = "빙의검증"))
        assertEquals(true, assertIs<opensamguk.common.wire.GeneralBoolResult>(claim).ok)
        flush.flush(DatabaseHooks.toFlushPayload(claimWorld, claimRecorder, claimWorld.consumeDirtyState()))
        assertEquals(reduced, PersonPolicyState.read(fixture.load(81).generals.single { it.id == created.id }.meta))
    }
}

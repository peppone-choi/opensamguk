package opensamguk.engine.boot

import kotlin.test.*
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.hwiha.*
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.logic.input.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** Storage-boundary evidence only: these synthetic people are not a playable scenario. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NpcDispatchPersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private lateinit var fixture: EnlistmentFixture
    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker unavailable: dispatch storage NOT verified")
        postgres=PostgreSQLContainer("postgres:16-alpine"); postgres.start()
        val source=DriverManagerDataSource(postgres.jdbcUrl,postgres.username,postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc=JdbcTemplate(source)
        flush=JdbcFlushExecutor(NamedParameterJdbcTemplate(source),TransactionTemplate(DataSourceTransactionManager(source)))
        fixture=EnlistmentFixture(jdbc,flush)
    }
    @AfterAll fun teardown() { if(this::postgres.isInitialized) postgres.stop() }
    private fun save(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        flush.flush(DatabaseHooks.toFlushPayload(world,recorder,world.consumeDirtyState()))
    private fun seed(id: Int): Int {
        fixture.seed(id)
        val world=InMemoryTurnWorld(fixture.load(id)); val recorder=ChangeRecorder()
        assertIs<EnlistmentExecution.Applied>(EnlistmentExecutor(world,recorder)
            .execute(EnlistmentRequest(1,EnlistmentMode.NATION,1)) { error("direct enlistment") })
        save(world,recorder)
        jdbc.update("UPDATE general SET user_id=42 WHERE world_id=? AND id=1",id)
        val county=world.administrativeCountyIds.min()
        jdbc.update("UPDATE city SET nation_id=1 WHERE world_id=? AND id=?",id,county)
        return county
    }
    @Test fun `NPC issuer due turn persists first dispatch without human inbox and cannot repeat after cold reload`() {
        val id=111
        seed(id)
        jdbc.update("UPDATE general SET turn_time='0200-01-02T00:00:00Z' WHERE world_id=? AND id<>10",id)
        val before=fixture.load(id)
        val published=mutableListOf<String>()
        val late=java.time.Instant.parse("0200-01-01T03:00:01Z")
        val result=fixture.service(opensamguk.common.world.WorldId(id),InMemoryTurnWorld(before),published,intake=true)
            .runDueGeneralTurns(late)
        assertEquals(listOf(10),result.handled.map { it.generalId })
        val after=fixture.load(id)
        val dispatch=assertNotNull(DispatchState.read(after.generals.single { it.id==1 }.meta))
        assertEquals("npc-dispatch:111:10:1:200:1:1",dispatch.dispatchId)
        assertEquals(DispatchStatus.PENDING,dispatch.status)
        assertEquals(before.generals.single { it.id==1 },after.generals.single { it.id==1 }.copy(meta=before.generals.single { it.id==1 }.meta))
        assertEquals(before.retainers,after.retainers)
        assertEquals(before.generalPositionSnapshot!!.statesByGeneralId,after.generalPositionSnapshot!!.statesByGeneralId)
        fun assertReasonLogOnce() {
            assertEquals(1,jdbc.queryForObject("""SELECT count(*) FROM log_entry
                WHERE world_id=? AND general_id=1 AND scope='GENERAL' AND category='ACTION'
                AND text='담당 장수가 없는 아군 현의 첫 부임 대상으로 발령되었습니다.'""",Int::class.java,id))
        }
        assertReasonLogOnce()
        val cold=InMemoryTurnWorld(after)
        assertTrue(fixture.service(opensamguk.common.world.WorldId(id),cold,published,intake=true)
            .runDueGeneralTurns(late).handled.isEmpty())
        cold.setCurrentDate(200,1,2)
        fixture.service(opensamguk.common.world.WorldId(id),cold,published,intake=true).runDueGeneralTurns(late)
        assertEquals(dispatch,DispatchState.read(fixture.load(id).generals.single { it.id==1 }.meta))
        for(table in listOf("command_inbox","command_result","command_outbox"))
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM $table WHERE world_id=?",Int::class.java,id))
        assertReasonLogOnce()
        assertTrue(published.isEmpty())
    }
}

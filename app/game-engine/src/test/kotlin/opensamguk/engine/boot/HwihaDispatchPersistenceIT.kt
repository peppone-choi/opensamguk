package opensamguk.engine.boot

import kotlin.test.*
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.hwiha.*
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.logic.input.*
import opensamguk.logic.renown.RenownEventSource
import opensamguk.logic.renown.RenownEvents
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

/** Storage-boundary evidence only: these synthetic people are not a playable scenario. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HwihaDispatchPersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private lateinit var fixture: HwihaEnlistmentFixture
    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker unavailable: dispatch storage NOT verified")
        postgres=PostgreSQLContainer("postgres:16-alpine"); postgres.start()
        val source=DriverManagerDataSource(postgres.jdbcUrl,postgres.username,postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc=JdbcTemplate(source)
        flush=JdbcFlushExecutor(NamedParameterJdbcTemplate(source),TransactionTemplate(DataSourceTransactionManager(source)))
        fixture=HwihaEnlistmentFixture(jdbc,flush)
    }
    @AfterAll fun teardown() { if(this::postgres.isInitialized) postgres.stop() }
    private fun save(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        flush.flush(DatabaseHooks.toFlushPayload(world,recorder,world.consumeDirtyState()))
    private fun seed(id: Int): Int {
        fixture.seed(id)
        val world=InMemoryTurnWorld(fixture.load(id)); val recorder=ChangeRecorder()
        assertIs<EnlistmentExecution.Applied>(HwihaEnlistmentExecutor(world,recorder)
            .execute(EnlistmentRequest(1,EnlistmentMode.NATION,1)) { error("direct enlistment") })
        save(world,recorder)
        jdbc.update("UPDATE general SET user_id=42 WHERE world_id=? AND id=1",id)
        val county=world.administrativeCountyIds.min()
        jdbc.update("UPDATE city SET nation_id=1 WHERE world_id=? AND id=?",id,county)
        return county
    }
    @Test fun `pending dispatch cold reload acceptance and repeated reply preserve personal state`() {
        val county=seed(91); val before=fixture.load(91)
        val world=InMemoryTurnWorld(before); val recorder=ChangeRecorder()
        assertIs<DispatchExecution.Applied>(HwihaDispatchExecutor(world,recorder).issue("dispatch-91",DispatchRequest(10,1,county)))
        save(world,recorder)
        val pending=fixture.load(91)
        assertEquals(DispatchStatus.PENDING,DispatchState.read(pending.generals.single { it.id==1 }.meta)!!.status)
        assertEquals(before.retainers,pending.retainers)
        val cold=InMemoryTurnWorld(pending); val replyRecorder=ChangeRecorder()
        assertIs<DispatchExecution.Applied>(HwihaDispatchExecutor(cold,replyRecorder).reply(DispatchReplyRequest(1,"dispatch-91",true)))
        save(cold,replyRecorder)
        val after=fixture.load(91)
        val actor=after.generals.single { it.id==1 }
        assertEquals(county,CountyAssignment.read(actor.meta)!!.countyId)
        assertEquals(before.generals.single { it.id==1 },actor.copy(meta=before.generals.single { it.id==1 }.meta))
        assertEquals(before.generalPositionSnapshot!!.statesByGeneralId,after.generalPositionSnapshot!!.statesByGeneralId)
        assertEquals(before.bugoks,after.bugoks); assertEquals(before.retainers,after.retainers)
        val repeated=ChangeRecorder()
        assertEquals(DispatchFailure.ALREADY_RESOLVED,assertIs<DispatchExecution.Rejected>(
            HwihaDispatchExecutor(InMemoryTurnWorld(after),repeated).reply(DispatchReplyRequest(1,"dispatch-91",false))).reason)
        assertTrue(repeated.generalPatches().isEmpty())
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM general_turn WHERE world_id=91",Int::class.java))
    }
    @Test fun `refusal loyalty renown and terminal state survive cold reload with no second charge`() {
        val county=seed(92); val world=InMemoryTurnWorld(fixture.load(92)); val recorder=ChangeRecorder()
        assertIs<DispatchExecution.Applied>(HwihaDispatchExecutor(world,recorder).issue("dispatch-92",DispatchRequest(10,1,county)))
        save(world,recorder)
        val cold=InMemoryTurnWorld(fixture.load(92)); val replyRecorder=ChangeRecorder()
        assertIs<DispatchExecution.Applied>(HwihaDispatchExecutor(cold,replyRecorder).reply(DispatchReplyRequest(1,"dispatch-92",false)))
        save(cold,replyRecorder)
        val after=fixture.load(92)
        assertEquals(45,after.retainers.single { it.generalId==1 }.loyalty)
        // 거절 명망은 다음 월단평에 −4 로 한 번 — 즉시 깎지 않고, 쌓인 사건이 재적재 뒤에도 남는다.
        assertEquals(30,PersonPolicyState.read(after.generals.single { it.id==1 }.meta)!!.renownCapacity)
        assertEquals(listOf(RenownEventSource.DISPATCH_REFUSAL),
            RenownEvents.entries(after.generals.single { it.id==1 }.meta).map { it.source })
        assertEquals(DispatchStatus.REFUSED,DispatchState.read(after.generals.single { it.id==1 }.meta)!!.status)
        val repeated=ChangeRecorder()
        assertIs<DispatchExecution.Rejected>(HwihaDispatchExecutor(InMemoryTurnWorld(after),repeated)
            .reply(DispatchReplyRequest(1,"dispatch-92",false)))
        assertTrue(repeated.generalPatches().isEmpty())
    }
}

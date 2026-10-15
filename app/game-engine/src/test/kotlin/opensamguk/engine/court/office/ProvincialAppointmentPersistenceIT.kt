package opensamguk.engine.court.office

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.engine.campaign.CourtStateStore
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.MetaJson
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** Actual JDBC flush and cold database read for approved zero cost. Input delivery remains S2. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProvincialAppointmentPersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker unavailable: appointment persistence NOT verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        flush = JdbcFlushExecutor(NamedParameterJdbcTemplate(source), TransactionTemplate(DataSourceTransactionManager(source)))
    }

    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    private fun seed(id: Int) {
        jdbc.update("""INSERT INTO world_state(id,scenario_code,current_year,current_month,tick_seconds,config,meta)
            VALUES (?,'provincial-appointment-test',196,1,3600,'{}'::jsonb,'{}'::jsonb)""", id)
    }

    private fun rows(id: Int) = jdbc.queryForList(
        """SELECT key,value::text AS value FROM game_kv WHERE world_id=? AND "table"='game_env' AND namespace='game_env' ORDER BY key""", id)

    private fun cold(f: ProvincialAppointmentExecutorTest.Fixture): InMemoryTurnWorld {
        val meta = rows(f.worldId.value).associate { row ->
            row["key"] as String to MetaJson.decode("{\"value\":${row["value"]}}")["value"]
        }
        return InMemoryTurnWorld(WorldSnapshot(state = f.world.getState().copy(meta = meta),
            generals = f.world.listGenerals(), nations = f.world.listNations(), worldId = f.worldId))
    }

    @Test fun `actual flush persists tenure and receipt cold replay is no-op and other world stays empty`() {
        seed(1)
        seed(2)
        val f = ProvincialAppointmentExecutorTest.Fixture()
        val applied = assertIs<ProvincialAppointmentExecutor.Result.Appointed>(f.execute())
        assertTrue(rows(1).isEmpty())
        flush.flush(DatabaseHooks.toFlushPayload(f.world, f.recorder, f.world.consumeDirtyState()))
        val stored = rows(1)
        assertEquals(2, stored.size)
        assertTrue(rows(2).isEmpty())
        val reloaded = cold(f)
        val changes = ChangeRecorder()
        assertEquals(listOf(applied.tenure), CourtStateStore(reloaded, changes).tenures())
        val executor = ProvincialAppointmentExecutor(reloaded, changes)
        assertEquals(applied.copy(replay = true), executor.execute(WorldId(1), "request-1", f.offer, 10, 11) { _, _ ->
            error("cold receipt replay must not require fresh sources")
        })
        assertTrue(changes.kvDirty().isEmpty())
        flush.flush(DatabaseHooks.toFlushPayload(reloaded, changes, reloaded.consumeDirtyState()))
        assertEquals(stored, rows(1))
        assertTrue(rows(2).isEmpty())
    }

    @Test fun `approved zero cost persists appointment without changing nation or general rows`() {
        seed(3)
        val f = ProvincialAppointmentExecutorTest.Fixture(WorldId(3))
        val nation = f.world.listNations().single()
        jdbc.update("""INSERT INTO nation(world_id,id,name,color,gold,rice,meta)
            VALUES (?,?,?,?,?,?,?::jsonb)""", 3, nation.id, nation.name, nation.color,
            nation.gold, nation.rice, MetaJson.encode(nation.meta))
        for (general in f.world.listGenerals()) jdbc.update("""INSERT INTO general
            (world_id,id,user_id,name,nation_id,city_id,gold,rice,turn_time,meta)
            VALUES (?,?,?,?,?,?,?,?,?,?::jsonb)""", 3, general.id, general.userId, general.name,
            general.nationId, general.cityId, general.gold, general.rice,
            java.sql.Timestamp.from(general.turnTime), MetaJson.encode(general.meta))
        val nations = jdbc.queryForList("SELECT * FROM nation WHERE world_id=3 ORDER BY id")
        val generals = jdbc.queryForList("SELECT * FROM general WHERE world_id=3 ORDER BY id")
        assertIs<ProvincialAppointmentExecutor.Result.Appointed>(f.execute())
        assertTrue(f.recorder.generalPatches().isEmpty())
        assertTrue(f.recorder.nationPatches().isEmpty())
        flush.flush(DatabaseHooks.toFlushPayload(f.world, f.recorder, f.world.consumeDirtyState()))
        assertEquals(2, rows(3).size)
        assertEquals(1, CourtStateStore(cold(f), ChangeRecorder()).tenures().size)
        assertEquals(nations, jdbc.queryForList("SELECT * FROM nation WHERE world_id=3 ORDER BY id"))
        assertEquals(generals, jdbc.queryForList("SELECT * FROM general WHERE world_id=3 ORDER BY id"))
    }
}

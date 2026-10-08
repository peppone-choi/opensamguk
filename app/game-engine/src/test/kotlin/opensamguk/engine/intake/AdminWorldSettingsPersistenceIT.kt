package opensamguk.engine.intake

import opensamguk.common.wire.AdminWorldSetting
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.world.WorldId
import opensamguk.engine.boot.SeedBootstrap
import opensamguk.engine.boot.WorldSnapshotLoader
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.JdbcFlushExecutor
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminWorldSettingsPersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    @BeforeAll
    fun setUp() {
        assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — admin settings PostgreSQL roundtrip NOT verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        jdbc = JdbcTemplate(source)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        executor = JdbcFlushExecutor(NamedParameterJdbcTemplate(source), TransactionTemplate(DataSourceTransactionManager(source)))
    }

    @AfterAll
    fun tearDown() { if (this::postgres.isInitialized) postgres.stop() }

    @Test
    fun `actual admin producer disables block in both stores and cold reload`() = assertTransition(1, 1, 0)

    @Test
    fun `actual admin producer enables block in both stores and cold reload`() = assertTransition(3, 0, 1)

    @Test
    fun `KV failure rolls back the earlier world config update and writer version`() {
        seed(5, 1)
        seed(6, 1)
        val world = load(5)
        val recorder = ChangeRecorder()
        assertTrue(AdminWorldSettingsHandler(world, recorder).handle(blockCommand(0)).ok)
        val batch = payload(world, recorder)
        jdbc.execute("""
            CREATE FUNCTION reject_admin_block_kv() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'forced admin settings KV failure'; END; ${'$'}${'$'};
            CREATE TRIGGER reject_admin_block_kv BEFORE INSERT OR UPDATE ON game_kv
            FOR EACH ROW WHEN (NEW.world_id=5 AND NEW."table"='game_env'
                AND NEW.namespace='game_env' AND NEW.key='block_general_create')
            EXECUTE FUNCTION reject_admin_block_kv();
        """.trimIndent())
        try {
            val failure = assertFailsWith<DataAccessException> { executor.flush(batch) }
            assertTrue(failure.mostSpecificCause.message.orEmpty().contains("forced admin settings KV failure"))
        } finally {
            jdbc.execute("DROP TRIGGER reject_admin_block_kv ON game_kv; DROP FUNCTION reject_admin_block_kv()")
        }
        assertStored(5, 1)
        assertStored(6, 1)
        assertEquals(0L, jdbc.queryForObject("SELECT world_version FROM world_state WHERE id=5", Long::class.java))
        assertReloaded(5, 1)
        // Retry the original real producer payload, after the isolated database fault is removed.
        executor.flush(batch)
        assertStored(5, 0)
        assertReloaded(5, 0)
        assertStored(6, 1)
    }

    @Test
    fun `invalid admin batch and unrelated setting preserve durable block and other world`() {
        seed(7, 1)
        seed(8, 0)
        var world = load(7)
        var recorder = ChangeRecorder()
        val before = world.getState()
        assertFalse(AdminWorldSettingsHandler(world, recorder).handle(TurnDaemonCommand.AdminWorldSettings(
            settings = listOf(AdminWorldSetting("block_general_create", intValue = 0), AdminWorldSetting("unknown", intValue = 1)),
        )).ok)
        assertEquals(before, world.getState())
        assertTrue(recorder.kvDirty().isEmpty())
        executor.flush(payload(world, recorder))
        assertStored(7, 1)
        assertStored(8, 0)
        world = load(7)
        recorder = ChangeRecorder()
        assertTrue(AdminWorldSettingsHandler(world, recorder).handle(TurnDaemonCommand.AdminWorldSettings(
            settings = listOf(AdminWorldSetting("npcmode", intValue = 0)),
        )).ok)
        assertTrue(recorder.kvDirty().isEmpty())
        executor.flush(payload(world, recorder))
        assertStored(7, 1)
        assertStored(8, 0)
        assertReloaded(7, 1)
        assertEquals(0, (load(7).getState().config["npcmode"] as Number).toInt())
    }

    private fun assertTransition(id: Int, initial: Int, next: Int) {
        seed(id, initial)
        seed(id + 1, initial)
        val world = load(id)
        val recorder = ChangeRecorder()
        assertTrue(AdminWorldSettingsHandler(world, recorder).handle(blockCommand(next)).ok)
        executor.flush(payload(world, recorder))
        assertStored(id, next)
        assertStored(id + 1, initial)
        assertReloaded(id, next)
        assertReloaded(id + 1, initial)
    }

    private fun seed(id: Int, block: Int) {
        jdbc.update("""
            INSERT INTO world_state (id,scenario_code,current_year,current_month,tick_seconds,status,meta,config,start_time,world_version,writer_epoch)
            VALUES (?,'admin-settings-test',200,1,3600,'OPEN','{}'::jsonb,
                CAST(? AS jsonb),TIMESTAMPTZ '0200-01-01 00:00:00+00',0,1)
        """.trimIndent(), id, """{"worldFormat":"GENERAL_RETAINER_CAMPAIGN","block_general_create":$block,"maxgeneral":50,"npcmode":2}""")
        jdbc.update("""INSERT INTO game_kv(world_id,"table",namespace,key,value)
            VALUES (?,'game_env','game_env','block_general_create',CAST(? AS jsonb))""", id, block.toString())
        jdbc.update("""INSERT INTO ng_games(world_id,server_id,date,season,scenario,scenario_name,env)
            VALUES (?,?,now(),1,0,'Admin settings test','{}'::jsonb)""", id, "admin-settings-test-$id")
    }

    private fun load(id: Int) = InMemoryTurnWorld(WorldSnapshotLoader(jdbc,
        SeedBootstrap(seedEnabled = false, worldId = WorldId(id)), WorldId(id), snapshotValidator = {},
    ).buildSnapshot())

    private fun blockCommand(value: Int) = TurnDaemonCommand.AdminWorldSettings(
        settings = listOf(AdminWorldSetting("block_general_create", intValue = value)),
    )

    private fun payload(world: InMemoryTurnWorld, recorder: ChangeRecorder): FlushPayload =
        DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()).let { batch ->
            batch.copy(worldStateUpdate = batch.worldStateUpdate + mapOf(
                "expected_world_version" to world.getState().worldVersion,
                "writer_epoch" to world.getState().writerEpoch,
            ))
        }

    private fun assertStored(id: Int, expected: Int) {
        assertEquals(expected, jdbc.queryForObject("SELECT (config->>'block_general_create')::int FROM world_state WHERE id=?", Int::class.java, id))
        assertEquals("number", jdbc.queryForObject("SELECT jsonb_typeof(config->'block_general_create') FROM world_state WHERE id=?", String::class.java, id))
        assertEquals(expected, jdbc.queryForObject("""SELECT value::text::int FROM game_kv WHERE world_id=?
            AND "table"='game_env' AND namespace='game_env' AND key='block_general_create'""", Int::class.java, id))
        assertEquals("number", jdbc.queryForObject("""SELECT jsonb_typeof(value) FROM game_kv WHERE world_id=?
            AND "table"='game_env' AND namespace='game_env' AND key='block_general_create'""", String::class.java, id))
        assertEquals(50, jdbc.queryForObject("SELECT (config->>'maxgeneral')::int FROM world_state WHERE id=?", Int::class.java, id))
        assertEquals(3600, jdbc.queryForObject("SELECT tick_seconds FROM world_state WHERE id=?", Int::class.java, id))
    }

    private fun assertReloaded(id: Int, expected: Int) {
        val state = load(id).getState()
        assertEquals(expected, (state.config["block_general_create"] as Number).toInt())
        assertEquals(expected, (state.meta["block_general_create"] as Number).toInt())
    }
}

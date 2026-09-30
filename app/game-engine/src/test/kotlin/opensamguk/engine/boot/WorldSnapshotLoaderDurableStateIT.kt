package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.engine.turn.TurnFailureLedgerCodec
import opensamguk.engine.turn.TurnFailureState
import opensamguk.engine.turn.TurnFailureUnit
import opensamguk.infra.persistence.FlushPayload
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
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorldSnapshotLoaderDurableStateIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun setUp() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — durable loader IT skipped (not failed)",
        )
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        dataSource = DriverManagerDataSource().apply {
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
        jdbc = JdbcTemplate(dataSource)
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `restart snapshot restores GAME plock PHP starttime and canonical world start time`() {
        jdbc.update(
            """
            INSERT INTO world_state (
                id, scenario_code, current_year, current_month, tick_seconds, status, meta, config, start_time
            ) VALUES (
                1, 'durable_lock', 200, 1, 1800, 'PRE_OPEN',
                '{"startTime":"obsolete"}'::jsonb,
                '{"turnterm":30,"startTime":"obsolete","worldFormat":"GENERAL_RETAINER_CAMPAIGN"}'::jsonb,
                TIMESTAMPTZ '0200-01-01 00:00:00+00'
            )
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO game_kv (world_id, "table", namespace, key, value)
            VALUES
              (1, 'game_env', 'game_env', 'plock', '1'::jsonb),
              (1, 'game_env', 'game_env', 'starttime', '"0200-01-01 09:00:00"'::jsonb)
            """.trimIndent(),
        )

        val state = WorldSnapshotLoader(
            jdbc,
            SeedBootstrap(scenarioCode = "scenario_0", seedEnabled = false, worldId = WorldId(1)),
            WorldId(1),
            snapshotValidator = {},
        ).buildSnapshot().state

        assertEquals(1, (state.meta["plock"] as Number).toInt())
        assertEquals("0200-01-01 09:00:00", state.meta["starttime"])
        assertEquals("0200-01-01T00:00:00Z", state.meta["startTime"])
        assertEquals("0200-01-01T00:00:00Z", state.config["startTime"])
        assertEquals("PRE_OPEN", state.status)
        assertEquals(1_800, state.tickSeconds)
    }

    @Test
    fun `failed unit ledger persists with world flush and survives cold reload`() {
        val worldId = WorldId(2)
        jdbc.update(
            """
            INSERT INTO world_state (
                id, scenario_code, current_year, current_month, tick_seconds, status, meta, config, start_time
            ) VALUES (
                2, 'turn_failure_reload', 200, 1, 1800, 'OPEN', '{}'::jsonb,
                '{"worldFormat":"GENERAL_RETAINER_CAMPAIGN"}'::jsonb,
                TIMESTAMPTZ '0200-01-01 00:00:00+00'
            )
            """.trimIndent(),
        )
        try {
            val executor = JdbcFlushExecutor(
                NamedParameterJdbcTemplate(dataSource),
                TransactionTemplate(DataSourceTransactionManager(dataSource)),
            )
            val states: Map<TurnFailureUnit, TurnFailureState> =
                mapOf(TurnFailureUnit.General(7) to TurnFailureState(3, 2401))
            val encoded = checkNotNull(TurnFailureLedgerCodec.encode(states))
            fun flush(month: Int, update: Map<String, Any?> = emptyMap()) {
                executor.flush(FlushPayload(worldId, linkedMapOf<String, Any?>(
                    "id" to 2, "current_year" to 200, "current_month" to month,
                ).apply { putAll(update) }))
            }
            fun coldState() = WorldSnapshotLoader(
                jdbc, SeedBootstrap(scenarioCode = "scenario_0", seedEnabled = false, worldId = worldId),
                worldId, snapshotValidator = {},
            ).buildSnapshot().state

            flush(2, mapOf("turn_failure_ledger" to encoded))
            assertEquals(states, TurnFailureLedgerCodec.decode(coldState().meta))
            flush(3)
            assertEquals(states, TurnFailureLedgerCodec.decode(coldState().meta), "unrelated flush preserves ledger")
            flush(4, mapOf("turn_failure_ledger" to null))
            assertEquals(false, jdbc.queryForObject(
                "SELECT (meta -> 'turnFailureLedger') IS NOT NULL FROM world_state WHERE id = 2", Boolean::class.java,
            ))
            assertEquals(emptyMap(), TurnFailureLedgerCodec.decode(coldState().meta))
            assertEquals(false, TurnFailureLedgerCodec.META_KEY in coldState().meta)
        } finally {
            jdbc.update("DELETE FROM world_state WHERE id = 2")
        }
    }
}

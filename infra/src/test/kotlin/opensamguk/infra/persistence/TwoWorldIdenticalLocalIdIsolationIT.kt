package opensamguk.infra.persistence

import opensamguk.common.wire.TurnDaemonStreamKeys
import opensamguk.common.wire.commandResultKey
import opensamguk.common.world.WorldId
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * OPENSAM-129 / ARCH-S2-T4 — identical local IDs in two worlds never cross-contaminate
 * on flush (update/log/tombstone), SQL predicates, or Redis key identity.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoWorldIdenticalLocalIdIsolationIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres.start()
        val ds: DataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = NamedParameterJdbcTemplate(ds)
        executor = JdbcFlushExecutor(jdbc, TransactionTemplate(DataSourceTransactionManager(ds)))
        seedWorld(1)
        seedWorld(2)
        seedLocalIdPair(worldId = 1, nameSuffix = "A")
        seedLocalIdPair(worldId = 2, nameSuffix = "B")
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `world-scoped log and rank flush never leak into the other world`() {
        executor.flush(
            testFlushPayload(
                worldId = WorldId(1),
                worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 200, "current_month" to 1),
                logEntries = listOf(
                    LogRow(scope = "SYSTEM", category = "HISTORY", text = "only-world-1", year = 200, month = 1),
                ),
                rankWrites = listOf(
                    RankWrite(generalId = 10, type = "warnum", op = RankFlushOp.Set(42)),
                ),
            ),
        )

        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM log_entry WHERE world_id = 1 AND text = 'only-world-1'",
                MapSqlParameterSource(),
                Int::class.java,
            ),
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM log_entry WHERE world_id = 2 AND text = 'only-world-1'",
                MapSqlParameterSource(),
                Int::class.java,
            ),
        )
        assertEquals(
            42,
            jdbc.queryForObject(
                "SELECT value FROM rank_data WHERE world_id = 1 AND general_id = 10 AND type = 'warnum'",
                MapSqlParameterSource(),
                Int::class.java,
            ),
        )
        assertEquals(
            9,
            jdbc.queryForObject(
                "SELECT value FROM rank_data WHERE world_id = 2 AND general_id = 10 AND type = 'warnum'",
                MapSqlParameterSource(),
                Int::class.java,
            ),
        )
    }

    @Test
    fun `delete tombstone in world 2 does not remove world 1 same local id`() {
        executor.flush(
            testFlushPayload(
                worldId = WorldId(2),
                worldStateUpdate = linkedMapOf("id" to 2, "current_year" to 200, "current_month" to 1),
                deletedGenerals = listOf(10),
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM general WHERE world_id = 1 AND id = 10",
                MapSqlParameterSource(),
                Int::class.java,
            ),
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM general WHERE world_id = 2 AND id = 10",
                MapSqlParameterSource(),
                Int::class.java,
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM message WHERE world_id = 1 AND id = 1",
                MapSqlParameterSource(),
                Int::class.java,
            ),
        )
    }

    @Test
    fun `redis stream and result keys differ for identical profile across worlds`() {
        val a = TurnDaemonStreamKeys.of("che:scenario_2", WorldId(1))
        val b = TurnDaemonStreamKeys.of("che:scenario_2", WorldId(2))
        assertNotEquals(a.commandStream, b.commandStream)
        assertNotEquals(a.eventStream, b.eventStream)
        assertNotEquals(
            commandResultKey("che:scenario_2", WorldId(1), "req-same"),
            commandResultKey("che:scenario_2", WorldId(2), "req-same"),
        )
        assertTrue(a.commandStream.contains(":w1:"))
        assertTrue(b.commandStream.contains(":w2:"))
    }

    private fun seedWorld(id: Int) {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (:id, :sc, 200, 1, 60)",
            MapSqlParameterSource().addValue("id", id).addValue("sc", "sc-$id"),
        )
    }

    private fun seedLocalIdPair(worldId: Int, nameSuffix: String) {
        jdbc.update(
            "INSERT INTO nation (id, world_id, name, color) VALUES (1, :w, :n, '#00ff00')",
            MapSqlParameterSource().addValue("w", worldId).addValue("n", "n$nameSuffix"),
        )
        jdbc.update(
            """
            INSERT INTO city (
                id, world_id, name, level, nation_id, supply_state, front_state,
                pop, pop_max, agri, agri_max, comm, comm_max, secu, secu_max,
                trust, trade, def, def_max, wall, wall_max, region, meta
            ) VALUES (1, :w, :n, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, '{}'::jsonb)
            """.trimIndent(),
            MapSqlParameterSource().addValue("w", worldId).addValue("n", "c$nameSuffix"),
        )
        jdbc.update(
            """
            INSERT INTO general (
                id, world_id, name, nation_id, city_id, leadership, strength, intel, injury,
                experience, dedication, officer_level, gold, rice,
                crew, crew_type_id, train, atmos, troop_id,
                weapon_code, book_code, horse_code, item_code, npc_state,
                turn_time, last_turn, meta
            ) VALUES (
                10, :w, :n, 1, 1, 50, 50, 50, 0,
                0, 0, 1, 100, 100,
                0, 0, 0, 0, 0,
                'None', 'None', 'None', 'None', 0,
                now(), '{}'::jsonb, '{}'::jsonb
            )
            """.trimIndent(),
            MapSqlParameterSource().addValue("w", worldId).addValue("n", "g$nameSuffix"),
        )
        jdbc.update(
            "INSERT INTO rank_data (id, world_id, nation_id, general_id, type, value) VALUES (:id, :w, 1, 10, 'warnum', :v)",
            MapSqlParameterSource().addValue("id", worldId * 100).addValue("w", worldId).addValue("v", if (worldId == 1) 1 else 9),
        )
        jdbc.update(
            """
            INSERT INTO message (world_id, id, mailbox, type, src, dest, time, valid_until, message)
            VALUES (:w, 1, 10, 'private', 10, 10, now(), now() + interval '1 day', '{}'::jsonb)
            """.trimIndent(),
            MapSqlParameterSource().addValue("w", worldId),
        )
    }
}

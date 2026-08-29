package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.engine.turn.GeneralAccessLog
import opensamguk.engine.turn.Troop
import opensamguk.engine.turn.TurnDiplomacy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorldSnapshotLoaderWorldScopeIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate

    @BeforeAll
    fun setUp() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — world snapshot loader IT skipped (not failed)",
        )
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val dataSource: DataSource = DriverManagerDataSource().apply {
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
        insertTwoWorldsWithIdenticalLocalIds()
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `cold boot reload keeps each world owned cohort isolated with identical local ids`() {
        val first = loader(WorldId(1)).buildSnapshot()
        val second = loader(WorldId(2)).buildSnapshot()

        assertEquals("one", first.state.meta["world_marker"])
        assertEquals("two", second.state.meta["world_marker"])
        assertEquals(1, first.state.meta["serverCount"])
        assertEquals(2, second.state.meta["serverCount"])
        assertEquals(listOf("one-item"), first.state.meta["activeUniqueAuctionItems"])
        assertEquals(listOf("two-item"), second.state.meta["activeUniqueAuctionItems"])
        assertEquals(mapOf("scope" to 1), first.state.meta["storedUniqueItemCounts"])
        assertEquals(mapOf("scope" to 2), second.state.meta["storedUniqueItemCounts"])
        assertEquals(mapOf(100 to 1200.0), first.state.meta["inheritancePrevious"])
        assertEquals(mapOf(200 to 2400.0), second.state.meta["inheritancePrevious"])
        assertEquals(listOf(10), first.archivedNationIds)
        assertEquals(listOf(11), second.archivedNationIds)

        assertEquals(listOf("one-capital", "one-border"), first.nations.map { it.name })
        assertEquals(listOf("two-capital", "two-border"), second.nations.map { it.name })
        assertEquals("one", (first.nations.first().meta["nation_env"] as Map<*, *>)["scope_marker"])
        assertEquals("two", (second.nations.first().meta["nation_env"] as Map<*, *>)["scope_marker"])
        assertEquals(listOf(Troop(30, 10, "one-troop")), first.troops)
        assertEquals(listOf(Troop(30, 10, "two-troop")), second.troops)
        assertEquals(listOf(TurnDiplomacy(10, 11, 101, 3)), first.diplomacy)
        assertEquals(listOf(TurnDiplomacy(10, 11, 202, 6)), second.diplomacy)
        assertEquals(
            listOf(GeneralAccessLog(generalId = 30, userId = 100, refresh = 1, refreshTotal = 2, refreshScore = 3, refreshScoreTotal = 4)),
            first.accessLogs,
        )
        assertEquals(
            listOf(GeneralAccessLog(generalId = 30, userId = 200, refresh = 5, refreshTotal = 6, refreshScore = 7, refreshScoreTotal = 8)),
            second.accessLogs,
        )
    }

    private fun loader(worldId: WorldId): WorldSnapshotLoader = WorldSnapshotLoader(
        jdbc,
        SeedBootstrap(scenarioCode = "scenario_0", seedEnabled = false, worldId = worldId),
        worldId,
        snapshotValidator = {},
    )

    private fun insertTwoWorldsWithIdenticalLocalIds() {
        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, meta)
            VALUES
              (1, 'world_one', 200, 1, 3600, '{"serverId":"shared-server"}'::jsonb),
              (2, 'world_two', 201, 2, 1800, '{"serverId":"shared-server"}'::jsonb)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO ng_games (world_id, id, server_id, date, season, scenario, scenario_name, env)
            VALUES
              (1, 1, 'shared-server', now(), 1, 1010, 'one', '{}'::jsonb),
              (2, 1, 'shared-server', now(), 2, 2020, 'two', '{}'::jsonb),
              (2, 2, 'other-server', now(), 3, 3030, 'two-extra', '{}'::jsonb)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO nation (world_id, id, name, color)
            VALUES
              (1, 10, 'one-capital', '#111111'),
              (1, 11, 'one-border', '#111112'),
              (2, 10, 'two-capital', '#222221'),
              (2, 11, 'two-border', '#222222')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO city
                (world_id, id, name, level, nation_id, pop, pop_max, agri, agri_max, comm, comm_max,
                 secu, secu_max, def, def_max, wall, wall_max, region)
            VALUES
              (1, 20, 'one-city', 1, 10, 100, 1000, 10, 1000, 10, 1000, 10, 1000, 10, 1000, 10, 1000, 1),
              (2, 20, 'two-city', 1, 10, 100, 1000, 10, 1000, 10, 1000, 10, 1000, 10, 1000, 10, 1000, 1)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO general (world_id, id, name, nation_id, city_id, troop_id, turn_time, user_id)
            VALUES
              (1, 30, 'one-general', 10, 20, 30, now(), '100'),
              (2, 30, 'two-general', 10, 20, 30, now(), '200')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO troop (world_id, troop_leader, nation, name)
            VALUES
              (1, 30, 10, 'one-troop'),
              (2, 30, 10, 'two-troop')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO diplomacy (world_id, id, src_nation_id, dest_nation_id, state_code, term)
            VALUES
              (1, 1, 10, 11, 101, 3),
              (2, 1, 10, 11, 202, 6)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO nation_env (world_id, namespace, key, value)
            VALUES
              (1, 10, 'scope_marker', '"one"'::jsonb),
              (2, 10, 'scope_marker', '"two"'::jsonb)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO general_access_log
                (world_id, general_id, user_id, refresh, refresh_total, refresh_score, refresh_score_total)
            VALUES
              (1, 30, 100, 1, 2, 3, 4),
              (2, 30, 200, 5, 6, 7, 8)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO ng_old_nations (world_id, id, server_id, nation, data)
            VALUES
              (1, 1, 'shared-server', 10, '{}'::jsonb),
              (2, 1, 'shared-server', 11, '{}'::jsonb)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO ng_auction
                (world_id, id, type, finished, target, host_general_id, req_resource, open_date, close_date)
            VALUES
              (1, 1, 'uniqueItem', false, 'one-item', 30, 'inheritPoint', now(), now() + interval '1 day'),
              (2, 1, 'uniqueItem', false, 'two-item', 30, 'inheritPoint', now(), now() + interval '1 day')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO game_kv (world_id, "table", namespace, key, value)
            VALUES
              (1, 'game_env', 'game_env', 'world_marker', '"one"'::jsonb),
              (2, 'game_env', 'game_env', 'world_marker', '"two"'::jsonb),
              (1, 'unique_state', 'ut_scope', 'first', '1'::jsonb),
              (2, 'unique_state', 'ut_scope', 'first', '1'::jsonb),
              (2, 'unique_state', 'ut_scope', 'second', '1'::jsonb),
              (NULL, 'inheritance', 'inheritance_100', 'previous', '[1200,null]'::jsonb),
              (NULL, 'inheritance', 'inheritance_200', 'previous', '[2400,null]'::jsonb)
            """.trimIndent(),
        )
    }
}

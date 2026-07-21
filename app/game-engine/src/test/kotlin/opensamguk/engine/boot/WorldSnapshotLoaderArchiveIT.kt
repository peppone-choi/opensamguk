package opensamguk.engine.boot

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorldSnapshotLoaderArchiveIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var loader: WorldSnapshotLoader

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
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
        loader = WorldSnapshotLoader(
            jdbc,
            SeedBootstrap(
                scenarioCode = "scenario_0",
                seedEnabled = false,
                worldId = opensamguk.common.world.WorldId(1),
            ),
            opensamguk.common.world.WorldId(1),
        )

        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, meta)
            VALUES (1, 'scenario_0', 200, 1, 3600, '{"serverId":"current-server"}'::jsonb)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO ng_games (world_id, server_id, date, season, scenario, scenario_name, env)
            VALUES
              (1, 'current-server', '2026-01-01T00:00:00Z', 6, 1010, '영웅집결', '{"develcost":20,"refreshLimit":30000}'::jsonb),
              (1, 'newest-wrong-server', '2026-01-03T00:00:00Z', 1, 0, 'wrong', '{}'::jsonb)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO ng_old_nations (world_id, server_id, nation, data)
            VALUES
              (1, 'newest-wrong-server', 99, '{}'::jsonb),
              (1, 'current-server', 7, '{}'::jsonb)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO statistic
                (world_id, year, month, nation_count, nation_name, nation_hist, gen_count, personal_hist, special_hist, aux)
            VALUES
                (1, 199, 1, 3, '삼국', '삼국 기록', '9', '이전 개인', '이전 특기', '{"turn":1}'::jsonb),
                (1, 200, 1, 2, '양국', '양국 기록', '7', '최근 개인', '최근 특기', '{"turn":2}'::jsonb)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO log_entry (world_id, scope, category, year, month, text, nation_id)
            VALUES
                (1, 'NATION', 'HISTORY', 199, 1, '과거 기록', 7),
                (1, 'NATION', 'HISTORY', 200, 1, '최근 기록', 7)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO log_entry (world_id, scope, category, year, month, text, general_id)
            VALUES
                (1, 'GENERAL', 'HISTORY', 199, 1, '장수 과거 기록', 7),
                (1, 'GENERAL', 'HISTORY', 200, 1, '장수 최근 기록', 7)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO log_entry (world_id, scope, category, year, month, text)
            VALUES
                (1, 'SYSTEM', 'HISTORY', 199, 1, '세계 과거 기록'),
                (1, 'SYSTEM', 'ACTION', 200, 1, '세계 최근 기록')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO game_kv (world_id, "table", namespace, key, value)
            VALUES
              (1, 'unique_state', 'ut_che_의술_정력견혈산', 'first', '1'::jsonb),
              (1, 'unique_state', 'ut_che_의술_정력견혈산', 'second', '1'::jsonb),
              (NULL, 'inheritance', 'inheritance_77', 'previous', '[1200,null]'::jsonb),
              (NULL, 'inheritance', 'inheritance_77', 'active_action', '[9,{"source":"old"}]'::jsonb),
              (1, 'game_env', 'game_env', 'unrelated', '1'::jsonb),
              (1, 'game_env', 'game_env', 'develcost', '99'::jsonb)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO ng_auction
                (world_id, type, finished, target, host_general_id, req_resource, open_date, close_date)
            VALUES
              (1, 'uniqueItem', false, 'che_명마_07_백마', 1, 'inheritPoint', now(), now() + interval '1 day'),
              (1, 'uniqueItem', true, 'che_명마_07_기주마', 1, 'inheritPoint', now(), now() + interval '1 day')
            """.trimIndent(),
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `archived nation ids are scoped to the current server`() {
        val snapshot = loader.buildSnapshot()

        assertEquals("current-server", snapshot.serverId)
        assertEquals("current-server", snapshot.state.serverId)
        assertEquals(1, snapshot.state.meta["ngGameId"])
        assertEquals(6, snapshot.state.meta["season"])
        assertEquals(1010, snapshot.state.meta["scenario"])
        assertEquals("영웅집결", snapshot.state.meta["scenario_text"])
        assertEquals(30_000, snapshot.state.meta["refreshLimit"])
        assertEquals(99, snapshot.state.meta["develcost"])
        assertEquals(2, snapshot.state.meta["serverCount"])
        assertEquals(2, (snapshot.state.meta["statisticRows"] as List<*>).size)
        assertEquals(listOf("최근 기록", "과거 기록"), (snapshot.state.meta["nationHistory"] as Map<*, *>)[7])
        assertEquals(listOf("장수 최근 기록", "장수 과거 기록"), (snapshot.state.meta["generalHistory"] as Map<*, *>)[7])
        assertEquals(
            listOf("세계 최근 기록", "세계 과거 기록"),
            (snapshot.state.meta["globalLogs"] as List<Map<*, *>>).map { it["text"] },
        )
        assertEquals(listOf(7), snapshot.archivedNationIds)
        assertEquals(listOf("che_명마_07_백마"), snapshot.state.meta["activeUniqueAuctionItems"])
        assertEquals(mapOf("che_의술_정력견혈산" to 2), snapshot.state.meta["storedUniqueItemCounts"])
        assertEquals(mapOf(77 to 1200.0), snapshot.state.meta["inheritancePrevious"])
        val inheritancePoints = snapshot.state.meta["inheritancePoints"] as Map<*, *>
        val ownerPoints = inheritancePoints[77] as Map<*, *>
        assertEquals(listOf(9, mapOf("source" to "old")), ownerPoints["active_action"])
    }

    @Test
    fun `configured world only loads its world-scoped state cohorts`() {
        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
            VALUES (2, 'other_world', 201, 2, 1800)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO nation (id, world_id, name, color)
            VALUES
                (101, 1, 'configured nation', '#111111'),
                (201, 2, 'other nation', '#222222')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO city
                (id, world_id, name, level, nation_id, pop, pop_max, agri, agri_max, comm, comm_max,
                 secu, secu_max, def, def_max, wall, wall_max, region)
            VALUES
                (102, 1, 'configured city', 1, 101, 100, 1000, 10, 1000, 10, 1000, 10, 1000, 10, 1000, 10, 1000, 1),
                (202, 2, 'other city', 1, 201, 100, 1000, 10, 1000, 10, 1000, 10, 1000, 10, 1000, 10, 1000, 1)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO general (id, world_id, name, nation_id, city_id, turn_time)
            VALUES
                (103, 1, 'configured general', 101, 102, now()),
                (203, 2, 'other general', 201, 202, now())
            """.trimIndent(),
        )

        val snapshot = loader.buildSnapshot()

        assertEquals(1, snapshot.worldId.value)
        assertEquals(listOf(101), snapshot.nations.map { it.id })
        assertEquals(listOf(102), snapshot.cities.map { it.id })
        assertEquals(listOf(103), snapshot.generals.map { it.id })
    }
}

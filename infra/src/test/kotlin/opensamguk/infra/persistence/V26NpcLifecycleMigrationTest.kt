package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V26NpcLifecycleMigrationTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flyway: Flyway

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres.start()
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        jdbc = JdbcTemplate(dataSource)
        flyway = Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .target(MigrationVersion.fromVersion("25"))
            .load()
        flyway.migrate()
        seedLegacyWorld()
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .migrate()
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `V26 converts killturn and restores underage rows as deferred scenario events`() {
        val adult = jdbc.queryForMap(
            "SELECT (meta ->> 'killturn')::integer AS killturn, meta ->> 'killturn_unit' AS killturn_unit FROM general WHERE id = 1001",
        )
        assertEquals(216, (adult["killturn"] as Number).toInt())
        assertEquals("phase", adult["killturn_unit"])

        assertFalse(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM general WHERE id = 1002)", Boolean::class.java) == true)
        assertFalse(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM general WHERE id = 1003)", Boolean::class.java) == true)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM general_turn WHERE general_id = 1002", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM rank_data WHERE general_id = 1002", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM general_turn WHERE general_id = 1003", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM rank_data WHERE general_id = 1003", Int::class.java))
        assertEquals(11, jdbc.queryForObject("SELECT officer_level FROM general WHERE id = 1001", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT troop_id FROM general WHERE id = 1001", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM troop WHERE troop_leader = 1002", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM general_access_log WHERE general_id = 1002", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM general_owner WHERE general_id = 1002", Int::class.java))
        assertEquals(null, jdbc.queryForObject("SELECT general_id FROM select_pool WHERE unique_name = '미성년'", Int::class.java))
        assertEquals(
            2,
            jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM event
                 CROSS JOIN LATERAL jsonb_array_elements(action) action_row
                 WHERE priority = 1000
                   AND action_row ->> 0 = 'RegNPC'
                """.trimIndent(),
                Int::class.java,
            ),
        )
        val deferredNames = jdbc.queryForList(
            """
            SELECT action_row ->> 2
              FROM event
             CROSS JOIN LATERAL jsonb_array_elements(action) action_row
             WHERE priority = 1000
               AND action_row ->> 0 = 'RegNPC'
             ORDER BY action_row ->> 2
            """.trimIndent(),
            String::class.java,
        )
        assertEquals(listOf("소제1", "유협").sorted(), deferredNames.sorted())
        assertFalse(deferredNames.contains("유변"), "legacy 소제1 event must suppress duplicate canonical 유변")
        assertTrue(jdbc.queryForObject("SELECT (meta ->> 'gennum')::integer = 1 FROM nation WHERE id = 1", Boolean::class.java) == true)
    }

    private fun seedLegacyWorld() {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'scenario_1010', 181, 1, 3600)",
        )
        jdbc.update("INSERT INTO nation (id, name, color, meta) VALUES (1, '한', '#fff', '{\"gennum\":2}')")
        jdbc.update(
            """
            INSERT INTO general
                (id, name, nation_id, city_id, npc_state, affinity, born_year, dead_year, picture,
                 leadership, strength, intel, officer_level, turn_time, age, personal_code,
                 special_code, special2_code, meta)
            VALUES
                (1001, 'ⓝ성인', 1, 3, 2, 42, 160, 220, 'adult.png',
                 70, 60, 50, 11, now(), 21, 'che_유지', 'None', 'che_신산', '{"killturn":72}'),
                (1002, '소제1', 1, 3, 2, 1, 168, 190, '1001',
                 20, 11, 48, 0, now(), 13, 'che_유지', 'None', 'None', '{"killturn":108}'),
                (1003, '헌제', 1, 3, 2, 1, 170, 250, '1002',
                 17, 13, 61, 0, now(), 11, 'che_안전', 'None', 'None', '{"killturn":828,"npcmsg":"한 왕실을 구해줄 이는 진정 없는 것인가..."}')
            """.trimIndent(),
        )
        jdbc.update("INSERT INTO troop (troop_leader, nation, name) VALUES (1002, 1, '미성년부대')")
        jdbc.update(
            "UPDATE general SET troop_id = (SELECT id FROM troop WHERE troop_leader = 1002) WHERE id IN (1001, 1002)",
        )
        jdbc.update("INSERT INTO general_access_log (general_id, user_id) VALUES (1002, 77)")
        jdbc.update("INSERT INTO general_owner (general_id, user_id) VALUES (1002, 77)")
        jdbc.update("INSERT INTO select_pool (unique_name, general_id, info) VALUES ('미성년', 1002, '{}')")
        jdbc.update(
            "INSERT INTO event (id, target_code, priority, condition, action) VALUES (500, 'Month', 0, '[]', '[]')",
        )
        jdbc.update(
            """
            INSERT INTO event (id, target_code, priority, condition, action)
            VALUES (501, 'Month', 1000, '[]', '[["RegNPC", 1, "소제1"]]')
            """.trimIndent(),
        )
        jdbc.update("INSERT INTO general_turn (general_id, turn_idx, action_code) VALUES (1002, 0, '휴식')")
        jdbc.update("INSERT INTO rank_data (general_id, type) VALUES (1002, 'leadership')")
        jdbc.update("INSERT INTO general_turn (general_id, turn_idx, action_code) VALUES (1003, 0, '휴식')")
        jdbc.update("INSERT INTO rank_data (general_id, type) VALUES (1003, 'leadership')")
    }
}

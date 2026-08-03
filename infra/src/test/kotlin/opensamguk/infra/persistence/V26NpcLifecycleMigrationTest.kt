package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V26NpcLifecycleMigrationTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate

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
    }

    @BeforeEach
    fun resetDatabase() {
        jdbc.execute("DROP SCHEMA public CASCADE")
        jdbc.execute("CREATE SCHEMA public")
        migrateTo25()
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `V26 converts killturn and restores underage rows as deferred scenario events`() {
        seedLegacyWorld()
        migrateTo26()
        val adult = jdbc.queryForMap(
            "SELECT (meta ->> 'killturn')::integer AS killturn, meta ->> 'killturn_unit' AS killturn_unit FROM general WHERE id = 1001",
        )
        assertEquals(216, (adult["killturn"] as Number).toInt())
        assertEquals("phase", adult["killturn_unit"])

        assertFalse(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM general WHERE id = 1002)", Boolean::class.java) == true)
        assertFalse(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM general WHERE id = 1003)", Boolean::class.java) == true)
        assertFalse(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM general WHERE id = 1004)", Boolean::class.java) == true)
        assertFalse(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM general WHERE id = 1005)", Boolean::class.java) == true)
        assertTrue(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM general WHERE id = 1001)", Boolean::class.java) == true)
        assertTrue(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM general WHERE id = 1006)", Boolean::class.java) == true)
        assertTrue(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM general WHERE id = 1007)", Boolean::class.java) == true)
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
            4,
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
        assertEquals(listOf("소제1", "헌제", "RTK출생변경", "RTK성인변경").sorted(), deferredNames.sorted())
        val rtkDeferred = jdbc.queryForMap(
            """
            SELECT e.condition ->> 2 AS scheduled_year,
                   action_row ->> 10 AS source_birth_year,
                   action_row ->> 17 AS source_appearance_year,
                   action_row ->> 18 AS source_officer_number
              FROM event e
             CROSS JOIN LATERAL jsonb_array_elements(e.action) action_row
             WHERE action_row ->> 2 = 'RTK출생변경'
            """.trimIndent(),
        )
        assertEquals("200", rtkDeferred["scheduled_year"])
        assertEquals("190", rtkDeferred["source_birth_year"])
        assertEquals("200", rtkDeferred["source_appearance_year"])
        assertEquals("17001", rtkDeferred["source_officer_number"])
        val formerAdultDeferred = jdbc.queryForMap(
            """
            SELECT e.condition ->> 2 AS scheduled_year,
                   action_row ->> 10 AS source_birth_year,
                   action_row ->> 17 AS source_appearance_year,
                   action_row ->> 18 AS source_officer_number,
                   action_row ->> 25 AS source_legacy_active_at_start
              FROM event e
             CROSS JOIN LATERAL jsonb_array_elements(e.action) action_row
             WHERE action_row ->> 2 = 'RTK성인변경'
            """.trimIndent(),
        )
        assertEquals("200", formerAdultDeferred["scheduled_year"])
        assertEquals("190", formerAdultDeferred["source_birth_year"])
        assertEquals("200", formerAdultDeferred["source_appearance_year"])
        assertEquals("17002", formerAdultDeferred["source_officer_number"])
        assertEquals("true", formerAdultDeferred["source_legacy_active_at_start"])
        assertTrue(jdbc.queryForObject("SELECT (meta ->> 'gennum')::integer = 3 FROM nation WHERE id = 1", Boolean::class.java) == true)
    }

    @Test
    fun `V26 migrates an external-only effective scenario`(@TempDir scenarioDir: Path) {
        Files.writeString(
            scenarioDir.resolve("scenario_v26_external_only.json"),
            scenarioJson("외부전용", bornYear = 168),
            StandardCharsets.UTF_8,
        )
        seedSingleLegacyWorld("scenario_v26_external_only", "외부전용", bornYear = 168)

        migrateTo26(scenarioDir)

        assertFalse(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM general WHERE id = 2001)", Boolean::class.java) == true)
        assertEquals(182, deferredYear("외부전용"))
    }

    @Test
    fun `V26 gives a same-name external scenario precedence over the bundled scenario`(@TempDir scenarioDir: Path) {
        Files.writeString(
            scenarioDir.resolve("scenario_v26_npc_lifecycle.json"),
            scenarioJson("소제1", bornYear = 168, appearanceYear = 205),
            StandardCharsets.UTF_8,
        )
        seedSingleLegacyWorld("scenario_v26_npc_lifecycle", "소제1", bornYear = 168)

        migrateTo26(scenarioDir)

        assertEquals(205, deferredYear("소제1"))
    }

    @Test
    fun `V26 fails closed for an NPC world without an effective scenario`() {
        seedSingleLegacyWorld("scenario_v26_missing", "누락시나리오", bornYear = 160)

        assertFailsWith<FlywayException> { migrateTo26() }

        assertEquals(72, killturn(2001))
        assertTrue(killturnUnitIsAbsent(2001))
        assertEquals(0, v26HistoryCount())
        assertEquals(0, eventCount())
    }

    @Test
    fun `V26 rolls back when a selected external override is malformed`(@TempDir scenarioDir: Path) {
        Files.writeString(scenarioDir.resolve("scenario_v26_npc_lifecycle.json"), "{ malformed", StandardCharsets.UTF_8)
        seedSingleLegacyWorld("scenario_v26_npc_lifecycle", "소제1", bornYear = 168)

        assertFailsWith<FlywayException> { migrateTo26(scenarioDir) }

        assertEquals(72, killturn(2001))
        assertTrue(killturnUnitIsAbsent(2001))
        assertEquals(0, v26HistoryCount())
        assertEquals(0, eventCount())
    }

    private fun migrateTo25() {
        migrateTo("25")
    }

    private fun migrateTo26(scenarioDir: Path? = null) {
        migrateTo("26", scenarioDir?.toString().orEmpty())
    }

    private fun migrateTo(target: String, scenarioDir: String = "") {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .placeholders(mapOf("scenario_dir" to scenarioDir))
            .target(MigrationVersion.fromVersion(target))
            .load()
            .migrate()
    }

    private fun seedSingleLegacyWorld(scenarioCode: String, name: String, bornYear: Int) {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, ?, 181, 1, 3600)",
            scenarioCode,
        )
        jdbc.update("INSERT INTO nation (id, name, color, meta) VALUES (1, '한', '#fff', '{\"gennum\":1}')")
        jdbc.update(
            """
            INSERT INTO general
                (id, name, nation_id, city_id, npc_state, affinity, born_year, dead_year, picture,
                 leadership, strength, intel, officer_level, turn_time, age, personal_code,
                 special_code, special2_code, meta)
            VALUES
                (2001, ?, 1, 3, 2, 1, ?, 240, 'external.png',
                 20, 11, 48, 0, now(), 21, 'che_유지', 'None', 'None', '{"killturn":72}')
            """.trimIndent(),
            name,
            bornYear,
        )
    }

    private fun deferredYear(name: String): Int = jdbc.queryForObject(
        """
        SELECT (e.condition ->> 2)::integer
          FROM event e
         CROSS JOIN LATERAL jsonb_array_elements(e.action) action_row
         WHERE action_row ->> 2 = ?
        """.trimIndent(),
        Int::class.java,
        name,
    )!!

    private fun killturn(generalId: Int): Int = jdbc.queryForObject(
        "SELECT (meta ->> 'killturn')::integer FROM general WHERE id = ?",
        Int::class.java,
        generalId,
    )!!

    private fun killturnUnitIsAbsent(generalId: Int): Boolean = jdbc.queryForObject(
        "SELECT NOT jsonb_exists(meta, 'killturn_unit') FROM general WHERE id = ?",
        Boolean::class.java,
        generalId,
    ) == true

    private fun v26HistoryCount(): Int = jdbc.queryForObject(
        "SELECT count(*) FROM flyway_schema_history WHERE version = '26'",
        Int::class.java,
    )!!

    private fun eventCount(): Int = jdbc.queryForObject("SELECT count(*) FROM event", Int::class.java)!!

    private fun scenarioJson(name: String, bornYear: Int, appearanceYear: Int? = null): String {
        val tuple = if (appearanceYear == null) {
            "[1,\"$name\",null,1,null,20,11,48,0,$bornYear,240,\"유지\",null,null]"
        } else {
            "[1,\"$name\",null,1,null,20,11,48,0,$bornYear,240,\"유지\",null,null,77,88,$appearanceYear,17001,\"female\",50,30,300,\"왕도\",false,true]"
        }
        return """{"title":"V26 external","startYear":181,"map":{},"const":{},"nation":[],"general":[$tuple],"general_ex":[],"general_neutral":[],"diplomacy":[]}"""
    }

    private fun seedLegacyWorld() {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'scenario_v26_npc_lifecycle', 181, 1, 3600)",
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
                 17, 13, 61, 0, now(), 11, 'che_안전', 'None', 'None', '{"killturn":828,"npcmsg":"한 왕실을 구해줄 이는 진정 없는 것인가..."}'),
                (1004, 'RTK출생변경', 1, 3, 2, 1, 168, 240, '1003',
                 40, 41, 42, 0, now(), 13, 'che_유지', 'None', 'None', '{"killturn":708}'),
                (1005, 'RTK성인변경', 1, 3, 2, 1, 160, 240, '1004',
                 40, 41, 42, 0, now(), 21, 'che_유지', 'None', 'None', '{"killturn":708}'),
                (1006, 'RTK동명이인', 1, 3, 2, 1, 160, 240, '1005',
                 40, 41, 42, 0, now(), 21, 'che_유지', 'None', 'None', '{"killturn":708}'),
                (1007, 'RTK동명이인', 1, 3, 2, 1, 159, 240, '1006',
                 40, 41, 42, 0, now(), 22, 'che_유지', 'None', 'None', '{"killturn":708}')
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
        jdbc.update("INSERT INTO general_turn (general_id, turn_idx, action_code) VALUES (1002, 0, '휴식')")
        jdbc.update("INSERT INTO rank_data (general_id, type) VALUES (1002, 'leadership')")
        jdbc.update("INSERT INTO general_turn (general_id, turn_idx, action_code) VALUES (1003, 0, '휴식')")
        jdbc.update("INSERT INTO rank_data (general_id, type) VALUES (1003, 'leadership')")
    }
}

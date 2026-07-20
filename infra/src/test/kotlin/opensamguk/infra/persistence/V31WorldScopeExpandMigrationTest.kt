package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class V31WorldScopeExpandMigrationTest {

    @Test
    fun `V31 expands a pristine schema without inventing a world identity`() {
        assumeDocker()
        withPostgres { postgres ->
            val jdbc = migrateTo30(postgres)

            migrateV31(postgres)

            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM world_state", Int::class.java))
            cohortTables.forEach { table ->
                assertWorldColumn(jdbc, table)
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))
            }
            assertEquals(1, appliedV31Count(jdbc), "V31 must apply to a pristine schema")
        }
    }

    @Test
    fun `V31 backfills the exactly-one positive canonical world and installs scoped contracts`() {
        assumeDocker()
        withPostgres { postgres ->
            val jdbc = migrateTo30(postgres)
            seedWorld(jdbc, 701)
            seedCohort(jdbc)

            migrateV31(postgres)

            cohortTables.forEach { table ->
                assertWorldColumn(jdbc, table)
                assertEquals(
                    listOf(701),
                    jdbc.queryForList("SELECT DISTINCT world_id FROM $table ORDER BY world_id", Int::class.java),
                    "$table must be backfilled only from world_state.id",
                )
                assertWorldForeignKey(jdbc, table)
            }
            assertRootScopedKeys(jdbc)
            assertTurnScopedKeys(jdbc)
            assertScopedTurnUniqueness(jdbc)
        }
    }

    @Test
    fun `V31 rejects zero canonical worlds when legacy ng games exists and rolls back DDL`() {
        assumeDocker()
        withPostgres { postgres ->
            val jdbc = migrateTo30(postgres)
            jdbc.update(
                """
                INSERT INTO ng_games (server_id, date, season, scenario, scenario_name, env)
                VALUES ('legacy-server-id', now(), 1, 1010, 'legacy scenario', '{}'::jsonb)
                """.trimIndent(),
            )

            val failure = assertFailsWith<FlywayException> { migrateV31(postgres) }

            assertTrue(failure.message.orEmpty().contains("V31"), "failure must identify the V31 preflight")
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM world_state", Int::class.java))
            assertV31DdlRolledBack(jdbc)
        }
    }

    @Test
    fun `V31 rejects orphaned cohort rows without a canonical world and rolls back DDL`() {
        assumeDocker()
        withPostgres { postgres ->
            val jdbc = migrateTo30(postgres)
            seedCohort(jdbc)

            assertFailsWith<FlywayException> { migrateV31(postgres) }

            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM world_state", Int::class.java))
            assertV31DdlRolledBack(jdbc)
        }
    }

    @Test
    fun `V31 rejects multiple canonical worlds without selecting either one`() {
        assumeDocker()
        withPostgres { postgres ->
            val jdbc = migrateTo30(postgres)
            seedWorld(jdbc, 701)
            seedWorld(jdbc, 702)
            seedCohort(jdbc)

            assertFailsWith<FlywayException> { migrateV31(postgres) }

            assertEquals(
                listOf(701, 702),
                jdbc.queryForList("SELECT id FROM world_state ORDER BY id", Int::class.java),
            )
            assertV31DdlRolledBack(jdbc)
        }
    }

    @Test
    fun `V31 rejects a non-positive canonical world source and rolls back DDL`() {
        assumeDocker()
        withPostgres { postgres ->
            val jdbc = migrateTo30(postgres)
            seedWorld(jdbc, 0)

            assertFailsWith<FlywayException> { migrateV31(postgres) }

            assertEquals(listOf(0), jdbc.queryForList("SELECT id FROM world_state", Int::class.java))
            assertV31DdlRolledBack(jdbc)
        }
    }

    @Test
    fun `V31 lock statement enumerates every classified C1 physical relation before classification`() {
        val migrationSql = requireNotNull(
            javaClass.classLoader.getResource("db/migration/V31__world_scope_expand.sql"),
        ).readText()
        val lockClause = requireNotNull(
            Regex(
                """LOCK\s+TABLE\s+(.*?)\s+IN\s+SHARE\s+ROW\s+EXCLUSIVE\s+MODE\s*;""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(migrationSql),
        ) { "V31 must acquire its relation locks in one explicit LOCK TABLE statement" }.groupValues[1]

        val parsedRelations = lockClause
            .split(',')
            .map { it.trim().lowercase() }
            .filter(String::isNotBlank)
        val expectedRelations = (listOf("world_state") + c1PhysicalRelations).map { it.lowercase() }

        assertEquals(expectedRelations.toSet(), parsedRelations.toSet(), "V31 lock list must classify exactly the C1 relations")
        assertEquals(parsedRelations.size, parsedRelations.distinct().size, "V31 lock list must not duplicate a physical relation")
    }

    @Test
    fun `V31 waits then fails closed when a legacy writer commits before classification`() {
        assumeDocker()
        withPostgres { postgres ->
            val jdbc = migrateTo30(postgres)
            val writerDataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            writerDataSource.connection.use { writer ->
                writer.autoCommit = false
                writer.prepareStatement(
                    """
                    INSERT INTO ng_games (server_id, date, season, scenario, scenario_name, env)
                    VALUES ('race-writer', now(), 1, 1010, 'legacy scenario', '{}'::jsonb)
                    """.trimIndent(),
                ).use { it.executeUpdate() }

                val executor = Executors.newSingleThreadExecutor()
                val migration = executor.submit<Unit> { migrateV31(postgres) }
                var writerCommitted = false
                try {
                    assertTrue(
                        awaitWaitingRelationLock(jdbc, "ng_games"),
                        "V31 must wait on ng_games rather than classify it without a conflicting table lock",
                    )
                    assertFalse(migration.isDone, "V31 must still be blocked while the conflicting lock is held")
                    writer.commit()
                    writerCommitted = true

                    val failure = assertFailsWith<ExecutionException> { migration.get(20, TimeUnit.SECONDS) }
                    assertTrue(failure.cause is FlywayException, "the committed legacy row must fail the V31 preflight")
                } finally {
                    if (!writerCommitted) writer.commit()
                    if (!migration.isDone) runCatching { migration.get(20, TimeUnit.SECONDS) }
                    executor.shutdownNow()
                }

                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ng_games", Int::class.java))
                assertV31DdlRolledBack(jdbc)
            }
        }
    }

    @Test
    fun `V31 post DDL failure rolls back added columns constraints data and Flyway history`() {
        assumeDocker()
        withPostgres { postgres ->
            val jdbc = migrateTo30(postgres)
            seedWorld(jdbc, 701)
            seedCohort(jdbc)
            jdbc.execute("ALTER TABLE nation ADD CONSTRAINT nation_world_id_fkey CHECK (true)")

            assertFailsWith<FlywayException> { migrateV31(postgres) }

            assertV31DdlRolledBack(jdbc)
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM nation", Int::class.java))
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM city", Int::class.java))
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM general", Int::class.java))
            assertEquals(
                1,
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_constraint WHERE conrelid = 'nation'::regclass AND conname = 'nation_world_id_fkey'",
                    Int::class.java,
                ),
                "the pre-existing failure fixture must remain after V31 rolls back",
            )
        }
    }

    @Test
    fun `V31 failure after turn unique changes restores legacy turn uniques and all DDL`() {
        assumeDocker()
        withPostgres { postgres ->
            val jdbc = migrateTo30(postgres)
            seedWorld(jdbc, 701)
            seedCohort(jdbc)
            jdbc.execute("ALTER TABLE nation_turn ADD CONSTRAINT nation_turn_world_id_fkey CHECK (true)")

            assertFailsWith<FlywayException> { migrateV31(postgres) }

            assertV31DdlRolledBack(jdbc)
            assertLegacyTurnUniqueness(jdbc)
        }
    }

    private fun assumeDocker() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — V31 migration IT skipped (not failed)",
        )
    }

    private fun withPostgres(block: (PostgreSQLContainer<*>) -> Unit) {
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()
            block(postgres)
        }
    }

    private fun migrateTo30(postgres: PostgreSQLContainer<*>): JdbcTemplate {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(sessionLockConfig)
            .target(MigrationVersion.fromVersion("30"))
            .load()
            .migrate()
        return JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    private fun migrateV31(postgres: PostgreSQLContainer<*>) {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(sessionLockConfig)
            .load()
            .migrate()
    }

    private fun seedWorld(jdbc: JdbcTemplate, id: Int) {
        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, meta)
            VALUES (?, 'scenario_1010', 181, 1, 3600, '{}'::jsonb)
            """.trimIndent(),
            id,
        )
    }

    private fun seedCohort(jdbc: JdbcTemplate) {
        jdbc.update("INSERT INTO nation (id, name, color) VALUES (11, '한', '#ffffff')")
        jdbc.update(
            """
            INSERT INTO city
                (id, name, level, pop, pop_max, agri, agri_max, comm, comm_max,
                 secu, secu_max, def, def_max, wall, wall_max, region)
            VALUES (21, '낙양', 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
            """.trimIndent(),
        )
        jdbc.update("INSERT INTO general (id, name, turn_time) VALUES (31, '장수', now())")
        jdbc.update("INSERT INTO general_turn (general_id, turn_idx, action_code) VALUES (31, 0, '휴식')")
        jdbc.update("INSERT INTO nation_turn (nation_id, officer_level, turn_idx, action_code) VALUES (11, 12, 0, '휴식')")
    }

    private fun assertWorldColumn(jdbc: JdbcTemplate, table: String) {
        assertTrue(hasWorldColumn(jdbc, table), "$table must have world_id")
        val column = jdbc.queryForMap(
            """
            SELECT data_type, is_nullable, column_default
              FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = ? AND column_name = 'world_id'
            """.trimIndent(),
            table,
        )
        assertEquals("integer", column["data_type"], "$table.world_id must be INTEGER")
        assertEquals("NO", column["is_nullable"], "$table.world_id must be NOT NULL")
        assertEquals(null, column["column_default"], "$table.world_id must not have a fallback default")
    }

    private fun assertWorldForeignKey(jdbc: JdbcTemplate, table: String) {
        assertConstraint(
            jdbc = jdbc,
            table = table,
            type = "f",
            expected = "FOREIGN KEY (world_id) REFERENCES world_state(id)",
        )
    }

    private fun assertRootScopedKeys(jdbc: JdbcTemplate) {
        for (table in rootTables) {
            assertConstraint(jdbc, table, "p", "PRIMARY KEY (id)")
            assertConstraint(jdbc, table, "u", "UNIQUE (world_id, id)")
        }
    }

    private fun assertTurnScopedKeys(jdbc: JdbcTemplate) {
        assertConstraint(jdbc, "general_turn", "u", "UNIQUE (world_id, general_id, turn_idx)")
        assertConstraint(jdbc, "nation_turn", "u", "UNIQUE (world_id, nation_id, officer_level, turn_idx)")
        assertFalse(
            constraintDefinitions(jdbc, "general_turn", "u").any { normalize(it) == "UNIQUE (general_id, turn_idx)" },
            "general_turn must not retain its unqualified unique key",
        )
        assertFalse(
            constraintDefinitions(jdbc, "nation_turn", "u").any { normalize(it) == "UNIQUE (nation_id, officer_level, turn_idx)" },
            "nation_turn must not retain its unqualified unique key",
        )
    }

    private fun assertScopedTurnUniqueness(jdbc: JdbcTemplate) {
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                """
                INSERT INTO general_turn (world_id, general_id, turn_idx, action_code)
                VALUES (701, 31, 0, '휴식')
                """.trimIndent(),
            )
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                """
                INSERT INTO nation_turn (world_id, nation_id, officer_level, turn_idx, action_code)
                VALUES (701, 11, 12, 0, '휴식')
                """.trimIndent(),
            )
        }
    }

    private fun assertV31DdlRolledBack(jdbc: JdbcTemplate) {
        cohortTables.forEach { table ->
            assertFalse(hasWorldColumn(jdbc, table), "$table.world_id DDL must roll back")
        }
        assertLegacyTurnUniqueness(jdbc)
        assertEquals(0, appliedV31Count(jdbc), "failed transactional V31 must not enter Flyway history")
    }

    private fun assertLegacyTurnUniqueness(jdbc: JdbcTemplate) {
        val generalTurn = constraintDefinitions(jdbc, "general_turn", "u").map(::normalize)
        val nationTurn = constraintDefinitions(jdbc, "nation_turn", "u").map(::normalize)
        assertTrue(generalTurn.contains("UNIQUE (general_id, turn_idx)"), "general_turn legacy unique must roll back")
        assertTrue(nationTurn.contains("UNIQUE (nation_id, officer_level, turn_idx)"), "nation_turn legacy unique must roll back")
        assertFalse(generalTurn.contains("UNIQUE (world_id, general_id, turn_idx)"), "general_turn scoped unique must roll back")
        assertFalse(nationTurn.contains("UNIQUE (world_id, nation_id, officer_level, turn_idx)"), "nation_turn scoped unique must roll back")
    }

    private fun hasWorldColumn(jdbc: JdbcTemplate, table: String): Boolean =
        (jdbc.queryForObject(
            """
            SELECT count(*)
              FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = ? AND column_name = 'world_id'
            """.trimIndent(),
            Int::class.java,
            table,
        ) ?: 0) == 1

    private fun appliedV31Count(jdbc: JdbcTemplate): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '31' AND success",
            Int::class.java,
        ) ?: 0

    private fun awaitWaitingRelationLock(jdbc: JdbcTemplate, relation: String): Boolean {
        repeat(80) {
            val waiting = jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM pg_locks lock
                  JOIN pg_class relation_class ON relation_class.oid = lock.relation
                 WHERE NOT lock.granted
                   AND relation_class.relname = ?
                """.trimIndent(),
                Int::class.java,
                relation,
            ) ?: 0
            if (waiting > 0) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun assertConstraint(jdbc: JdbcTemplate, table: String, type: String, expected: String) {
        val definitions = constraintDefinitions(jdbc, table, type)
        assertTrue(
            definitions.any { normalize(it) == expected },
            "$table must have $expected; actual=$definitions",
        )
    }

    private fun constraintDefinitions(jdbc: JdbcTemplate, table: String, type: String): List<String> =
        jdbc.queryForList(
            """
            SELECT pg_get_constraintdef(c.oid)
              FROM pg_constraint c
             WHERE c.conrelid = ?::regclass AND c.contype = ?
            """.trimIndent(),
            String::class.java,
            table,
            type,
        )

    private fun normalize(definition: String): String = definition.replace(Regex("\\s+"), " ").trim()

    private companion object {
        private val sessionLockConfig = mapOf("flyway.postgresql.transactional.lock" to "false")
        private val rootTables = listOf("nation", "city", "general")
        private val cohortTables = rootTables + listOf("general_turn", "nation_turn")
        private val c1PhysicalRelations = listOf(
            "nation",
            "city",
            "general",
            "troop",
            "general_turn",
            "nation_turn",
            "diplomacy",
            "diplomacy_letter",
            "rank_data",
            "hall",
            "ng_games",
            "ng_old_nations",
            "ng_old_generals",
            "yearbook_history",
            "event",
            "log_entry",
            "board_post",
            "board_comment",
            "vote_poll",
            "vote",
            "vote_comment",
            "nation_env",
            "message",
            "ng_betting",
            "ng_auction",
            "ng_auction_bid",
            "statistic",
            "select_pool",
            "general_access_log",
            "emperior",
        )
    }
}

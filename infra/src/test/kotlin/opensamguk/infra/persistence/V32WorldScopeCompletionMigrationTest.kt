package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V32WorldScopeCompletionMigrationTest {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate

    @BeforeAll
    fun startPostgres() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — V32 migration IT skipped (not failed)",
        )
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @AfterAll
    fun stopPostgres() {
        if (::postgres.isInitialized) postgres.stop()
    }

    @BeforeEach
    fun resetSchema() {
        jdbc.execute("DROP SCHEMA public CASCADE")
        jdbc.execute("CREATE SCHEMA public")
    }

    @Test
    fun `V32 inventory is exhaustive and every world-owned table has a strict scoped key`() {
        migrateTo31()

        migrateV32()

        val physicalTables = jdbc.queryForList(
            """
            SELECT table_name
              FROM information_schema.tables
             WHERE table_schema = 'public'
               AND table_type = 'BASE TABLE'
               AND table_name <> 'flyway_schema_history'
             ORDER BY table_name
            """.trimIndent(),
            String::class.java,
        ).toSet()
        assertEquals(
            (setOf("world_state", "game_kv") + worldOwnedTables + globalAllowlist).toSortedSet(),
            physicalTables.toSortedSet(),
            "every current physical table must be classified exactly once",
        )

        worldOwnedTables.forEach { table ->
            assertWorldColumn(table, nullable = false)
            assertWorldForeignKey(table)
            assertTrue(
                normalize(primaryKeyDefinition(table)).startsWith("PRIMARY KEY (world_id,"),
                "$table primary key must be world-leading; actual=${primaryKeyDefinition(table)}",
            )
            assertEveryIndexWorldLeading(table)
        }
        globalAllowlist.forEach { table ->
            assertFalse(hasWorldColumn(table), "$table is explicitly global and must remain unscoped")
        }
        assertWorldColumn("game_kv", nullable = true)
        assertWorldForeignKey("game_kv")
        assertConstraint("world_state", "c", "CHECK ((id > 0))")
        assertEquals(
            0,
            jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal", Int::class.java),
            "strict world scoping must not install compatibility triggers",
        )
        assertEquals(1, appliedV32Count())
        assertScopedConstraintMatrix()
        serialIdentityColumns.forEach { (table, column) ->
            val default = jdbc.queryForObject(
                """
                SELECT column_default
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """.trimIndent(),
                String::class.java,
                table,
                column,
            )
            assertTrue(default.orEmpty().startsWith("nextval("), "$table.$column must retain its sequence default")
        }
    }

    @Test
    fun `gateway board tables are explicitly account-global in the world-scope inventory`() {
        migrateTo31()

        migrateV32()

        val gatewayAccountTables = setOf("gateway_board_post", "gateway_board_comment")
        val physicalGatewayTables = jdbc.queryForList(
            """
            SELECT table_name
              FROM information_schema.tables
             WHERE table_schema = 'public'
               AND table_type = 'BASE TABLE'
               AND table_name IN ('gateway_board_post', 'gateway_board_comment')
            """.trimIndent(),
            String::class.java,
        ).toSet()
        assertEquals(gatewayAccountTables, physicalGatewayTables, "V40 gateway tables must exist")
        assertTrue(
            globalAllowlist.containsAll(gatewayAccountTables),
            "gateway account tables must be explicitly classified instead of acquiring a game world",
        )
        gatewayAccountTables.forEach { table ->
            assertFalse(hasWorldColumn(table), "$table is account-global and must not acquire world_id")
        }
    }

    @Test
    fun `V32 backfills every remaining world-owned row from exactly one positive world`() {
        migrateTo31()
        seedWorld(701)
        seedV31Cohort(701)
        seedRemainingWorldRows()

        migrateV32()

        v32WorldOwnedTables.forEach { table ->
            assertEquals(
                listOf(701),
                jdbc.queryForList("SELECT DISTINCT world_id FROM $table ORDER BY world_id", Int::class.java),
                "$table must be backfilled only from world_state.id",
            )
        }
        assertEquals(
            listOf(mapOf("table_name" to "game_env", "world_id" to 701), mapOf("table_name" to "inheritance", "world_id" to null)),
            jdbc.queryForList("SELECT \"table\" AS table_name, world_id FROM game_kv ORDER BY \"table\""),
        )
    }

    @Test
    fun `V32 allows a zero-world global-only database and rejects unscoped world inserts`() {
        migrateTo31()
        seedGlobalRows()
        jdbc.update(
            "INSERT INTO game_kv (\"table\", namespace, key, value) VALUES ('inheritance', 'user:1', 'point', '1'::jsonb)",
        )

        migrateV32()

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM world_state", Int::class.java))
        assertEquals(null, jdbc.queryForMap("SELECT world_id FROM game_kv")["world_id"])
        v32GlobalAllowlist.forEach { table ->
            assertFalse(hasWorldColumn(table), "$table must remain global")
            assertTrue(
                (jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java) ?: 0) > 0,
                "$table global data must survive V32",
            )
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO message (mailbox, type, src, dest, message) VALUES (1, 'private', 1, 2, '{}'::jsonb)",
            )
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO game_kv (\"table\", namespace, key, value) VALUES ('game_env', 'global', 'x', '1'::jsonb)",
            )
        }
    }

    @Test
    fun `V32 rejects zero-world legacy game rows and rolls back all V32 DDL`() {
        val legacyFixtures = listOf(
            "INSERT INTO ng_games (server_id, date, season, scenario, scenario_name) VALUES ('legacy', now(), 1, 1010, '시나리오')" to
                "ng_games",
            "INSERT INTO general_owner (general_id, user_id) VALUES (31, 1)" to
                "general_owner",
            "INSERT INTO inheritance_result (server_id, owner, general_id, year, month) VALUES ('legacy', 'owner', 31, 181, 1)" to
                "inheritance_result",
            "INSERT INTO select_npc_token (owner_id, valid_until, pick_more_from, nonce) VALUES (1, now() + interval '1 hour', now(), 1)" to
                "select_npc_token",
            "INSERT INTO game_kv (\"table\", namespace, key, value) VALUES ('game_env', 'global', 'turn', '1'::jsonb)" to
                "game_kv",
        )

        legacyFixtures.forEach { (statement, table) ->
            resetSchema()
            migrateTo31()
            jdbc.update(statement)

            val failure = assertFailsWith<FlywayException> { migrateV32() }

            assertTrue(failure.message.orEmpty().contains("V32"))
            assertV32DdlRolledBack()
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))
        }
    }

    @Test
    fun `V32 rejects multiple or non-positive canonical worlds before DDL`() {
        migrateTo31()
        seedWorld(701)
        seedWorld(702)

        assertFailsWith<FlywayException> { migrateV32() }
        assertV32DdlRolledBack()

        resetSchema()
        migrateTo31()
        seedWorld(0)

        assertFailsWith<FlywayException> { migrateV32() }
        assertV32DdlRolledBack()
    }

    @Test
    fun `V32 failure after key promotion rolls back columns data constraints and Flyway history`() {
        migrateTo31()
        seedWorld(701)
        seedV31Cohort(701)
        seedRemainingWorldRows()
        jdbc.execute("ALTER TABLE troop ADD CONSTRAINT troop_world_id_fkey CHECK (true)")

        assertFailsWith<FlywayException> { migrateV32() }

        assertV32DdlRolledBack()
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM troop", Int::class.java))
        assertConstraint("troop", "c", "CHECK (true)")
    }

    @Test
    fun `V32 scopes uniqueness foreign keys and permits equal local root ids in two worlds`() {
        migrateTo31()
        seedWorld(701)
        seedV31Cohort(701)
        seedRemainingWorldRows()
        migrateV32()
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO message (mailbox, type, src, dest, message) VALUES (1, 'private', 1, 2, '{}'::jsonb)",
            )
        }
        seedWorld(702)

        seedV31Cohort(702)
        jdbc.update("INSERT INTO troop (world_id, troop_leader, nation, name) VALUES (702, 31, 11, '제2부대')")

        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM nation WHERE id = 11", Int::class.java))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM city WHERE id = 21", Int::class.java))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM general WHERE id = 31", Int::class.java))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM general_turn WHERE id = 41", Int::class.java))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM nation_turn WHERE id = 42", Int::class.java))
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("INSERT INTO nation (world_id, id, name, color) VALUES (702, 11, '중복', '#000000')")
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO general_turn (world_id, id, general_id, turn_idx, action_code) VALUES (702, 99, 999, 0, '휴식')",
            )
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO board_comment (world_id, id, post_id, nation_id, author_general_id, author_name, content_text) " +
                    "VALUES (702, 999, 110, 11, 31, '장수', '교차 세계 댓글')",
            )
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO ng_auction_bid (world_id, no, auction_id, general_id, amount, date) " +
                    "VALUES (702, 999, 130, 31, 10, now())",
            )
        }
    }

    @Test
    fun `V32 enforces game kv global inheritance and scoped row families with partial uniques`() {
        migrateTo31()
        seedWorld(701)
        migrateV32()
        seedWorld(702)

        jdbc.update(
            "INSERT INTO game_kv (world_id, \"table\", namespace, key, value) VALUES (NULL, 'inheritance', 'u:1', 'p', '1'::jsonb)",
        )
        jdbc.update(
            "INSERT INTO game_kv (world_id, \"table\", namespace, key, value) VALUES (701, 'game_env', 'global', 'turn', '1'::jsonb)",
        )
        jdbc.update(
            "INSERT INTO game_kv (world_id, \"table\", namespace, key, value) VALUES (702, 'game_env', 'global', 'turn', '2'::jsonb)",
        )

        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO game_kv (world_id, \"table\", namespace, key, value) VALUES (701, 'inheritance', 'u:2', 'p', '1'::jsonb)",
            )
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO game_kv (world_id, \"table\", namespace, key, value) VALUES (NULL, 'game_env', 'global', 'other', '1'::jsonb)",
            )
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO game_kv (world_id, \"table\", namespace, key, value) VALUES (NULL, 'inheritance', 'u:1', 'p', '2'::jsonb)",
            )
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO game_kv (world_id, \"table\", namespace, key, value) VALUES (701, 'game_env', 'global', 'turn', '2'::jsonb)",
            )
        }
        val indexes = indexDefinitions("game_kv")
        assertTrue(
            indexes.any {
                val definition = normalize(it)
                definition.contains("UNIQUE INDEX") &&
                    definition.contains("(\"table\", namespace, key)") &&
                    definition.contains("(\"table\" = 'inheritance'::text)") &&
                    definition.contains("(world_id IS NULL)")
            },
        )
        assertTrue(
            indexes.any {
                val definition = normalize(it)
                definition.contains("UNIQUE INDEX") &&
                    definition.contains("(world_id, \"table\", namespace, key)") &&
                    definition.contains("(\"table\" <> 'inheritance'::text)") &&
                    definition.contains("(world_id IS NOT NULL)")
            },
        )
    }

    @Test
    fun `V32 uses one fixed lock order and creates no world id defaults`() {
        val migrationSql = requireNotNull(
            javaClass.classLoader.getResource("db/migration/V32__complete_world_scope_expand.sql"),
        ).readText()
        val lockClause = requireNotNull(
            Regex(
                """LOCK\s+TABLE\s+(.*?)\s+IN\s+SHARE\s+ROW\s+EXCLUSIVE\s+MODE\s*;""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(migrationSql),
        ).groupValues[1]
        val parsedRelations = lockClause.split(',').map { it.trim().lowercase() }.filter(String::isNotBlank)

        assertEquals(listOf("world_state") + v32WorldOwnedTables + "game_kv", parsedRelations)
        assertFalse(migrationSql.contains(Regex("world_id\\s+integer\\s+[^;]*DEFAULT", RegexOption.IGNORE_CASE)))
        assertFalse(migrationSql.contains(Regex("CREATE\\s+(OR\\s+REPLACE\\s+)?FUNCTION", RegexOption.IGNORE_CASE)))
        assertFalse(migrationSql.contains(Regex("CREATE\\s+TRIGGER", RegexOption.IGNORE_CASE)))
    }

    private fun migrateTo31() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(sessionLockConfig)
            .target(MigrationVersion.fromVersion("31"))
            .load()
            .migrate()
    }

    private fun migrateV32() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(sessionLockConfig)
            .load()
            .migrate()
    }

    private fun seedWorld(id: Int) {
        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, meta)
            VALUES (?, 'scenario_1010', 181, 1, 3600, '{}'::jsonb)
            """.trimIndent(),
            id,
        )
    }

    private fun seedV31Cohort(worldId: Int) {
        jdbc.update("INSERT INTO nation (world_id, id, name, color) VALUES (?, 11, '한', '#ffffff')", worldId)
        jdbc.update(
            """
            INSERT INTO city
                (world_id, id, name, level, nation_id, pop, pop_max, agri, agri_max, comm, comm_max,
                 secu, secu_max, def, def_max, wall, wall_max, region)
            VALUES (?, 21, '낙양', 1, 11, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
            """.trimIndent(),
            worldId,
        )
        jdbc.update(
            "INSERT INTO general (world_id, id, name, nation_id, city_id, turn_time) VALUES (?, 31, '장수', 11, 21, now())",
            worldId,
        )
        jdbc.update(
            "INSERT INTO general_turn (world_id, id, general_id, turn_idx, action_code) VALUES (?, 41, 31, 0, '휴식')",
            worldId,
        )
        jdbc.update(
            "INSERT INTO nation_turn (world_id, id, nation_id, officer_level, turn_idx, action_code) VALUES (?, 42, 11, 12, 0, '휴식')",
            worldId,
        )
    }

    private fun seedRemainingWorldRows() {
        val statements = listOf(
            "INSERT INTO troop (troop_leader, nation, name) VALUES (31, 11, '부대')",
            "INSERT INTO diplomacy (id, src_nation_id, dest_nation_id, state_code) VALUES (50, 11, 11, 0)",
            "INSERT INTO diplomacy_letter (id, src_nation_id, dest_nation_id, text_brief, text_detail, src_signer) VALUES (51, 11, 11, '요약', '본문', 31)",
            "INSERT INTO rank_data (id, nation_id, general_id, type, value) VALUES (60, 11, 31, 'leadership', 1)",
            "INSERT INTO hall (id, server_id, season, scenario, general_no, type, value, owner) VALUES (70, 'server', 1, 1010, 31, 'leadership', 1, 'owner')",
            "INSERT INTO ng_games (id, server_id, date, season, scenario, scenario_name) VALUES (80, 'server', now(), 1, 1010, '시나리오')",
            "INSERT INTO ng_old_nations (id, server_id, nation) VALUES (81, 'server', 11)",
            "INSERT INTO ng_old_generals (id, server_id, general_no, owner, name, last_yearmonth, turntime) VALUES (82, 'server', 31, 'owner', '장수', 18101, now())",
            "INSERT INTO yearbook_history (id, profile_name, server_id, year, month, map, nations) VALUES (90, 'profile', 'server', 181, 1, '{}'::jsonb, '[]'::jsonb)",
            "INSERT INTO event (id, target_code, priority) VALUES (100, 'monthly', 0)",
            "INSERT INTO log_entry (id, scope, category, year, month, text, general_id, nation_id) VALUES (101, 'GENERAL', 'ACTION', 181, 1, '로그', 31, 11)",
            "INSERT INTO board_post (id, nation_id, author_general_id, author_name, title, content_html) VALUES (110, 11, 31, '장수', '제목', '본문')",
            "INSERT INTO board_comment (id, post_id, nation_id, author_general_id, author_name, content_text) VALUES (111, 110, 11, 31, '장수', '댓글')",
            "INSERT INTO vote_poll (id, title, options, reveal_mode, opener_general_id, opener_name) VALUES (120, '투표', '[]'::jsonb, 'public', 31, '장수')",
            "INSERT INTO vote (id, vote_id, general_id, nation_id, selection) VALUES (121, 120, 31, 11, '[]'::jsonb)",
            "INSERT INTO vote_comment (id, vote_id, general_id, nation_id, general_name, nation_name, text) VALUES (122, 120, 31, 11, '장수', '한', '댓글')",
            "INSERT INTO nation_env (id, namespace, key, value) VALUES (123, 11, 'key', '1'::jsonb)",
            "INSERT INTO message (id, mailbox, type, src, dest, message) VALUES (124, 31, 'private', 31, 31, '{}'::jsonb)",
            "INSERT INTO ng_betting (id, betting_id, general_id, betting_type, amount) VALUES (125, 1, 31, 'bet', 10)",
            "INSERT INTO game_kv (id, \"table\", namespace, key, value) VALUES (126, 'game_env', 'global', 'turn', '1'::jsonb)",
            "INSERT INTO game_kv (id, \"table\", namespace, key, value) VALUES (127, 'inheritance', 'user:1', 'point', '1'::jsonb)",
            "INSERT INTO ng_auction (id, type, target, host_general_id, req_resource, open_date, close_date) VALUES (130, 'buyRice', 'rice', 31, 'gold', now(), now() + interval '1 hour')",
            "INSERT INTO ng_auction_bid (no, auction_id, general_id, amount, date) VALUES (131, 130, 31, 10, now())",
            "INSERT INTO statistic (id, year, month) VALUES (140, 181, 1)",
            "INSERT INTO select_pool (id, unique_name, owner, general_id, info) VALUES (150, 'pool', 1, 31, 'info')",
            "INSERT INTO general_access_log (id, general_id, user_id) VALUES (160, 31, 1)",
            "INSERT INTO emperior (id, phase, server_id, nation_count, nation_name, nation_hist, gen_count, personal_hist, special_hist, name, type, color, year, month, pop, poprate) VALUES (170, 'united', 'server', '1', '한', '', '1', '', '', '황제', 'type', '#fff', 181, 1, '1', '1')",
            "INSERT INTO general_owner (general_id, user_id) VALUES (31, 1)",
            "INSERT INTO inheritance_result (id, server_id, owner, general_id, year, month) VALUES (180, 'server', 'owner', 31, 181, 1)",
            "INSERT INTO select_npc_token (id, owner_id, valid_until, pick_more_from, nonce) VALUES (190, 1, now() + interval '1 hour', now(), 1)",
        )
        statements.forEach(jdbc::execute)
    }

    private fun seedGlobalRows() {
        jdbc.update("INSERT INTO users (id, username, password) VALUES (1, 'user', 'hash')")
        jdbc.update("INSERT INTO banned_member (id, hashed_email) VALUES (1, repeat('a', 128))")
        jdbc.update("INSERT INTO error_log (id, category, message) VALUES (1, 'global', 'message')")
        jdbc.update("INSERT INTO inheritance_point (id, user_id, key) VALUES (1, 'user', 'point')")
        jdbc.update("INSERT INTO inheritance_log (id, user_id, year, month, text) VALUES (1, 'user', 181, 1, 'log')")
        jdbc.update("INSERT INTO inheritance_user_state (user_id) VALUES ('user')")
    }

    private fun assertScopedConstraintMatrix() {
        val specialPrimaryKeys = mapOf(
            "troop" to "PRIMARY KEY (world_id, troop_leader)",
            "ng_auction_bid" to "PRIMARY KEY (world_id, no)",
            "general_owner" to "PRIMARY KEY (world_id, general_id)",
            "water_zone_control" to "PRIMARY KEY (world_id, water_zone_id)",
        )
        specialPrimaryKeys.forEach { (table, expected) -> assertConstraint(table, "p", expected) }

        val scopedUniques = mapOf(
            "general_turn" to listOf("UNIQUE (world_id, general_id, turn_idx)"),
            "nation_turn" to listOf("UNIQUE (world_id, nation_id, officer_level, turn_idx)"),
            "diplomacy" to listOf("UNIQUE (world_id, src_nation_id, dest_nation_id)"),
            "rank_data" to listOf("UNIQUE (world_id, general_id, type)"),
            "hall" to listOf("UNIQUE (world_id, server_id, type, general_no)", "UNIQUE (world_id, owner, server_id, type)"),
            "ng_games" to listOf("UNIQUE (world_id, server_id)"),
            "ng_old_nations" to listOf("UNIQUE (world_id, server_id, nation)"),
            "ng_old_generals" to listOf("UNIQUE (world_id, server_id, general_no)"),
            "vote" to listOf("UNIQUE (world_id, vote_id, general_id)"),
            "nation_env" to listOf("UNIQUE (world_id, namespace, key)"),
            "ng_betting" to listOf("UNIQUE (world_id, general_id, betting_id, betting_type)", "UNIQUE (world_id, betting_id, betting_type, general_id)"),
            "ng_auction_bid" to listOf("UNIQUE (world_id, general_id, auction_id, amount)", "UNIQUE (world_id, auction_id, amount)"),
            "select_pool" to listOf("UNIQUE (world_id, unique_name)", "UNIQUE (world_id, general_id)"),
            "general_access_log" to listOf("UNIQUE (world_id, general_id)"),
            "general_owner" to listOf("UNIQUE (world_id, user_id)"),
        )
        scopedUniques.forEach { (table, expected) -> expected.forEach { assertConstraint(table, "u", it) } }
        worldOwnedTables.forEach { table ->
            constraintDefinitions(table, "u").forEach { definition ->
                assertTrue(normalize(definition).contains("(world_id,"), "$table retains unscoped unique: $definition")
            }
        }

        assertForeignKey("general_turn", "FOREIGN KEY (world_id, general_id) REFERENCES general(world_id, id) DEFERRABLE INITIALLY DEFERRED")
        assertForeignKey("nation_turn", "FOREIGN KEY (world_id, nation_id) REFERENCES nation(world_id, id) DEFERRABLE INITIALLY DEFERRED")
        assertForeignKey("troop", "FOREIGN KEY (world_id, troop_leader) REFERENCES general(world_id, id) DEFERRABLE INITIALLY DEFERRED")
        assertForeignKey("troop", "FOREIGN KEY (world_id, nation) REFERENCES nation(world_id, id) DEFERRABLE INITIALLY DEFERRED")
        assertForeignKey("diplomacy", "FOREIGN KEY (world_id, src_nation_id) REFERENCES nation(world_id, id) DEFERRABLE INITIALLY DEFERRED")
        assertForeignKey("diplomacy", "FOREIGN KEY (world_id, dest_nation_id) REFERENCES nation(world_id, id) DEFERRABLE INITIALLY DEFERRED")
        assertForeignKey("diplomacy_letter", "FOREIGN KEY (world_id, prev_id) REFERENCES diplomacy_letter(world_id, id) DEFERRABLE INITIALLY DEFERRED")
        assertForeignKey("board_comment", "FOREIGN KEY (world_id, post_id) REFERENCES board_post(world_id, id) ON DELETE CASCADE")
        assertForeignKey("vote", "FOREIGN KEY (world_id, vote_id) REFERENCES vote_poll(world_id, id) ON DELETE CASCADE")
        assertForeignKey("vote_comment", "FOREIGN KEY (world_id, vote_id) REFERENCES vote_poll(world_id, id) ON DELETE CASCADE")
        assertForeignKey("ng_auction_bid", "FOREIGN KEY (world_id, auction_id) REFERENCES ng_auction(world_id, id)")
    }

    private fun assertWorldColumn(table: String, nullable: Boolean) {
        val column = jdbc.queryForMap(
            """
            SELECT data_type, is_nullable, column_default
              FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = ? AND column_name = 'world_id'
            """.trimIndent(),
            table,
        )
        assertEquals("integer", column["data_type"])
        assertEquals(if (nullable) "YES" else "NO", column["is_nullable"], "$table.world_id nullability")
        assertEquals(null, column["column_default"], "$table.world_id must not have a fallback default")
    }

    private fun assertWorldForeignKey(table: String) {
        val deleteAction = if (table == "water_zone_control") " ON DELETE CASCADE" else ""
        assertForeignKey(table, "FOREIGN KEY (world_id) REFERENCES world_state(id)$deleteAction")
    }

    private fun assertForeignKey(table: String, expected: String) {
        val definitions = constraintDefinitions(table, "f").map(::normalize)
        assertTrue(definitions.contains(expected), "$table must have $expected; actual=$definitions")
    }

    private fun assertEveryIndexWorldLeading(table: String) {
        val firstColumns = jdbc.queryForList(
            """
            SELECT attribute.attname
              FROM pg_index index_meta
              JOIN pg_class relation ON relation.oid = index_meta.indrelid
              JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
              JOIN pg_attribute attribute
                ON attribute.attrelid = relation.oid
               AND attribute.attnum = index_meta.indkey[0]
             WHERE namespace.nspname = 'public'
               AND relation.relname = ?
            """.trimIndent(),
            String::class.java,
            table,
        )
        assertTrue(firstColumns.isNotEmpty(), "$table must have a world-leading key/index")
        assertEquals(setOf("world_id"), firstColumns.toSet(), "$table retains a non-world-leading index")
    }

    private fun assertConstraint(table: String, type: String, expected: String) {
        val definitions = constraintDefinitions(table, type)
        assertTrue(definitions.any { normalize(it) == expected }, "$table must have $expected; actual=$definitions")
    }

    private fun constraintDefinitions(table: String, type: String): List<String> =
        jdbc.queryForList(
            """
            SELECT pg_get_constraintdef(constraint_meta.oid)
              FROM pg_constraint constraint_meta
             WHERE constraint_meta.conrelid = ?::regclass
               AND constraint_meta.contype = ?
             ORDER BY constraint_meta.conname
            """.trimIndent(),
            String::class.java,
            table,
            type,
        )

    private fun primaryKeyDefinition(table: String): String = constraintDefinitions(table, "p").single()

    private fun indexDefinitions(table: String): List<String> = jdbc.queryForList(
        "SELECT pg_get_indexdef(indexrelid) FROM pg_index WHERE indrelid = ?::regclass ORDER BY indexrelid::regclass::text",
        String::class.java,
        table,
    )

    private fun hasWorldColumn(table: String): Boolean =
        (jdbc.queryForObject(
            """
            SELECT count(*)
              FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = ? AND column_name = 'world_id'
            """.trimIndent(),
            Int::class.java,
            table,
        ) ?: 0) == 1

    private fun assertV32DdlRolledBack() {
        remainingWorldTables.forEach { table -> assertFalse(hasWorldColumn(table), "$table.world_id DDL must roll back") }
        assertFalse(hasWorldColumn("game_kv"), "game_kv.world_id DDL must roll back")
        firstCohort.forEach { table ->
            assertEquals("PRIMARY KEY (id)", normalize(primaryKeyDefinition(table)), "$table V31 primary key must roll back")
        }
        assertFalse(
            constraintDefinitions("world_state", "c").map(::normalize).contains("CHECK ((id > 0))"),
            "world_state positive check must roll back",
        )
        assertEquals(0, appliedV32Count())
    }

    private fun appliedV32Count(): Int = jdbc.queryForObject(
        "SELECT count(*) FROM flyway_schema_history WHERE version = '32' AND success",
        Int::class.java,
    ) ?: 0

    private fun normalize(definition: String): String = definition.replace(Regex("\\s+"), " ").trim()

    private companion object {
        private val sessionLockConfig = mapOf("flyway.postgresql.transactional.lock" to "false")
        private val firstCohort = listOf("nation", "city", "general", "general_turn", "nation_turn")
        private val remainingWorldTables = listOf(
            "troop",
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
            "general_owner",
            "inheritance_result",
            "select_npc_token",
        )
        private val postV32WorldTables = listOf(
            "command_inbox",
            "command_result",
            "command_outbox",
            "water_zone_control",
        )
        private val v32WorldOwnedTables = firstCohort + remainingWorldTables
        private val worldOwnedTables = v32WorldOwnedTables + postV32WorldTables
        private val v32GlobalAllowlist = setOf(
            "inheritance_point",
            "inheritance_log",
            "inheritance_user_state",
            "users",
            "system_flag",
            "banned_member",
            "error_log",
        )
        private val postV32GlobalTables = setOf(
            "gateway_board_post",
            "gateway_board_comment",
            "game_server",
            "game_server_registry_transition",
        )
        private val globalAllowlist = v32GlobalAllowlist + postV32GlobalTables
        private val serialIdentityColumns = mapOf(
            "general_turn" to "id",
            "nation_turn" to "id",
            "diplomacy" to "id",
            "diplomacy_letter" to "id",
            "rank_data" to "id",
            "hall" to "id",
            "ng_games" to "id",
            "ng_old_nations" to "id",
            "ng_old_generals" to "id",
            "yearbook_history" to "id",
            "event" to "id",
            "log_entry" to "id",
            "board_post" to "id",
            "board_comment" to "id",
            "vote_poll" to "id",
            "vote" to "id",
            "vote_comment" to "id",
            "nation_env" to "id",
            "message" to "id",
            "ng_betting" to "id",
            "ng_auction" to "id",
            "ng_auction_bid" to "no",
            "statistic" to "id",
            "select_pool" to "id",
            "general_access_log" to "id",
            "emperior" to "id",
            "inheritance_result" to "id",
            "select_npc_token" to "id",
        )
    }
}

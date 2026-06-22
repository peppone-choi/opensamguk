package opensamguk.infra.persistence

import opensamguk.logic.domain.Nation
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
import kotlin.test.assertTrue

/**
 * F3 Task FF2 — the tombstone DELETE seam, exercised end-to-end against Postgres.
 *
 * Faithful to `General.php:515-600` (kill: DELETE general/general_turn/rank_data + gennum-1) + the
 * nation cascade (DELETE diplomacy/nation_turn/nation). The [JdbcFlushExecutor] step-5/step-6
 * delete-sets ALREADY exist; F3's emitter (FF1) just makes the [FlushPayload.deletedGenerals] /
 * [FlushPayload.deletedNations] non-empty so they fire. This IT pins the explicit P3 gate target:
 * the delete lands EXACTLY ONCE and a re-flush of an empty delta does NOT re-apply / does NOT error.
 *
 * NOTE: `general_access_log` (the 4th kill() table) is NOT ported to the V1 baseline schema — only
 * the 3 tables that exist (general/general_turn/rank_data) are deleted.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeleteFlushNoDoubleApplyIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    private val rankColumns = listOf(
        "firenum", "warnum", "killnum", "deathnum", "killcrew", "deathcrew",
        "ttw", "ttd", "ttl", "ttg", "ttp",
        "tlw", "tld", "tll", "tlg", "tlp",
        "tsw", "tsd", "tsl", "tsg", "tsp",
        "tiw", "tid", "til", "tig", "tip",
        "betwin", "betgold", "betwingold",
        "killcrew_person", "deathcrew_person",
        "occupied",
        "inherit_earned", "inherit_spent",
        "inherit_earned_dyn", "inherit_earned_act", "inherit_spent_dyn",
    )

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
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
            .load()
            .migrate()

        jdbc = NamedParameterJdbcTemplate(dataSource)
        val txManager = DataSourceTransactionManager(dataSource)
        executor = JdbcFlushExecutor(jdbc, TransactionTemplate(txManager))

        seed()
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    private fun seed() {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'scenario_2', 200, 1, 3600)",
            MapSqlParameterSource(),
        )
        // Two nations: 2 (the killed general's nation, gennum starts at 3) and 9 (the cascade target).
        jdbc.update(
            """
            INSERT INTO nation (id, name, color, capital_city_id, gold, rice, tech, level, type_code, meta)
            VALUES (2, '촉', '#00ff00', 5, 5000, 5000, 1000, 5, 'che_명가', CAST('{"gennum":3}' AS jsonb)),
                   (9, '망국', '#ff0000', 7, 100, 100, 0, 1, 'che_명가', CAST('{"gennum":1}' AS jsonb))
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        // The general to kill: 37 rank_data rows + general_turn rows.
        jdbc.update(
            """
            INSERT INTO general
                (id, name, nation_id, city_id, leadership, strength, intel, injury,
                 experience, dedication, officer_level, gold, rice, turn_time, last_turn, meta)
            VALUES
                (10, '장수십', 2, 5, 70, 65, 80, 0, 0, 0, 4, 1000, 1000, now(),
                 CAST('{}' AS jsonb), CAST('{}' AS jsonb))
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        for (col in rankColumns) {
            jdbc.update(
                "INSERT INTO rank_data (nation_id, general_id, type, value) VALUES (2, 10, :type, 0)",
                MapSqlParameterSource().addValue("type", col),
            )
        }
        for (idx in 0..2) {
            jdbc.update(
                "INSERT INTO general_turn (general_id, turn_idx, action_code) VALUES (10, :idx, '휴식')",
                MapSqlParameterSource().addValue("idx", idx),
            )
        }
        // A second, surviving general (must NOT be touched by the delete).
        jdbc.update(
            """
            INSERT INTO general
                (id, name, nation_id, city_id, leadership, strength, intel, injury,
                 experience, dedication, officer_level, gold, rice, turn_time, last_turn, meta)
            VALUES
                (11, '생존', 2, 5, 60, 60, 60, 0, 0, 0, 1, 500, 500, now(),
                 CAST('{}' AS jsonb), CAST('{}' AS jsonb))
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        jdbc.update(
            "INSERT INTO rank_data (nation_id, general_id, type, value) VALUES (2, 11, 'warnum', 0)",
            MapSqlParameterSource(),
        )
        // Cascade fixture for nation 9: a captured city, diplomacy pairs, and nation_turn rows.
        jdbc.update(
            """
            INSERT INTO city
                (id, name, level, nation_id, supply_state, front_state, pop, pop_max,
                 agri, agri_max, comm, comm_max, secu, secu_max, trust, trade, def, def_max,
                 wall, wall_max, region, meta)
            VALUES
                (7, '함락성', 5, 9, 1, 0, 10000, 50000, 500, 1000, 500, 1000, 300, 1000, 40, 100,
                 500, 1000, 500, 1000, 1, CAST('{}' AS jsonb))
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        jdbc.update(
            """
            INSERT INTO diplomacy (src_nation_id, dest_nation_id, state_code, term)
            VALUES (9, 2, 2, 0), (2, 9, 2, 0)
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        jdbc.update(
            "INSERT INTO nation_turn (nation_id, officer_level, turn_idx, action_code) VALUES (9, 12, 0, '휴식')",
            MapSqlParameterSource(),
        )
    }

    @Test
    fun `general kill deletes the 3 ported tables once, decrements gennum, and a re-flush is a no-op`() {
        // gennum-1 on the killed general's nation rides the step-7 nation UPDATE (General.php:99).
        val nation2AfterKill = Nation(
            id = 2, level = 5, capitalCityId = 5, name = "촉", color = "#00ff00",
            typeCode = "che_명가", gold = 5000, rice = 5000, tech = 1000.0,
            meta = linkedMapOf("gennum" to 2),
        )
        val payload = FlushPayload(
            worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 200, "current_month" to 1),
            deletedGenerals = listOf(10),
            updatedNations = listOf(nation2AfterKill),
        )

        executor.flush(payload)

        // --- all 3 ported kill() tables deleted for general 10 -------------------------------------
        assertEquals(0, count("SELECT count(*) FROM general WHERE id = 10"))
        assertEquals(0, count("SELECT count(*) FROM general_turn WHERE general_id = 10"))
        assertEquals(0, count("SELECT count(*) FROM rank_data WHERE general_id = 10"))

        // --- the surviving general (and its rank rows) are untouched -------------------------------
        assertEquals(1, count("SELECT count(*) FROM general WHERE id = 11"))
        assertEquals(1, count("SELECT count(*) FROM rank_data WHERE general_id = 11"))

        // --- gennum decremented on the nation (rode the step-7 UPDATE) -----------------------------
        val gennum = jdbc.queryForObject(
            "SELECT (meta->>'gennum')::int FROM nation WHERE id = 2",
            MapSqlParameterSource(), Int::class.java,
        )
        assertEquals(2, gennum)

        // --- the op sequence shows step-5 fired general + general_turn + rank_data DELETE_MANY ------
        val deleteOps = executor.lastOps().filter { it.verb == FlushVerb.DELETE_MANY }.map { it.table }
        assertEquals(listOf("general", "general_turn", "rank_data"), deleteOps)

        // --- a SECOND flush of an EMPTY delta does NOT re-apply / does NOT error --------------------
        val emptyPayload = FlushPayload(
            worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 200, "current_month" to 1),
        )
        executor.flush(emptyPayload) // must not throw
        assertEquals(0, count("SELECT count(*) FROM general WHERE id = 10"), "still deleted, no resurrection")
        assertTrue(
            executor.lastOps().none { it.verb == FlushVerb.DELETE_MANY },
            "empty delta fires no delete",
        )
    }

    @Test
    fun `nation cascade deletes diplomacy + nation_turn + nation exactly once`() {
        val payload = FlushPayload(
            worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 200, "current_month" to 1),
            deletedNations = listOf(9),
        )

        executor.flush(payload)

        assertEquals(0, count("SELECT count(*) FROM nation WHERE id = 9"))
        assertEquals(0, count("SELECT count(*) FROM nation_turn WHERE nation_id = 9"))
        assertEquals(
            0,
            count("SELECT count(*) FROM diplomacy WHERE src_nation_id = 9 OR dest_nation_id = 9"),
        )

        val cascadeOps = executor.lastOps().filter { it.verb == FlushVerb.DELETE_MANY }.map { it.table }
        assertEquals(listOf("diplomacy", "nation_turn", "nation"), cascadeOps)

        // re-flush of an empty delta does not error and does not re-delete.
        executor.flush(
            FlushPayload(worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 200, "current_month" to 1)),
        )
        assertTrue(executor.lastOps().none { it.verb == FlushVerb.DELETE_MANY })
    }

    private fun count(sql: String): Int =
        jdbc.queryForObject(sql, MapSqlParameterSource(), Int::class.java) ?: -1
}

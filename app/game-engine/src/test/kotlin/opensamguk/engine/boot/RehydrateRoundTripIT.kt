package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.engine.turn.Troop
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.TroopRow
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * OPENSAM-149 D2 gate — the `troop` ROUND TRIP: flush → DB → rehydrate.
 *
 * Every existing flush IT is one-directional (`FlushPayload` → SQL → row assertion). `troop` is the
 * only in-memory world surface that is flushed on all four paths (`troopCreateMany` / `troopUpdate` /
 * `troopDeleteMany` / `troopDeleteByNation`) but never read back: [WorldSnapshotLoader] hardcodes
 * `troops = emptyList()`, so a restarted daemon boots with zero troops no matter what the DB holds.
 * Evidence: `docs/superpowers/research/2026-07-25-opensam-149-rehydrate-defects.md` §D2.
 *
 * Harness is [WorldSnapshotLoaderArchiveIT]'s verbatim (Testcontainers postgres:16-alpine + the
 * :infra Flyway baseline); the writer is the real [JdbcFlushExecutor] as in
 * [opensamguk.infra.persistence.TroopFlushIT]. Docker unavailable ⇒ skipped, not failed.
 *
 * Lives in :app:game-engine rather than next to the flush ITs because [WorldSnapshotLoader] is a
 * game-engine class and :infra must not depend on :app:game-engine.
 *
 * Each test owns a disjoint troop-leader id range so JUnit's unordered execution cannot couple them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RehydrateRoundTripIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var executor: JdbcFlushExecutor
    private lateinit var loader: WorldSnapshotLoader

    private fun ws() = linkedMapOf<String, Any?>("id" to 1, "current_year" to 200, "current_month" to 1)

    private fun flush(
        created: List<TroopRow> = emptyList(),
        updated: List<TroopRow> = emptyList(),
        deleted: List<Int> = emptyList(),
    ) = executor.flush(
        FlushPayload(
            worldId = WorldId(1),
            worldStateUpdate = ws(),
            createdTroops = created,
            updatedTroops = updated,
            deletedTroops = deleted,
        ),
    )

    /** Rehydrated troops restricted to one test's id range — the round-trip's read half. */
    private fun rehydratedTroops(ids: IntRange): List<Troop> =
        loader.buildSnapshot().troops.filter { it.id in ids }

    private fun troopRowsInDb(ids: IntRange): List<Int> = jdbc.queryForList(
        "SELECT troop_leader FROM troop WHERE world_id = 1 AND troop_leader BETWEEN ? AND ? ORDER BY troop_leader",
        Int::class.java,
        ids.first,
        ids.last,
    )

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
        executor = JdbcFlushExecutor(
            NamedParameterJdbcTemplate(dataSource),
            TransactionTemplate(DataSourceTransactionManager(dataSource)),
        )
        loader = WorldSnapshotLoader(
            jdbc,
            SeedBootstrap(
                scenarioCode = "scenario_0",
                seedEnabled = false,
                worldId = WorldId(1),
            ),
            WorldId(1),
            snapshotValidator = {},
        )

        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
            VALUES (1, 'scenario_0', 200, 1, 3600)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO nation (id, world_id, name, color)
            VALUES (1, 1, '테스트국', '#000000')
            """.trimIndent(),
        )
        // troop.troop_leader FKs to general (troop_world_general_fkey) — one leader per test id range.
        jdbc.update(
            """
            INSERT INTO general (id, world_id, name, nation_id, turn_time)
            VALUES (11, 1, '장수11', 1, now()), (12, 1, '장수12', 1, now()),
                   (21, 1, '장수21', 1, now()),
                   (31, 1, '장수31', 1, now()), (32, 1, '장수32', 1, now())
            """.trimIndent(),
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `a flush-created troop comes back in the rehydrated world snapshot`() {
        flush(created = listOf(TroopRow(11, 1, "제1군단"), TroopRow(12, 1, "제2군단")))
        assertEquals(listOf(11, 12), troopRowsInDb(10..19), "precondition: both troop rows were flushed")

        assertEquals(
            listOf(Troop(11, 1, "제1군단"), Troop(12, 1, "제2군단")),
            rehydratedTroops(10..19),
            "created troops 11/12 are in the `troop` table but the rehydrated WorldSnapshot has none — " +
                "WorldSnapshotLoader.buildSnapshot() hardcodes `troops = emptyList()`, so the restarted " +
                "daemon boots with zero troops and its first flush diffs against an empty troop set " +
                "(OPENSAM-149 D2)",
        )
    }

    @Test
    fun `a flush-updated troop name comes back in the rehydrated world snapshot`() {
        flush(created = listOf(TroopRow(21, 1, "원래군단")))
        flush(updated = listOf(TroopRow(21, 1, "개명군단")))

        assertEquals(
            listOf(Troop(21, 1, "개명군단")),
            rehydratedTroops(20..29),
            "the SetTroopName UPDATE reached the `troop` table but the rehydrated WorldSnapshot has no " +
                "troop 21 at all — WorldSnapshotLoader never reads the table, so a renamed troop cannot " +
                "survive a restart (OPENSAM-149 D2)",
        )
    }

    @Test
    fun `a flush-deleted troop is gone from the rehydrated snapshot while its sibling survives`() {
        flush(created = listOf(TroopRow(31, 1, "해산군단"), TroopRow(32, 1, "잔존군단")))
        flush(deleted = listOf(31))
        assertEquals(listOf(32), troopRowsInDb(30..39), "precondition: only troop 31 was deleted")

        assertEquals(
            listOf(Troop(32, 1, "잔존군단")),
            rehydratedTroops(30..39),
            "after the ExitTroop delete the surviving troop 32 is still in the `troop` table, but the " +
                "rehydrated WorldSnapshot returns no troops — the loader's empty list makes deletion " +
                "indistinguishable from survival across a restart (OPENSAM-149 D2)",
        )
    }
}

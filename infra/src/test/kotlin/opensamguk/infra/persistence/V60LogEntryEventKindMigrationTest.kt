package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V60 — `log_entry.event_kind` 와 휘하 기록 부분 인덱스 2개. 인덱스가 유효하고(CONCURRENTLY 빌드), 기존 행은
 * NULL 로 남고, game-api `HwihaRecordReadRepository` 와 같은 모양의 질의(연·월·순 행 비교, 종류 IN 목록)가
 * 해를 넘는 창에서 맞는 줄만 고르는지 본다.
 */
class V60LogEntryEventKindMigrationTest {
    @Test
    fun `V60 adds event_kind, leaves legacy rows null and builds valid partial indexes`() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — V60 migration IT skipped",
        )
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()
            migrate(postgres, "59")
            val jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
            jdbc.update("INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'fixture', 200, 1, 3600)")
            jdbc.update("INSERT INTO log_entry (world_id, scope, category, year, month, text) VALUES (1, 'GENERAL', 'ACTION', 199, 12, 'legacy')")
            migrate(postgres, "60")

            assertNull(jdbc.queryForObject("SELECT event_kind FROM log_entry WHERE text = 'legacy'", String::class.java))
            for (index in listOf("log_entry_hwiha_general_turn_idx", "log_entry_hwiha_nation_turn_idx")) {
                val valid = jdbc.queryForObject(
                    "SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = ?",
                    Boolean::class.java, index,
                )
                assertTrue(valid == true, "$index must exist and be valid")
                val def = jdbc.queryForObject("SELECT pg_get_indexdef(?::regclass)", String::class.java, index)!!
                assertTrue("WHERE (event_kind IS NOT NULL)" in def, "$index must be partial: $def")
            }

            fun insert(general: Int?, nation: Int?, scope: String, y: Int, m: Int, p: Int, kind: String, text: String) =
                jdbc.update(
                    """INSERT INTO log_entry (world_id, scope, category, year, month, phase, text, general_id, nation_id, meta, event_kind)
                       VALUES (1, CAST(? AS log_scope), 'ACTION', ?, ?, ?, ?, ?, ?, '{"refs":{"n":1}}'::jsonb, ?)""",
                    scope, y, m, p, text, general, nation, kind,
                )
            insert(1, 3, "GENERAL", 199, 9, 2, "march.assignment", "before-window")
            insert(1, 3, "GENERAL", 199, 9, 3, "march.assignment", "window-start")
            insert(1, 3, "GENERAL", 200, 1, 2, "renown.event", "window-end")
            insert(2, 3, "GENERAL", 200, 1, 1, "renown.event", "someone-else")
            insert(null, 3, "NATION", 199, 11, 1, "county.captured", "captured")
            insert(null, 3, "NATION", 199, 11, 1, "income.monthly", "income-not-summary")
            insert(null, 4, "NATION", 199, 11, 1, "county.lost", "other-nation")
            insert(null, null, "SYSTEM", 200, 1, 1, "yuedan.announced", "announced")

            val named = NamedParameterJdbcTemplate(jdbc)
            val window = mapOf("world" to 1, "fy" to 199, "fm" to 9, "fp" to 3, "ty" to 200, "tm" to 1, "tp" to 2)
            val personal = named.queryForList(
                """SELECT text FROM log_entry
                   WHERE world_id = :world AND general_id = :general AND scope = 'GENERAL' AND event_kind IS NOT NULL
                     AND (year, month, phase) >= (:fy, :fm, :fp) AND (year, month, phase) <= (:ty, :tm, :tp)
                   ORDER BY year, month, phase, id""",
                window + mapOf("general" to 1), String::class.java,
            )
            assertEquals(listOf("window-start", "window-end"), personal)
            val summary = named.queryForList(
                """SELECT text FROM log_entry
                   WHERE world_id = :world AND event_kind IS NOT NULL
                     AND ((scope = 'NATION' AND nation_id = :nation AND :nation <> 0 AND event_kind IN (:nationKinds))
                       OR (scope = 'SYSTEM' AND nation_id IS NULL AND event_kind IN (:worldKinds)))
                     AND (year, month, phase) >= (:fy, :fm, :fp) AND (year, month, phase) <= (:ty, :tm, :tp)
                   ORDER BY year, month, phase, id""",
                window + mapOf("nation" to 3, "nationKinds" to listOf("county.captured", "county.lost"),
                    "worldKinds" to listOf("yuedan.announced")),
                String::class.java,
            )
            assertEquals(listOf("captured", "announced"), summary)
        }
    }

    private fun migrate(postgres: PostgreSQLContainer<*>, target: String) {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .target(org.flywaydb.core.api.MigrationVersion.fromVersion(target))
            .load()
            .migrate()
    }
}

package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V29LogEntryYearMonthIndexMigrationTest {

    @Test
    fun `V29 creates the year-month index non-transactionally and it is valid`() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — V29 migration IT skipped",
        )
        withPostgres { postgres ->
            migrateTo29(postgres)
            val jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))

            // CONCURRENTLY 빌드가 중단되면 INVALID 인덱스가 남는다 — 존재만이 아니라 유효성까지 검증.
            val valid = jdbc.queryForObject(
                """
                SELECT i.indisvalid FROM pg_index i
                JOIN pg_class c ON c.oid = i.indexrelid
                WHERE c.relname = 'log_entry_year_month_idx'
                """.trimIndent(),
                Boolean::class.java,
            )
            assertTrue(valid == true, "log_entry_year_month_idx must exist and be valid")

            val columns = jdbc.queryForObject(
                "SELECT pg_get_indexdef(i.indexrelid) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'log_entry_year_month_idx'",
                String::class.java,
            )
            assertEquals(true, columns!!.contains("(year, month, id)"), "index must cover (year, month, id): $columns")
        }
    }

    @Test
    fun `V29 replaces an invalid leftover index from an interrupted CONCURRENTLY build`() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — V29 migration IT skipped",
        )
        withPostgres { postgres ->
            migrateTo28(postgres)
            val jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))

            // 중단된 CONCURRENTLY 빌드 잔흔 재현: 같은 이름의 인덱스를 만들고 카탈로그에서 INVALID로
            // 뒤집는다. V29가 DROP 없이 CREATE만 했다면 이름 충돌로 실패하고, IF NOT EXISTS였다면
            // INVALID 잔흔을 그대로 두고 지나간다 — 드롭-후-생성 관용구만 이 상태를 유효 인덱스로 바꾼다.
            jdbc.execute("CREATE INDEX log_entry_year_month_idx ON log_entry (year, month, id)")
            jdbc.execute("UPDATE pg_index SET indisvalid = false WHERE indexrelid = 'log_entry_year_month_idx'::regclass")

            migrateTo29(postgres)

            val valid = jdbc.queryForObject(
                "SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'log_entry_year_month_idx'",
                Boolean::class.java,
            )
            assertEquals(true, valid)
        }
    }

    private fun migrateTo28(postgres: PostgreSQLContainer<*>) {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(SESSION_LOCK_CONFIG)
            .target(org.flywaydb.core.api.MigrationVersion.fromVersion("28"))
            .load()
            .migrate()
    }

    private fun withPostgres(block: (PostgreSQLContainer<*>) -> Unit) {
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()
            block(postgres)
        }
    }

    private fun migrateTo29(postgres: PostgreSQLContainer<*>) {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(SESSION_LOCK_CONFIG)
            .target(org.flywaydb.core.api.MigrationVersion.fromVersion("29"))
            .load()
            .migrate()
    }

    companion object {
        // Flyway 기본값(트랜잭션형 advisory lock)은 잠금 커넥션을 idle-in-transaction으로 유지하고,
        // V29의 CREATE INDEX CONCURRENTLY는 그 열린 트랜잭션이 끝나길 영원히 기다린다(실증된 데드락).
        // 앱 3종의 spring.flyway.postgresql.transactional-lock=false와 동일한 세션 락 설정이다 —
        // 이 설정 없이 migrate()를 부르는 신규 테스트/도구는 V29에서 다시 행이 걸린다.
        private val SESSION_LOCK_CONFIG = mapOf("flyway.postgresql.transactional.lock" to "false")
    }
}

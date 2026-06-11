package opensamguk.infra.persistence

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

/**
 * P0-07 — Testcontainers IT for the betting channel UPSERT.
 *
 * PHP `Betting::bet()`은 `insertUpdate`(Betting.php:162-166): 같은 (general,betting,type)
 * 재베팅이면 `amount += %i`만 갱신. V7 UNIQUE(general_id,betting_id,betting_type) 위에서
 * plain INSERT는 재베팅 시 제약 위반으로 flush가 크래시(턴 동결 계열)하므로, 이 IT가
 * ON CONFLICT … amount += 경로를 실 Postgres에서 고정한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BettingFlushIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val ds: DataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration").load().migrate()
        jdbc = NamedParameterJdbcTemplate(ds)
        executor = JdbcFlushExecutor(jdbc, TransactionTemplate(DataSourceTransactionManager(ds)))
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'sc', 200, 1, 3600)",
            MapSqlParameterSource(),
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    private fun bet(generalId: Int, amount: Int, type: String = "[1,3]") = BettingInsertRow(
        linkedMapOf(
            "betting_id" to 7, "general_id" to generalId, "user_id" to 55,
            "betting_type" to type, "amount" to amount,
        ),
    )

    private fun payload(rows: List<BettingInsertRow>) = FlushPayload(
        worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 200, "current_month" to 1),
        bettingInserts = rows,
    )

    @Test
    fun `re-bet on the same (general, betting, type) UPSERTs amount += like PHP insertUpdate`() {
        // 첫 베팅 INSERT.
        executor.flush(payload(listOf(bet(generalId = 10, amount = 300))))
        assertEquals(
            300,
            jdbc.queryForObject(
                "SELECT amount FROM ng_betting WHERE betting_id = 7 AND general_id = 10 AND betting_type = '[1,3]'",
                MapSqlParameterSource(), Int::class.java,
            ),
        )

        // 같은 키 재베팅 — plain INSERT면 UNIQUE 위반으로 flush 크래시. UPSERT면 amount 300+200=500.
        executor.flush(payload(listOf(bet(generalId = 10, amount = 200))))
        assertEquals(
            500,
            jdbc.queryForObject(
                "SELECT amount FROM ng_betting WHERE betting_id = 7 AND general_id = 10 AND betting_type = '[1,3]'",
                MapSqlParameterSource(), Int::class.java,
            ),
        )
        // 행은 1개 그대로 (PHP insertUpdate — 새 행 아님).
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM ng_betting WHERE betting_id = 7 AND general_id = 10",
                MapSqlParameterSource(), Int::class.java,
            ),
        )

        // 다른 type/다른 장수는 별도 행.
        executor.flush(payload(listOf(bet(generalId = 10, amount = 100, type = "[2]"), bet(generalId = 20, amount = 50))))
        assertEquals(
            3,
            jdbc.queryForObject("SELECT count(*) FROM ng_betting WHERE betting_id = 7", MapSqlParameterSource(), Int::class.java),
        )
    }
}

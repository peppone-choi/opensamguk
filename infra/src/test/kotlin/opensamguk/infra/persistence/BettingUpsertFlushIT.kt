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
 * W0-8 ng_betting UPSERT 채널의 실DB 증명 (P0-07 flush 측).
 *
 * PHP 정본 Betting::bet(Betting.php:160-164):
 * `insertUpdate('ng_betting', row, ['amount' => sqleval('amount + %i', $amount)])`
 * — UNIQUE(general_id, betting_id, betting_type) 충돌(동일 키 재베팅) 시 amount만 누적,
 * user_id 등 나머지 컬럼은 기존 행 유지. 기존 INSERT-only 경로는 동일 키 재베팅마다 행을
 * 중복 적재해 당첨 정산(sum 집계는 우연히 같지만 행 모양이 PHP와 다름)과 UNIQUE 제약을 깼다.
 *
 * PlaceBetHandler의 검증 체인 9종(P0-07 본체)은 W1-C 소관 — 이 IT는 flush/DDL 측만 증명한다.
 *
 * Docker 미가용 시 Testcontainers가 컨테이너를 못 띄우므로 IT는 skip — fail이 아니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BettingUpsertFlushIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    private fun ws() = linkedMapOf<String, Any?>("id" to 1, "current_year" to 181, "current_month" to 1)

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres.start()
        val ds: DataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = NamedParameterJdbcTemplate(ds)
        executor = JdbcFlushExecutor(jdbc, TransactionTemplate(DataSourceTransactionManager(ds)))
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'sc', 181, 1, 3600)",
            MapSqlParameterSource(),
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    private fun bet(generalId: Int, type: String, amount: Int, userId: Int? = 7) = BettingInsertRow(
        linkedMapOf(
            "betting_id" to 1,
            "general_id" to generalId,
            "user_id" to userId,
            "betting_type" to type,
            "amount" to amount,
        ),
    )

    @Test
    fun `동일 키 재베팅은 행 중복 없이 amount만 누적된다 -- PHP insertUpdate 패러티`() {
        // 1차 베팅: 100.
        executor.flush(FlushPayload(worldId = opensamguk.common.world.WorldId(1), worldStateUpdate = ws(), bettingInserts = listOf(bet(10, "[0]", 100))))
        // 2차 재베팅(동일 general/betting/type): +200 — insertUpdate의 amount 누적 경로.
        // user_id를 다르게 실어도 기존 행이 유지된다(PHP insertUpdate는 amount만 갱신).
        executor.flush(FlushPayload(worldId = opensamguk.common.world.WorldId(1), worldStateUpdate = ws(), bettingInserts = listOf(bet(10, "[0]", 200, userId = 99))))

        val rows = jdbc.jdbcTemplate.queryForList("SELECT * FROM ng_betting WHERE betting_id = 1 AND general_id = 10")
        assertEquals(1, rows.size, "동일 (general,betting,type) 키 → 단일 행 (INSERT-only 중복 결함 제거)")
        assertEquals(300, rows[0]["amount"], "amount = 100 + 200 누적")
        assertEquals(7, rows[0]["user_id"], "충돌 시 user_id 등 나머지 컬럼은 기존 행 유지")
        assertEquals(FlushVerb.UPSERT, executor.lastOps().first { it.table == "ng_betting" }.verb)
    }

    @Test
    fun `다른 betting_type 또는 다른 장수는 별도 행이다`() {
        executor.flush(
            FlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                bettingInserts = listOf(bet(20, "[0]", 50), bet(20, "[0,1]", 60), bet(21, "[0]", 70)),
            ),
        )
        val count = jdbc.jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ng_betting WHERE general_id IN (20, 21)", Int::class.java,
        )
        assertEquals(3, count, "키가 다르면(UNIQUE 3컬럼) 각각 INSERT")
    }
}

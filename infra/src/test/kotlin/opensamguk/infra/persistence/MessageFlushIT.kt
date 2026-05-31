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
 * T0.5 — Testcontainers IT for the mailbox channel: message INSERT (receiver-before-sender, explicit
 * in-memory ids matching the SERIAL) + invalidate UPDATE.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageFlushIT {

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

    @Test
    fun `message INSERT writes both rows with explicit ids then invalidate UPDATEs the body`() {
        executor.flush(
            FlushPayload(
                worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 200, "current_month" to 1),
                createdMessages = listOf(
                    // receiver row (id 1) BEFORE sender row (id 2).
                    CreatedMessageRow(1, mailbox = 2, type = "private", srcId = 1, destId = 2,
                        time = "2026-05-31 00:00:00", validUntil = "9999-12-31 23:59:59",
                        bodyJson = """{"src":{"id":1},"dest":{"id":2},"text":"안녕","option":{"receiverMessageID":1}}"""),
                    CreatedMessageRow(2, mailbox = 1, type = "private", srcId = 1, destId = 2,
                        time = "2026-05-31 00:00:00", validUntil = "9999-12-31 23:59:59",
                        bodyJson = """{"src":{"id":1},"dest":{"id":2},"text":"안녕","option":{"senderMessageID":2}}"""),
                ),
            ),
        )
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM message", MapSqlParameterSource(), Int::class.java))
        // receiver row in dest mailbox.
        assertEquals(2, jdbc.queryForObject("SELECT mailbox FROM message WHERE id = 1", MapSqlParameterSource(), Int::class.java))
        // sender row in src mailbox.
        assertEquals(1, jdbc.queryForObject("SELECT mailbox FROM message WHERE id = 2", MapSqlParameterSource(), Int::class.java))

        // invalidate the receiver row: valid_until -> 2000, body rewritten.
        executor.flush(
            FlushPayload(
                worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 200, "current_month" to 1),
                messageInvalidates = listOf(
                    MessageInvalidateRow(1, validUntil = "2000-12-31 00:00:00",
                        bodyJson = """{"src":{"id":1},"dest":{"id":2},"text":"삭제된 메시지입니다.","option":{"invalid":true}}"""),
                ),
            ),
        )
        assertEquals(
            "2000",
            jdbc.queryForObject("SELECT to_char(valid_until, 'YYYY') FROM message WHERE id = 1", MapSqlParameterSource(), String::class.java),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM message WHERE id = 1 AND message -> 'option' ->> 'invalid' = 'true'",
                MapSqlParameterSource(), Int::class.java,
            ),
        )
    }
}

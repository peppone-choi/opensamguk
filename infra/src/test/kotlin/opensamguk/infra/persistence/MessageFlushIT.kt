package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
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

    /**
     * PER_CLASS 라이프사이클 + 단일 공유 컨테이너이므로 테스트 간 `message` 테이블을 비워 격리한다.
     * (PHP의 요청 단위 스코프에 대응 — 각 테스트는 깨끗한 메일함에서 시작.)
     */
    @BeforeEach
    fun cleanMessages() {
        jdbc.update("DELETE FROM message", MapSqlParameterSource())
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

    /**
     * A2 공통 인프라 — 외교 제의(che_불가침제의/종전제의/불가침파기제의)의 message:send effect flush 경로.
     *
     * 외교 명령 resolve()가 `GeneralActionResolveContext.sendMessage(DIPLOMACY 메시지)`를 buffer하면
     * 엔진(ReservedTurnHandler.routeMessage / ProcessNationCommand.routeMessage)이
     * `Message.send()`로 두 행(수신 BEFORE 발신)을 만들어 `ChangeRecorder.recordMessageInsert` →
     * `JdbcFlushExecutor.messageCreateMany`로 flush한다. 이 IT는 그 산출물(메일함 = 9000+nationID,
     * type=diplomacy, validUntil=date+max(30,turnterm*3)분, option.action=no_aggression)을 실 Postgres에
     * 적재해 검증한다 — A국(nation 1)→B국(nation 5) 불가침 제의.
     *
     *  - 수신 행: mailbox = 9000+5(B국), id 10(먼저 INSERT).
     *  - 발신 행: mailbox = 9000+1(A국), id 11. PHP는 발신 행의 option(action 포함)을 NULL로 적재한다
     *    (DiplomaticMessage sender row action-nulling) — 본문 option이 null임을 확인한다.
     */
    @Test
    fun `diplomatic message send flush writes national-base mailbox rows with diplomacy type`() {
        // turnterm=60 → validUntil = date + max(30, 180)분 = 2026-06-07 15:00:00
        executor.flush(
            FlushPayload(
                worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 200, "current_month" to 1),
                createdMessages = listOf(
                    // 수신 행(B국 메일함 9005) — id 10, BEFORE 발신.
                    CreatedMessageRow(
                        10, mailbox = 9005, type = "diplomacy", srcId = 9001, destId = 9005,
                        time = "2026-06-07 12:00:00", validUntil = "2026-06-07 15:00:00",
                        bodyJson = """{"src":{"id":1,"nation_id":1},"dest":{"id":0,"nation_id":5},"text":"아국와 200년 6월까지 불가침 제의 서신","option":{"action":"no_aggression","year":200,"month":6,"receiverMessageID":10}}""",
                    ),
                    // 발신 행(A국 메일함 9001) — id 11. action을 담은 외교 발신 행은 option을 NULL로 적재.
                    CreatedMessageRow(
                        11, mailbox = 9001, type = "diplomacy", srcId = 9001, destId = 9005,
                        time = "2026-06-07 12:00:00", validUntil = "2026-06-07 15:00:00",
                        bodyJson = """{"src":{"id":1,"nation_id":1},"dest":{"id":0,"nation_id":5},"text":"아국와 200년 6월까지 불가침 제의 서신","option":null}""",
                    ),
                ),
            ),
        )

        // 두 행 모두 적재.
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM message WHERE id IN (10, 11)", MapSqlParameterSource(), Int::class.java))
        // 수신 행은 B국 메일함(9000+5).
        assertEquals(9005, jdbc.queryForObject("SELECT mailbox FROM message WHERE id = 10", MapSqlParameterSource(), Int::class.java))
        // 발신 행은 A국 메일함(9000+1).
        assertEquals(9001, jdbc.queryForObject("SELECT mailbox FROM message WHERE id = 11", MapSqlParameterSource(), Int::class.java))
        // 타입은 diplomacy(message_type enum).
        assertEquals(
            "diplomacy",
            jdbc.queryForObject("SELECT type::text FROM message WHERE id = 10", MapSqlParameterSource(), String::class.java),
        )
        // validUntil = date + max(30, turnterm*3)분 = 15시.
        assertEquals(
            "15",
            jdbc.queryForObject("SELECT to_char(valid_until, 'HH24') FROM message WHERE id = 10", MapSqlParameterSource(), String::class.java),
        )
        // 수신 행 본문의 외교 action 페이로드가 보존됨(수락 intake가 소비).
        assertEquals(
            "no_aggression",
            jdbc.queryForObject(
                "SELECT message -> 'option' ->> 'action' FROM message WHERE id = 10",
                MapSqlParameterSource(), String::class.java,
            ),
        )
        // 발신 행 본문 option은 JSON null(외교 발신 행 action-nulling). jsonb에서 `{"option":null}`의
        // `-> 'option'`은 SQL-NULL이 아니라 JSONB null 값이므로 jsonb_typeof로 판정한다.
        assertEquals(
            "null",
            jdbc.queryForObject(
                "SELECT jsonb_typeof(message -> 'option') FROM message WHERE id = 11",
                MapSqlParameterSource(), String::class.java,
            ),
        )
    }
}

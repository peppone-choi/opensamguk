package opensamguk.infra.persistence

import opensamguk.infra.read.VotePollRepository
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
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F4 Wave 투표 — 설문조사 채널에 대한 Testcontainers IT (BoardFlushIT 미러): vote_poll INSERT
 * (설문 개설)에 이어 SERIAL로 부여된 id를 통해 설문을 참조하는 vote / vote_comment INSERT (투표·댓글),
 * 그리고 vote_poll UPDATE (closeOldVote 명시 마감 — end_at/closed_at/updated_at). [JdbcFlushExecutor]가
 * 방출하는 SQL이 실제 V1 baseline vote_poll/vote/vote_comment 테이블에 대해 컬럼이 올바른지 증명한다:
 *  - id 생략 → DB SERIAL; vote/vote_comment FK(vote_id)는 step-8e에서 설문-먼저-자식-나중으로 해소.
 *  - vote는 PHP insertIgnore → ON CONFLICT(vote_id,general_id) DO NOTHING (중복 멱등 무시).
 *  - vote_poll UPDATE는 삽입 순서대로 SET 절을 구성하고 timestamptz로 캐스트한다.
 *
 * Docker 미가용 시 Testcontainers가 컨테이너를 못 띄우므로 IT는 skip — fail이 아니다 (CLAUDE.md).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VoteFlushIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    private fun ws() = linkedMapOf<String, Any?>("id" to 1, "current_year" to 200, "current_month" to 1)

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
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'sc', 200, 1, 3600)",
            MapSqlParameterSource(),
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `vote_poll INSERT then vote and vote_comment INSERT then close UPDATE`() {
        // 1) 설문 개설 (INSERT-only, id 생략 → SERIAL). options는 jsonb 배열(인코딩된 문자열), end_at은 nullable.
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                votePollInserts = listOf(
                    VotePollInsertRow(
                        linkedMapOf(
                            "title" to "수도 이전 찬반", "body" to "",
                            "options" to """["찬성","반대","유보"]""",
                            "multiple_options" to 1, "reveal_mode" to "always",
                            "opener_general_id" to 10, "opener_name" to "유비",
                            "start_at" to "2026-06-03 00:00:00", "end_at" to null,
                        ),
                    ),
                ),
            ),
        )
        assertEquals(1, count("vote_poll"))
        val pollId = jdbc.queryForObject(
            "SELECT id FROM vote_poll WHERE title = :t",
            MapSqlParameterSource("t", "수도 이전 찬반"), Int::class.java,
        )!!
        // options jsonb 배열 길이 = 3 (옵션 인덱스 범위 가드 소스).
        assertEquals(3, jdbc.queryForObject(
            "SELECT jsonb_array_length(options) FROM vote_poll WHERE id = :id",
            MapSqlParameterSource("id", pollId), Int::class.java,
        ))
        // 개설 직후 end_at/closed_at 모두 NULL (아직 진행 중).
        assertNull(jdbc.queryForMap(
            "SELECT end_at FROM vote_poll WHERE id = :id", MapSqlParameterSource("id", pollId),
        )["end_at"])

        // 2) 영속화된 설문에 대한 투표 — FK vote_id가 반드시 해소되어야 한다. selection은 jsonb(정렬된 인덱스).
        //    같은 (vote_id, general_id)를 두 번 INSERT해 insertIgnore(ON CONFLICT DO NOTHING) 멱등을 증명.
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                voteInserts = listOf(
                    VoteInsertRow(linkedMapOf(
                        "vote_id" to pollId, "general_id" to 20, "nation_id" to 1, "selection" to "[0]",
                    )),
                    VoteInsertRow(linkedMapOf(
                        "vote_id" to pollId, "general_id" to 20, "nation_id" to 1, "selection" to "[1]",
                    )),
                ),
                voteCommentInserts = listOf(
                    VoteCommentInsertRow(linkedMapOf(
                        "vote_id" to pollId, "general_id" to 20, "nation_id" to 1,
                        "general_name" to "관우", "nation_name" to "촉", "text" to "찬성합니다",
                    )),
                ),
            ),
        )
        // UNIQUE(vote_id,general_id) → 중복 INSERT는 무시되어 1행만 남는다 (PHP insertIgnore).
        assertEquals(1, count("vote"))
        // 첫 INSERT의 selection이 유지된다 (DO NOTHING이라 갱신 없음).
        assertEquals("[0]", jdbc.queryForObject(
            "SELECT selection::text FROM vote WHERE vote_id = :id AND general_id = 20",
            MapSqlParameterSource("id", pollId), String::class.java,
        ))
        assertEquals(1, count("vote_comment"))
        val comment = jdbc.queryForMap(
            "SELECT general_name, nation_name, text FROM vote_comment WHERE vote_id = :id",
            MapSqlParameterSource("id", pollId),
        )
        assertEquals("관우", comment["general_name"])
        assertEquals("찬성합니다", comment["text"])

        // 3) closeOldVote 명시 마감 — vote_poll UPDATE (end_at/closed_at/updated_at, 삽입 순서대로 SET).
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                votePollUpdates = linkedMapOf(
                    pollId to linkedMapOf<String, Any?>(
                        "end_at" to "2026-06-03 12:00:00",
                        "closed_at" to "2026-06-03 12:00:00",
                        "updated_at" to "2026-06-03 12:00:00",
                    ),
                ),
            ),
        )
        // end_at/closed_at이 채워졌다 → expired 판정 소스.
        val closed = jdbc.queryForMap(
            "SELECT end_at, closed_at, updated_at FROM vote_poll WHERE id = :id",
            MapSqlParameterSource("id", pollId),
        )
        assertNotNull(closed["end_at"])
        assertNotNull(closed["closed_at"])
        assertNotNull(closed["updated_at"])
        assertTrue(executor.lastOps().any { it.table == "vote_poll" && it.verb == FlushVerb.UPDATE })
    }

    @Test
    fun `VotePollRepository findPollState reads cast-guard fields and already-voted EXISTS`() {
        val repo = VotePollRepository(jdbc)
        val now = Instant.parse("2026-06-03T06:00:00Z")

        // 진행 중인 설문 — end_at은 now 이후(미만료). general 30은 미투표, general 31은 투표 완료로 셋업.
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                votePollInserts = listOf(
                    VotePollInsertRow(linkedMapOf(
                        "title" to "동맹 체결", "body" to "",
                        "options" to """["승인","거부"]""",
                        "multiple_options" to 2, "reveal_mode" to "always",
                        "opener_general_id" to 30, "opener_name" to "조조",
                        "start_at" to "2026-06-03 00:00:00", "end_at" to "2026-06-03 23:00:00",
                    )),
                ),
            ),
        )
        val pollId = jdbc.queryForObject(
            "SELECT id FROM vote_poll WHERE title = :t",
            MapSqlParameterSource("t", "동맹 체결"), Int::class.java,
        )!!
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                voteInserts = listOf(VoteInsertRow(linkedMapOf(
                    "vote_id" to pollId, "general_id" to 31, "nation_id" to 2, "selection" to "[0]",
                ))),
            ),
        )

        // general 30: 미투표 + 미만료(end_at 23:00 > now 06:00).
        val state30 = repo.findPollState(pollId, generalId = 30, now = now)
        assertNotNull(state30)
        assertEquals(pollId, state30.id)
        assertEquals(2, state30.multipleOptions)
        assertEquals(2, state30.optionsCount)
        assertEquals("조조", state30.opener)
        assertFalse(state30.expired)
        assertTrue(state30.hasEndDate)
        assertFalse(state30.alreadyVoted)

        // general 31: 같은 설문이지만 이미 투표함 (vote UNIQUE 존재) → alreadyVoted = true.
        val state31 = repo.findPollState(pollId, generalId = 31, now = now)
        assertNotNull(state31)
        assertTrue(state31.alreadyVoted)

        // 존재하지 않는 설문 → null (PHP '설문조사가 없습니다.').
        assertNull(repo.findPollState(999999, generalId = 30, now = now))

        // 만료 비교: now를 end_at 이후로 옮기면 expired = true (PHP endDate < now).
        val afterEnd = Instant.parse("2026-06-04T00:00:00Z")
        val stateExpired = repo.findPollState(pollId, generalId = 30, now = afterEnd)
        assertNotNull(stateExpired)
        assertTrue(stateExpired.expired)

        // closeOldVote 마감(closed_at) 후엔 now와 무관하게 expired = true.
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                votePollUpdates = linkedMapOf(
                    pollId to linkedMapOf<String, Any?>("closed_at" to "2026-06-03 05:00:00"),
                ),
            ),
        )
        val stateClosed = repo.findPollState(pollId, generalId = 30, now = now)
        assertNotNull(stateClosed)
        assertTrue(stateClosed.expired)
        assertTrue(stateClosed.hasEndDate)
    }

    private fun count(table: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table", MapSqlParameterSource(), Int::class.java)!!
}

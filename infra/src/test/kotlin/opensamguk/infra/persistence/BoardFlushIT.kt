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
import kotlin.test.assertTrue

/**
 * F4 Wave C2 slice C — 게시판 채널에 대한 Testcontainers IT: board_post INSERT (회의실/기밀실 글)에
 * 이어 SERIAL로 부여된 id를 통해 글을 참조하는 board_comment INSERT (댓글). [JdbcFlushExecutor]가
 * 방출하는 SQL이 실제 V1 baseline board_post/board_comment 테이블에 대해 컬럼이 올바른지 증명한다
 * (id 생략 → DB SERIAL; 댓글 FK는 step-8d에서 글-먼저-댓글-나중 순으로 해소된다).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BoardFlushIT {

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
    fun `board_post INSERT then board_comment INSERT referencing the assigned id`() {
        // 회의실 article + 기밀실 article (INSERT-only, id 생략 → SERIAL).
        executor.flush(
            FlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                boardPostInserts = listOf(
                    BoardPostInsertRow(
                        linkedMapOf(
                            "nation_id" to 1, "is_secret" to false, "author_general_id" to 10,
                            "author_name" to "유비", "title" to "회의 안건", "content_html" to "내용A",
                        ),
                    ),
                    BoardPostInsertRow(
                        linkedMapOf(
                            "nation_id" to 1, "is_secret" to true, "author_general_id" to 11,
                            "author_name" to "관우", "title" to "기밀 안건", "content_html" to "내용B",
                        ),
                    ),
                ),
            ),
        )
        assertEquals(2, count("board_post"))
        val postId = jdbc.queryForObject(
            "SELECT id FROM board_post WHERE title = :t",
            MapSqlParameterSource("t", "회의 안건"), Int::class.java,
        )!!
        assertEquals(false, jdbc.queryForObject(
            "SELECT is_secret FROM board_post WHERE id = :id",
            MapSqlParameterSource("id", postId), Boolean::class.java,
        ))

        // 영속화된 글에 대한 댓글 — FK post_id가 반드시 해소되어야 한다.
        executor.flush(
            FlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                boardCommentInserts = listOf(
                    BoardCommentInsertRow(
                        linkedMapOf(
                            "post_id" to postId, "nation_id" to 1, "is_secret" to false,
                            "author_general_id" to 12, "author_name" to "장비", "content_text" to "동의합니다",
                        ),
                    ),
                ),
            ),
        )
        assertEquals(1, count("board_comment"))
        val row = jdbc.queryForMap(
            "SELECT post_id, author_name, content_text FROM board_comment WHERE post_id = :id",
            MapSqlParameterSource("id", postId),
        )
        assertEquals(postId, row["post_id"])
        assertEquals("장비", row["author_name"])
        assertEquals("동의합니다", row["content_text"])
        assertTrue(executor.lastOps().any { it.table == "board_comment" && it.verb == FlushVerb.CREATE_MANY })
    }

    private fun count(table: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table", MapSqlParameterSource(), Int::class.java)!!

    @Test
    fun `board_post kind and vote_id persist and board_post_read is idempotent per (post, general)`() {
        executor.flush(
            FlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                boardPostInserts = listOf(
                    BoardPostInsertRow(
                        linkedMapOf(
                            "nation_id" to 1, "is_secret" to true, "author_general_id" to 10,
                            "author_name" to "유비", "title" to "표결 안건", "content_html" to "내용", "kind" to "vote", "vote_id" to 3,
                        ),
                    ),
                ),
            ),
        )
        val postId = jdbc.queryForObject(
            "SELECT id FROM board_post WHERE title = :t", MapSqlParameterSource("t", "표결 안건"), Int::class.java,
        )!!
        val row = jdbc.queryForMap("SELECT kind, vote_id FROM board_post WHERE id = :id", MapSqlParameterSource("id", postId))
        assertEquals("vote", row["kind"])
        assertEquals(3, row["vote_id"])

        // 같은 (post, general) 을 두 번 — 첫 열람만 남는다(ON CONFLICT DO NOTHING).
        repeat(2) {
            executor.flush(
                FlushPayload(
                    worldId = opensamguk.common.world.WorldId(1),
                    worldStateUpdate = ws(),
                    boardReadInserts = listOf(
                        BoardReadInsertRow(linkedMapOf("post_id" to postId, "general_id" to 10)),
                        BoardReadInsertRow(linkedMapOf("post_id" to postId, "general_id" to 11)),
                    ),
                ),
            )
        }
        val readRows = jdbc.queryForList(
            "SELECT general_id FROM board_post_read WHERE post_id = :id ORDER BY id", MapSqlParameterSource("id", postId),
        )
        assertEquals(listOf(10, 11), readRows.map { it["general_id"] })
        assertTrue(executor.lastOps().any { it.table == "board_post_read" && it.verb == FlushVerb.CREATE_MANY })
        // 클래스 단위 DB 를 공유하므로 이 테스트의 글을 지운다(열람 기록은 CASCADE) — 다른 테스트의 count 가 흔들리지 않게.
        jdbc.update("DELETE FROM board_post WHERE id = :id", MapSqlParameterSource("id", postId))
    }
}

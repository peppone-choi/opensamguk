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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * W0-8 infra 공유 widen의 잔여 3채널 실DB 증명 — 컨테이너 1개로 묶는다
 * (channel-disjoint: board author_icon / yearbook global_* / inheritance_log.date).
 *
 * PHP 정본:
 *  - board.author_icon VARCHAR(128) NULL (schema.sql:157; j_board_article_add.php:65,73) — V15.
 *  - ng_history map/global_history/global_action/nations JSON 4컬럼 (schema.sql:465;
 *    LogHistory func_history.php:436-448) — V16이 yearbook_history에 global_* 2컬럼 추가.
 *  - user_record.date DATETIME NULL (schema.sql:618; v_inheritPoint.php:74) — V17이
 *    inheritance_log.date 추가.
 *
 * Docker 미가용 시 Testcontainers가 컨테이너를 못 띄우므로 IT는 skip — fail이 아니다 (CLAUDE.md).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class W08SharedWidenFlushIT {

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

    // ── board_post.author_icon (V15, P1-017) ───────────────────────────────────────────────────

    @Test
    fun `board_post author_icon이 영속되고 키 부재 시 NULL이다 -- PHP NULL 패러티`() {
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                boardPostInserts = listOf(
                    BoardPostInsertRow(
                        linkedMapOf(
                            "nation_id" to 1, "is_secret" to false,
                            "author_general_id" to 10, "author_name" to "관우",
                            "author_icon" to "icon/64/guanyu.jpg",
                            "title" to "제목", "content_html" to "<p>본문</p>",
                        ),
                    ),
                    // author_icon 키 없음 → NULL 바인딩 (아이콘 없는 글, PHP NULL DEFAULT NULL).
                    BoardPostInsertRow(
                        linkedMapOf(
                            "nation_id" to 1, "is_secret" to false,
                            "author_general_id" to 11, "author_name" to "장비",
                            "title" to "제목2", "content_html" to "<p>본문2</p>",
                        ),
                    ),
                ),
            ),
        )
        val withIcon = jdbc.jdbcTemplate.queryForMap("SELECT * FROM board_post WHERE author_general_id = 10")
        assertEquals("icon/64/guanyu.jpg", withIcon["author_icon"])
        val without = jdbc.jdbcTemplate.queryForMap("SELECT * FROM board_post WHERE author_general_id = 11")
        assertNull(without["author_icon"])
    }

    // ── yearbook_history global_* (V16, P0-20) ─────────────────────────────────────────────────

    @Test
    fun `yearbook INSERT가 server_id와 global jsonb를 싣고 같은 달 재캡처도 별도 행으로 남긴다`() {
        val base = linkedMapOf<String, Any?>(
            "server_id" to "s1",
            "year" to 181,
            "month" to 1,
            "map" to """{"year":181,"month":1}""",
            "nations" to """[{"nation":1,"name":"후한"}]""",
            "global_history" to """["<C>●</>181년 1월: 중원 정세 갱신"]""",
            "global_action" to """["<C>●</>181년 1월: 장수 동향"]""",
        )
        executor.flush(testFlushPayload(worldId = opensamguk.common.world.WorldId(1), worldStateUpdate = ws(), yearbookInserts = listOf(YearbookInsertRow(base))))

        var row = jdbc.jdbcTemplate.queryForMap("SELECT * FROM yearbook_history WHERE server_id = 's1' AND year = 181 AND month = 1")
        assertEquals("s1", row["server_id"])
        assertEquals("s1", row["profile_name"])
        assertEquals("""["<C>●</>181년 1월: 중원 정세 갱신"]""", row["global_history"].toString())
        assertEquals("""["<C>●</>181년 1월: 장수 동향"]""", row["global_action"].toString())

        val recapture = LinkedHashMap(base).apply { put("global_history", """["갱신된 정세"]""") }
        executor.flush(testFlushPayload(worldId = opensamguk.common.world.WorldId(1), worldStateUpdate = ws(), yearbookInserts = listOf(YearbookInsertRow(recapture))))

        val count = jdbc.jdbcTemplate.queryForObject(
            "SELECT count(*) FROM yearbook_history WHERE server_id = 's1' AND year = 181 AND month = 1", Int::class.java,
        )
        assertEquals(2, count, "PHP ng_history plain INSERT parity keeps both captures")
        row = jdbc.jdbcTemplate.queryForMap(
            "SELECT * FROM yearbook_history WHERE server_id = 's1' AND year = 181 AND month = 1 ORDER BY id DESC LIMIT 1",
        )
        assertEquals("""["갱신된 정세"]""", row["global_history"].toString())
        assertEquals(FlushVerb.CREATE_MANY, executor.lastOps().first { it.table == "yearbook_history" }.verb)
    }

    @Test
    fun `hall은 어느 UNIQUE 충돌도 INSERT IGNORE하고 동일 장수의 더 큰 값만 value와 aux를 갱신한다`() {
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                hallUpserts = listOf(
                    HallUpsertRow(
                        linkedMapOf(
                            "server_id" to "s1", "season" to 1, "scenario" to 0,
                            "general_no" to 10, "type" to "dedication", "value" to 100.0,
                            "owner" to "owner-10", "aux" to """{"name":"관우","rank":"old"}""",
                        ),
                    ),
                ),
            ),
        )
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                hallUpserts = listOf(
                    HallUpsertRow(
                        linkedMapOf(
                            "server_id" to "s1", "season" to 9, "scenario" to 9,
                            "general_no" to 10, "type" to "dedication", "value" to 90.0,
                            "owner" to "owner-10", "aux" to """{"rank":"lower"}""",
                        ),
                    ),
                    HallUpsertRow(
                        linkedMapOf(
                            "server_id" to "s1", "season" to 9, "scenario" to 9,
                            "general_no" to 11, "type" to "dedication", "value" to 300.0,
                            "owner" to "owner-10", "aux" to """{"rank":"owner-conflict"}""",
                        ),
                    ),
                    HallUpsertRow(
                        linkedMapOf(
                            "server_id" to "s1", "season" to 1, "scenario" to 0,
                            "general_no" to 10, "type" to "dedication", "value" to 150.0,
                            "owner" to "owner-10", "aux" to """{"rank":"new-high"}""",
                        ),
                    ),
                ),
            ),
        )

        val row = jdbc.jdbcTemplate.queryForMap(
            "SELECT season, scenario, general_no, value, owner, aux FROM hall WHERE server_id = 's1' AND type = 'dedication'",
        )
        assertEquals(1, row["season"])
        assertEquals(0, row["scenario"])
        assertEquals(10, row["general_no"])
        assertEquals(150.0, row["value"])
        assertEquals("owner-10", row["owner"])
        assertEquals(
            "new-high",
            jdbc.jdbcTemplate.queryForObject(
                "SELECT aux ->> 'rank' FROM hall WHERE server_id = 's1' AND type = 'dedication'",
                String::class.java,
            ),
        )
        assertEquals(1, jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM hall WHERE server_id = 's1'", Int::class.java))
        assertEquals(
            0,
            jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM hall WHERE server_id = 's1' AND scenario = 9", Int::class.java),
        )
        assertEquals(FlushVerb.UPSERT, executor.lastOps().first { it.table == "hall" }.verb)
    }

    // ── inheritance_log.date (V17, P1-043) ─────────────────────────────────────────────────────

    @Test
    fun `inheritance_log date가 영속되고 null이면 NULL이다 -- user_record date 패러티`() {
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                inheritanceLogInserts = listOf(
                    InheritanceLogRow(ownerID = 7, year = 181, month = 1, text = "100 포인트를 베팅에 사용", tag = "inheritPoint", date = "2026-06-10T03:00:00Z"),
                    InheritanceLogRow(ownerID = 7, year = 181, month = 1, text = "스탬프 없는 로그", tag = "inheritPoint"),
                ),
            ),
        )
        val rows = jdbc.jdbcTemplate.queryForList("SELECT text, date FROM inheritance_log WHERE user_id = '7' ORDER BY id ASC")
        assertEquals(2, rows.size)
        assertNotNull(rows[0]["date"], "ISO-8601 스탬프 → timestamptz 영속")
        assertNull(rows[1]["date"], "미스탬프 행은 NULL (PHP user_record.date NULL 허용)")
    }
}

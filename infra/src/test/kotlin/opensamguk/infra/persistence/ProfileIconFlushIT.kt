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
 * OPENSAM-94 프로필 아이콘 sync 채널의 실DB 증명 — general.picture/image_server 전용 컬럼 UPDATE.
 *
 * generalUpdate SET 절이 picture/image_server를 방출하지 않는(officer_city #17류 typed-컬럼 누락)
 * 결함을 재발하지 않도록, 이 채널이 실제로 두 표시-컬럼을 DB에 반영하는지(criterion 6), 그리고 WHERE
 * predicate(`user_id = ? AND npc_state = 0`)가 NPC/타 owner row를 절대 덮어쓰지 않는지(criterion 7)를
 * 실 Postgres로 단언한다.
 *
 * Docker 미가용 시 Testcontainers가 컨테이너를 못 띄우므로 IT는 skip — fail이 아니다 (CLAUDE.md).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfileIconFlushIT {

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
        seedGeneral(10, userId = "7", npcState = 0, picture = "default.jpg", imageServer = 0)  // player
        seedGeneral(11, userId = "7", npcState = 1, picture = "npc.jpg", imageServer = 0)      // same user, NPC
        seedGeneral(12, userId = "99", npcState = 0, picture = "other.jpg", imageServer = 0)   // other owner
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    private fun seedGeneral(id: Int, userId: String, npcState: Int, picture: String, imageServer: Int) {
        jdbc.update(
            """
            INSERT INTO general
                (id, name, nation_id, city_id, leadership, strength, intel, injury,
                 experience, dedication, officer_level, gold, rice, turn_time, last_turn, meta,
                 user_id, npc_state, picture, image_server)
            VALUES
                (:id, :name, 2, 5, 70, 65, 80, 0, 0, 0, 4, 1000, 1000, now(),
                 CAST('{}' AS jsonb), CAST('{}' AS jsonb),
                 :user_id, :npc_state, :picture, :image_server)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id).addValue("name", "장수$id")
                .addValue("user_id", userId).addValue("npc_state", npcState)
                .addValue("picture", picture).addValue("image_server", imageServer),
        )
    }

    private fun row(id: Int, userId: String, picture: String, imageServer: Int) = ProfileIconUpdateRow(
        linkedMapOf("id" to id, "user_id" to userId, "picture" to picture, "image_server" to imageServer),
    )

    private fun pictureOf(id: Int): Pair<String?, Int> {
        val r = jdbc.jdbcTemplate.queryForMap("SELECT picture, image_server FROM general WHERE id = $id")
        return (r["picture"] as String?) to (r["image_server"] as Number).toInt()
    }

    @Test
    fun `eligible player general has picture and image_server persisted to typed columns`() {
        executor.flush(
            FlushPayload(worldStateUpdate = ws(), profileIconUpdates = listOf(row(10, "7", "abcd1234.jpg", 1))),
        )
        assertEquals("abcd1234.jpg" to 1, pictureOf(10), "typed 컬럼에 canonical picture/image_server 반영(criterion 6)")
        assertEquals(FlushVerb.UPDATE, executor.lastOps().last { it.table == "general" }.verb)
    }

    @Test
    fun `where predicate never repaints an NPC row or another owner even with a matching id`() {
        // NPC(npc_state=1) — 같은 user_id로 id를 정확히 겨눠도 WHERE npc_state=0이 막는다.
        // 타 owner(user_id=99) — user_id 불일치로 WHERE가 막는다.
        executor.flush(
            FlushPayload(
                worldStateUpdate = ws(),
                profileIconUpdates = listOf(row(11, "7", "hijack.jpg", 1), row(12, "7", "hijack.jpg", 1)),
            ),
        )
        assertEquals("npc.jpg" to 0, pictureOf(11), "NPC row 불변(criterion 7)")
        assertEquals("other.jpg" to 0, pictureOf(12), "타 owner row 불변(criterion 7)")
    }
}

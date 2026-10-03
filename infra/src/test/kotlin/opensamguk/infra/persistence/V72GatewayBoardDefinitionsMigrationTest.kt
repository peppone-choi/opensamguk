package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class V72GatewayBoardDefinitionsMigrationTest {
    @Test
    fun `definitions preserve old category posts comments and reports and reject orphan posts`() {
        assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — V72 board migration IT skipped")
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()
            fun migrate(target: String) {
                Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                    .locations("classpath:db/migration")
                    .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
                    .target(MigrationVersion.fromVersion(target)).load().migrate()
            }
            migrate("69")
            val jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
            jdbc.update("INSERT INTO users (id, username, password, nickname) VALUES (1, 'fixture', 'encoded', '작성자')")
            jdbc.update("""INSERT INTO gateway_board_post (id, category, author_name, title, content_html)
                VALUES (1, 'FREE', '작성자', '기존 글', '<p>본문</p>')""")
            jdbc.update("""INSERT INTO gateway_board_post (id, category, author_name, title, content_html, deleted_at)
                VALUES (2, 'FREE', '작성자', '기존 지운 글', '<p>본문</p>', now())""")
            jdbc.update("INSERT INTO gateway_board_comment (id, post_id, author_name, content_text) VALUES (1, 1, '작성자', '댓글')")
            jdbc.update("INSERT INTO gateway_board_report (id, post_id, reporter_account_id, reason) VALUES (1, 1, 1, '신고')")
            val before = jdbc.queryForList("SELECT * FROM gateway_board_post ORDER BY id")
            migrate("72")
            assertEquals(before, jdbc.queryForList("SELECT * FROM gateway_board_post ORDER BY id"))
            assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM gateway_board_definition", Int::class.java))
            assertEquals(1, jdbc.queryForObject("SELECT post_id FROM gateway_board_comment WHERE id=1", Int::class.java))
            assertEquals(1, jdbc.queryForObject("SELECT post_id FROM gateway_board_report WHERE id=1", Int::class.java))
            assertFailsWith<DataAccessException> { jdbc.update("DELETE FROM gateway_board_definition WHERE board_key='FREE'") }
            assertFailsWith<DataAccessException> { jdbc.update("UPDATE gateway_board_post SET category='MISSING' WHERE id=1") }
            jdbc.update("INSERT INTO gateway_board_definition (board_key,name) VALUES ('NEW','새 게시판')")
            jdbc.update("UPDATE gateway_board_post SET category='NEW' WHERE category='FREE'")
            jdbc.update("DELETE FROM gateway_board_definition WHERE board_key='FREE'")
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM gateway_board_post WHERE category='NEW'", Int::class.java))
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM gateway_board_comment WHERE post_id=1", Int::class.java))
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM gateway_board_report WHERE post_id=1", Int::class.java))
        }
    }
}

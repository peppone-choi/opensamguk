package opensamguk.boardapi.board

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest
class GatewayBoardMigrationIT {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var postRepository: GatewayBoardPostRepository

    @Test
    fun `V40 creates a gateway-only board schema exactly once`() {
        // Given: a PostgreSQL database bootstrapped through all gateway Flyway migrations.
        val columns = jdbcTemplate.queryForList(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'gateway_board_post'
            """.trimIndent(),
            String::class.java,
        )
        val indexes = jdbcTemplate.queryForList(
            """
            SELECT indexname
            FROM pg_indexes
            WHERE schemaname = 'public' AND tablename = 'gateway_board_post'
            """.trimIndent(),
            String::class.java,
        )

        jdbcTemplate.execute(
            ClassPathResource("db/migration/V40__gateway_board.sql").inputStream.bufferedReader().use { it.readText() },
        )

        // Then: the board is account-scoped, not game-world scoped, and V40 is idempotent.
        assertTrue(columns.contains("author_account_id"))
        assertFalse(columns.contains("world_id"))
        assertTrue(indexes.contains("gateway_board_post_feed_idx"))
        assertTrue(indexes.contains("gateway_board_post_category_feed_idx"))
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '40' AND success",
                Int::class.java,
            ),
        )
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.flyway.postgresql.transactional-lock") { "false" }
        }
    }
    /**
     * PR 비평 S1 적색 프로브 — `(:x IS NULL OR …)` 네이티브 쿼리에 null 세 개를 바인딩하는 기본 목록 경로는 H2 테스트만 타고
     * 있었다. Hibernate 6.6 + PostgreSQL 의 null 바인딩(타입 추론 실패 → "could not determine data type of parameter")이
     * 실제 PG 에서 나지 않는지 여기서 본다. 실패하면 커뮤니티 기본 목록이 프로덕션에서 500 이다.
     */
    @Test
    fun `native feed queries bind null category author and q on real PostgreSQL`() {
        val latest = postRepository.searchLatest(null, null, null, PageRequest.of(0, 10))
        val mine = postRepository.searchLatest(null, 1L, null, PageRequest.of(0, 10))
        val popular = postRepository.searchPopular(null, null, java.time.Instant.now().minusSeconds(7L * 24 * 3600), PageRequest.of(0, 10))
        val filtered = postRepository.searchLatest("FREE", null, "검색", PageRequest.of(0, 10))
        assertTrue(latest.content.isEmpty() && mine.content.isEmpty() && popular.content.isEmpty() && filtered.content.isEmpty())
    }
}

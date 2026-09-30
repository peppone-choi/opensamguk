package opensamguk.boardapi.board

import opensamguk.boardapi.security.BoardUserDetails
import opensamguk.infra.entity.UserEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest
class GatewayBoardDefinitionConcurrencyIT {
    @Autowired lateinit var service: GatewayBoardDefinitionService
    @Autowired lateinit var posts: GatewayBoardPostRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var manager: PlatformTransactionManager

    @Test
    fun `definition deletion cannot race past a post writer and no post is lost`() {
        val admin = BoardUserDetails(UserEntity(username = "lock-admin", password = "encoded", role = "ADMIN"))
        val source = service.create(CreateGatewayBoardDefinitionRequest("LOCK_SOURCE", "원본"), admin)
        val target = service.create(CreateGatewayBoardDefinitionRequest("LOCK_TARGET", "대상"), admin)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        try {
            val writer = workers.submit<Long> {
                TransactionTemplate(manager).execute {
                    service.requireWritable(GatewayBoardCategory(source.key))
                    locked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    requireNotNull(posts.saveAndFlush(GatewayBoardPostEntity(
                        GatewayBoardCategory(source.key), null, "작성자", "동시 생성 글", "본문")).id)
                }!!
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            try {
                // A deterministic lock timeout proves the writer holds the definition row until commit.
                assertFailsWith<DataAccessException> {
                    TransactionTemplate(manager).execute {
                        jdbc.execute("SET LOCAL lock_timeout = '250ms'")
                        service.delete(source.boardId, null, admin)
                    }
                }
            } finally {
                release.countDown()
            }
            val postId = writer.get(10, TimeUnit.SECONDS)
            assertFailsWith<GatewayBoardConflictException> { service.delete(source.boardId, null, admin) }
            service.delete(source.boardId, target.boardId, admin)
            assertEquals(target.key, jdbc.queryForObject("SELECT category FROM gateway_board_post WHERE id=?", String::class.java, postId))
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM gateway_board_post WHERE id=?", Int::class.java, postId))
        } finally {
            release.countDown()
            workers.shutdownNow()
            workers.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
        @DynamicPropertySource @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.flyway.postgresql.transactional-lock") { "false" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.jpa.defer-datasource-initialization") { "false" }
            registry.add("spring.sql.init.mode") { "never" }
        }
    }
}

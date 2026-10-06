package opensamguk.boardapi.board

import opensamguk.boardapi.security.BoardUserDetails
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
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
import java.util.concurrent.ExecutionException
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
    @Autowired lateinit var definitions: GatewayBoardDefinitionRepository
    @Autowired lateinit var boardService: GatewayBoardService
    @Autowired lateinit var users: UserRepository

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

    @Test
    fun `cached source definition cannot authorize edit after readonly commit`() {
        readonlyAfterCachedDefinition(sourceReadonly = true)
    }

    @Test
    fun `cached target definition cannot authorize edit after readonly commit`() {
        readonlyAfterCachedDefinition(sourceReadonly = false)
    }

    private fun readonlyAfterCachedDefinition(sourceReadonly: Boolean) {
        val suffix = if (sourceReadonly) "SRC" else "DST"
        val admin = BoardUserDetails(users.saveAndFlush(UserEntity(
            username = "readonly-${suffix.lowercase()}", password = "encoded", role = "ADMIN")))
        val source = service.create(CreateGatewayBoardDefinitionRequest("RO_${suffix}_SOURCE", "원본"), admin)
        val target = service.create(CreateGatewayBoardDefinitionRequest("RO_${suffix}_TARGET", "대상"), admin)
        val postId = requireNotNull(posts.saveAndFlush(GatewayBoardPostEntity(
            GatewayBoardCategory(source.key), admin.id, "작성자", "권한 검사 전 글", "본문")).id)
        val cached = CountDownLatch(1)
        val committed = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        try {
            val writer = workers.submit<GatewayBoardPostResponse> {
                TransactionTemplate(manager).execute {
                    // Force both definitions into this transaction's first-level
                    // cache before a separate committed writable change.
                    check(definitions.findByKey(source.key)?.writable == true)
                    check(definitions.findByKey(target.key)?.writable == true)
                    cached.countDown()
                    check(committed.await(10, TimeUnit.SECONDS))
                    boardService.updatePost(postId, UpdateGatewayBoardPostRequest(
                        GatewayBoardCategory(target.key), "허용되면 안 되는 수정", "변경 본문"), admin)
                }!!
            }
            assertTrue(cached.await(10, TimeUnit.SECONDS))
            service.update(if (sourceReadonly) source.boardId else target.boardId,
                UpdateGatewayBoardDefinitionRequest(writable = false), admin)
            committed.countDown()
            val failure = assertFailsWith<ExecutionException> { writer.get(10, TimeUnit.SECONDS) }
            assertTrue(failure.cause is GatewayBoardForbiddenException)
            assertEquals("권한 검사 전 글", jdbc.queryForObject(
                "SELECT title FROM gateway_board_post WHERE id=?", String::class.java, postId))
            assertEquals(source.key, jdbc.queryForObject(
                "SELECT category FROM gateway_board_post WHERE id=?", String::class.java, postId))
        } finally {
            committed.countDown()
            workers.shutdownNow()
            workers.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `post deletion preserves committed board move after stale load`() {
        mutateAfterCommittedMove(pin = false)
    }

    @Test
    fun `post pin preserves committed board move after stale load`() {
        mutateAfterCommittedMove(pin = true)
    }

    private fun mutateAfterCommittedMove(pin: Boolean) {
        val suffix = if (pin) "PIN" else "DEL"
        val admin = BoardUserDetails(users.saveAndFlush(UserEntity(
            username = "move-${suffix.lowercase()}", password = "encoded", role = "ADMIN")))
        val source = service.create(CreateGatewayBoardDefinitionRequest("MOVE_${suffix}_SOURCE", "원본"), admin)
        val target = service.create(CreateGatewayBoardDefinitionRequest("MOVE_${suffix}_TARGET", "대상"), admin)
        val postId = requireNotNull(posts.saveAndFlush(GatewayBoardPostEntity(
            GatewayBoardCategory(source.key), admin.id, "작성자", "보존할 글", "보존할 본문")).id)
        val cached = CountDownLatch(1)
        val committed = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        try {
            val writer = workers.submit<GatewayBoardPostResponse?> {
                TransactionTemplate(manager).execute {
                    val stale = posts.findById(postId).orElseThrow()
                    check(stale.category == GatewayBoardCategory(source.key))
                    cached.countDown()
                    check(committed.await(10, TimeUnit.SECONDS))
                    if (pin) boardService.updatePin(postId, UpdateGatewayBoardPinRequest(true), admin)
                    else {
                        boardService.deletePost(postId, admin)
                        null
                    }
                }
            }
            assertTrue(cached.await(10, TimeUnit.SECONDS))
            // This transaction completes the bulk move and removes the source
            // definition before the stale managed entity performs its mutation.
            service.delete(source.boardId, target.boardId, admin)
            committed.countDown()
            val response = writer.get(10, TimeUnit.SECONDS)
            if (pin) assertEquals(GatewayBoardCategory(target.key), requireNotNull(response).category)
            assertEquals(target.key, jdbc.queryForObject(
                "SELECT category FROM gateway_board_post WHERE id=?", String::class.java, postId))
            assertEquals("보존할 글", jdbc.queryForObject(
                "SELECT title FROM gateway_board_post WHERE id=?", String::class.java, postId))
            assertEquals("보존할 본문", jdbc.queryForObject(
                "SELECT content_html FROM gateway_board_post WHERE id=?", String::class.java, postId))
            assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM gateway_board_post WHERE id=?", Int::class.java, postId))
            if (pin) {
                assertEquals(true, jdbc.queryForObject(
                    "SELECT pinned FROM gateway_board_post WHERE id=?", Boolean::class.java, postId))
            } else {
                assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM gateway_board_post WHERE id=? AND deleted_at IS NOT NULL AND deleted_by_account_id=?",
                    Int::class.java, postId, admin.id))
            }
        } finally {
            committed.countDown()
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

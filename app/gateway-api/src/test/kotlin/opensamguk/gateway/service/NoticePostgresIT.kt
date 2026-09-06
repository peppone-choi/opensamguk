package opensamguk.gateway.service

import opensamguk.gateway.dto.NoticeUpsertRequest
import opensamguk.infra.read.GatewayNoticeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers

/** 공개 피드 순서(고정 → 최신)와 soft-delete 마스킹을 실제 PostgreSQL(V51) 로 확인한다. Docker 없으면 skip. */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class NoticePostgresIT {
    @Autowired
    lateinit var repository: GatewayNoticeRepository

    @BeforeEach
    fun reset() {
        repository.deleteAll()
    }

    @Test
    fun `feed puts pinned first then newest and hides soft-deleted rows`() {
        val service = NoticeService(repository)
        val older = service.create(NoticeUpsertRequest("옛 공지", "b"), null)
        Thread.sleep(5)
        val newer = service.create(NoticeUpsertRequest("새 공지", "b"), null)
        Thread.sleep(5)
        val pinned = service.create(NoticeUpsertRequest("고정 공지", "b", pinned = true), null)
        val gone = service.create(NoticeUpsertRequest("지운 공지", "b"), null)
        service.delete(gone.id)

        val feed = service.publicFeed()
        assertEquals(listOf(pinned.id, newer.id, older.id), feed.map { it.id })
        assertEquals(4, service.adminList().size)
        assertEquals(true, service.adminList().first { it.id == gone.id }.deleted)
    }

    companion object {
        @JvmStatic
        @org.testcontainers.junit.jupiter.Container
        val postgres = org.testcontainers.containers.PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @org.springframework.test.context.DynamicPropertySource
        fun props(registry: org.springframework.test.context.DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.flyway.postgresql.transactional-lock") { "false" }
        }
    }
}

package opensamguk.gameapi.read

import opensamguk.gameapi.config.GameApiProcessWorldIdConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

/** 실제 PostgreSQL/JPA에서 소속·방·종류·커서·LIMIT의 결합을 확인한다. skip은 통과 증거가 아니다. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(GameApiProcessWorldIdConfiguration::class, BoardPostReadRepository::class)
class CouncilReadRepositoryIT {
    @Autowired lateinit var posts: BoardPostReadRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    private val time = Instant.parse("2026-10-01T18:00:00Z")
    private fun seed() {
        (1..2).forEach { world -> jdbc.update("""
            INSERT INTO world_state (id,scenario_code,current_year,current_month,tick_seconds,config)
            VALUES (?, ?, 200, 1, 60, '{"worldFormat":"GENERAL_RETAINER_CAMPAIGN"}'::jsonb)
        """, world, "council-$world") }
    }
    private fun insert(id: Int, world: Int = 1, nation: Int = 3, secret: Boolean = false,
                       kind: String = "general", created: Instant = time) {
        jdbc.update("""INSERT INTO board_post
            (world_id,id,nation_id,is_secret,author_general_id,author_name,title,content_html,kind,created_at)
            VALUES (?,?,?,?,101,'작성자',?, ?, ?, CAST(? AS timestamptz))""",
            world, id, nation, secret, "제목-$world-$nation-$id", "본문-$world-$nation-$id", kind, created.toString())
    }

    @Test fun `SQL은 페이지 제한 전에 world 소속 방 종류를 모두 제한하고 동률 커서를 빠뜨리지 않는다`() {
        seed()
        insert(40); insert(41); insert(42, created = time.minusSeconds(1))
        insert(43, secret = true); insert(44, nation = 4)
        insert(41, world = 2); insert(45, kind = "vote"); insert(46, kind = "notice")
        assertEquals(listOf(41, 40), posts.councilPage(3, false, listOf("general"), null, null, 2).map { it.id })
        assertEquals(listOf(40, 42), posts.councilPage(3, false, listOf("general"), time, 41, 51).map { it.id })
        assertEquals(listOf(43), posts.councilPage(3, true, listOf("general"), null, null, 51).map { it.id })
        assertEquals(listOf(46, 41, 40, 42), posts.councilPage(3, false,
            listOf("general", "operation", "notice"), null, null, 51).map { it.id })
        assertTrue(posts.councilPage(5, false, listOf("general"), null, null, 51).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { posts.councilPage(0, false, listOf("general"), null, null, 51) }
    }

    @Test fun `부모 본문은 현재 소속과 허용된 방만 읽고 다른 world 동번호와 타국은 거절한다`() {
        seed(); insert(40); insert(41, secret = true); insert(42, nation = 4)
        insert(40, world = 2); insert(43, world = 2)
        assertEquals("본문-1-3-40", posts.councilArticle(3, 40, false)?.contentHtml)
        assertNull(posts.councilArticle(3, 41, false))
        assertEquals("본문-1-3-41", posts.councilArticle(3, 41, true)?.contentHtml)
        assertNull(posts.councilArticle(3, 42, true))
        assertNull(posts.councilArticle(3, 43, true))
        assertThrows(IllegalArgumentException::class.java) { posts.councilArticle(0, 40, true) }
    }

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}

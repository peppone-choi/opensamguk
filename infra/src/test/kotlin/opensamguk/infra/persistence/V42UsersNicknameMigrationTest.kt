package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V42 닉네임 필수화 마이그레이션 IT.
 *
 * 이 마이그레이션은 **실 데이터를 고쳐 쓴다** — 빈 값을 채우고, 중복을 떼어내고, 그 위에
 * NOT NULL + 유일 인덱스를 건다. 잘못되면 게이트웨이가 부팅에 실패하거나(인덱스 생성 실패)
 * 사용자가 직접 정한 이름이 조용히 바뀐다. 그래서 실 Postgres 위에서 사전 상태를 심고
 * 결과를 확인한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V42UsersNicknameMigrationTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate

    @BeforeAll
    fun setUp() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun resetDatabase() {
        jdbc.execute("DROP SCHEMA public CASCADE")
        jdbc.execute("CREATE SCHEMA public")
        migrateTo("41")
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `V42 백필과 중복 해소 뒤 모든 닉네임이 값이 있고 대소문자 무시로 유일하다`() {
        seedUser(1, "alice", nickname = null)                 // 백필 대상
        seedUser(2, "bob", nickname = "   ")                  // 공백만 = 백필 대상
        seedUser(3, "carol", nickname = " 관도 ")              // 공백 다듬기 대상
        seedUser(4, "dave", nickname = "관도")                 // 3번과 중복
        seedUser(5, "erin", nickname = "KWANDO")              // 대소문자만 다른 중복
        seedUser(6, "frank", nickname = "kwando")

        migrateTo("42")

        assertEquals(0, count("SELECT count(*) FROM users WHERE nickname IS NULL OR btrim(nickname) = ''"))
        assertEquals(
            6,
            count("SELECT count(DISTINCT lower(nickname)) FROM users"),
            "대소문자를 무시해도 6개 모두 서로 달라야 한다",
        )
        // 백필된 행은 아이디를 표시 이름으로 받는다.
        assertEquals("alice", nicknameOf(1))
        assertEquals("bob", nicknameOf(2))
        // 먼저 만든 계정이 자기 이름을 지킨다.
        assertEquals("관도", nicknameOf(3))
        assertTrue(nicknameOf(4).startsWith("관도_"), "밀려난 쪽만 접미사를 받는다: ${nicknameOf(4)}")
        assertEquals("KWANDO", nicknameOf(5))
        assertTrue(nicknameOf(6).startsWith("kwando_"), nicknameOf(6))
    }

    @Test
    fun `V42 는 사용자가 정한 이름을 백필된 이름보다 우선한다`() {
        // id 가 작은 쪽이 백필(아이디 = '관도'), 큰 쪽이 사용자가 직접 정한 '관도'.
        seedUser(1, "관도", nickname = null)
        seedUser(2, "zeta", nickname = "관도")

        migrateTo("42")

        assertEquals("관도", nicknameOf(2), "직접 정한 이름이 남아야 한다")
        assertTrue(nicknameOf(1).startsWith("관도_"), "백필된 쪽이 밀린다: ${nicknameOf(1)}")
    }

    @Test
    fun `V42 는 접미사가 기존 이름과 또 부딪혀도 끝까지 해소한다`() {
        // 중복 해소가 만들어낼 값('관도_2')을 다른 계정이 이미 쓰고 있다 — 한 번만 돌면 실패한다.
        seedUser(1, "alice", nickname = "관도")
        seedUser(2, "bob", nickname = "관도")
        seedUser(3, "carol", nickname = "관도_2")

        migrateTo("42")

        assertEquals(3, count("SELECT count(DISTINCT lower(nickname)) FROM users"))
        assertEquals("관도", nicknameOf(1))
        // 두 바퀴째는 'user_<id>' 로 떨어뜨린다 — 부딪힌 쪽 중 하나만 밀린다.
        assertTrue(nicknameOf(2).startsWith("관도_2") || nicknameOf(2) == "user_2", nicknameOf(2))
        assertTrue(nicknameOf(3) == "관도_2" || nicknameOf(3) == "user_3", nicknameOf(3))
    }

    @Test
    fun `V42 뒤에는 NOT NULL 과 대소문자 무시 유일 인덱스가 실제로 강제된다`() {
        seedUser(1, "alice", nickname = "관도")

        migrateTo("42")

        val nullInsert = runCatching {
            jdbc.update("INSERT INTO users (id, username, password, role, created_at, updated_at) VALUES (2, 'bob', 'x', 'USER', now(), now())")
        }
        assertTrue(nullInsert.isFailure, "닉네임 없는 INSERT 는 막혀야 한다")

        val dupInsert = runCatching {
            jdbc.update("INSERT INTO users (id, username, password, nickname, role, created_at, updated_at) VALUES (3, 'carol', 'x', '관도', 'USER', now(), now())")
        }
        assertTrue(dupInsert.isFailure, "같은 닉네임은 막혀야 한다")

        val caseInsert = runCatching {
            jdbc.update("INSERT INTO users (id, username, password, nickname, role, created_at, updated_at) VALUES (4, 'dave', 'x', 'Guandu', 'USER', now(), now())")
        }
        assertTrue(caseInsert.isSuccess, "다른 이름은 들어가야 한다")
    }

    private fun seedUser(id: Int, username: String, nickname: String?) {
        jdbc.update(
            """
            INSERT INTO users (id, username, password, nickname, role, created_at, updated_at)
            VALUES (?, ?, 'encoded', ?, 'USER', now(), now())
            """.trimIndent(),
            id,
            username,
            nickname,
        )
    }

    private fun nicknameOf(id: Int): String =
        jdbc.queryForObject("SELECT nickname FROM users WHERE id = ?", String::class.java, id)!!

    private fun count(sql: String): Int = jdbc.queryForObject(sql, Int::class.java)!!

    private fun migrateTo(target: String) {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .placeholders(mapOf("scenario_dir" to ""))
            .target(MigrationVersion.fromVersion(target))
            .load()
            .migrate()
    }
}

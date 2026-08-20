package opensamguk.infra.persistence
import opensamguk.infra.entity.BannedMemberEntity
import opensamguk.infra.entity.SystemFlagEntity
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.BannedMemberRepository
import opensamguk.infra.read.EmailHasher
import opensamguk.infra.read.SystemFlagRepository
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
/**
 * V11 어드민 회원관리 마이그레이션 IT — `ddl-auto: validate` 하에서 신규 컬럼/테이블이
 * 엔티티 매핑과 정확히 일치함을 실 Postgres(Testcontainers)로 증명한다(B0-DATA / B2b / B2e).
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V11AdminMemberMigrationIT {
    @Autowired
    lateinit var userRepository: UserRepository
    @Autowired
    lateinit var systemFlagRepository: SystemFlagRepository
    @Autowired
    lateinit var bannedMemberRepository: BannedMemberRepository
    @Test
    fun `B0-DATA -- users round-trips the new admin member columns`() {
        val now = LocalDateTime.now().withNano(0)
        val saved = userRepository.save(
            UserEntity(
                username = "alice",
                password = "enc",
                email = "alice@example.com",
                nickname = "앨리스",
                role = "USER",
                grade = 1,
                blockUntil = now.plusDays(7),
                deleteAfter = now.plusDays(30),
                oauthType = "NONE",
                picture = "default.jpg",
                imgsvr = true,
                lastLoginAt = now,
            ),
        )
        assertNotNull(saved.id)
        val found = userRepository.findById(saved.id).orElseThrow()
        assertEquals(1, found.grade)
        assertEquals(now.plusDays(7), found.blockUntil)
        assertEquals(now.plusDays(30), found.deleteAfter)
        assertEquals("NONE", found.oauthType)
        assertEquals("default.jpg", found.picture)
        assertTrue(found.imgsvr)
        assertEquals(now, found.lastLoginAt)
    }
    @Test
    fun `B0-DATA -- new admin columns are nullable (divergence -- grade null by default)`() {
        val saved = userRepository.save(
            UserEntity(username = "bob", password = "enc", role = "USER", nickname = "bob"),
        )
        val found = userRepository.findById(saved.id).orElseThrow()
        // grade는 0.9.0 divergence로 미사용 → null. imgsvr만 NOT NULL(기본 false).
        assertEquals(null, found.grade)
        assertEquals(null, found.blockUntil)
        assertEquals(null, found.deleteAfter)
        assertEquals(null, found.oauthType)
        assertEquals(null, found.picture)
        assertFalse(found.imgsvr)
        assertEquals(null, found.lastLoginAt)
    }
    @Test
    fun `B2b -- system_flag singleton row is seeded and maps`() {
        // V11이 id=1 단일 행을 미허용(false/false) 기본으로 시드한다(legacy DEFAULT 'N').
        val seeded = systemFlagRepository.findSingleton()
        assertNotNull(seeded)
        assertEquals(SystemFlagRepository.SINGLETON_ID, seeded!!.id)
        assertFalse(seeded.allowJoin)
        assertFalse(seeded.allowLogin)
        assertEquals("", seeded.notice)
        // 어드민 토글(B2b)이 갱신할 경로 — round-trip 확인.
        seeded.allowJoin = true
        seeded.allowLogin = true
        seeded.notice = "공지"
        systemFlagRepository.saveAndFlush(seeded)
        val updated = systemFlagRepository.findSingleton()!!
        assertTrue(updated.allowJoin)
        assertTrue(updated.allowLogin)
        assertEquals("공지", updated.notice)
    }
    @Test
    fun `B2e -- banned_member maps and existsByHashedEmail works`() {
        val hasher = EmailHasher("goldensalt")
        val hash = hasher.hash("ban@me.com")
        // legacy hash('sha512', ...) = 소문자 hex 128자.
        assertEquals(128, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" })
        assertFalse(bannedMemberRepository.existsByHashedEmail(hash))
        bannedMemberRepository.save(
            BannedMemberEntity(hashedEmail = hash, info = "2026-06-08 00:00:00"),
        )
        assertTrue(bannedMemberRepository.existsByHashedEmail(hash))
    }
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}

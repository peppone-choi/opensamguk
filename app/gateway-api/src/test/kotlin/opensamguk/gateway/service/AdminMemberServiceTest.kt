package opensamguk.gateway.service

import opensamguk.gateway.security.AdminMemberGuard
import opensamguk.gateway.security.SelfPeerProtectionException
import opensamguk.infra.entity.SystemFlagEntity
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.BannedMemberRepository
import opensamguk.infra.read.EmailHasher
import opensamguk.infra.read.SystemFlagRepository
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.LocalDateTime
import java.util.Optional

/**
 * B2 회원관리 서비스 슬라이스 테스트 — 루트DB 어드민 명령(legacy j_get_userlist/j_set_userlist/BanEmailAddress).
 * self/peer 보호 거부([AdminMemberGuard])를 포함한다. Mockito 단위(Docker 불요) — AuthServiceTest 패턴(mockito-core only).
 */
@ExtendWith(MockitoExtension::class)
class AdminMemberServiceTest {

    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var systemFlagRepository: SystemFlagRepository
    @Mock lateinit var bannedMemberRepository: BannedMemberRepository
    @Mock lateinit var serverRegistry: ServerRegistry

    private val emailHasher = EmailHasher("goldensalt")
    private val guard = AdminMemberGuard()
    private val passwordEncoder = BCryptPasswordEncoder()
    private lateinit var service: AdminMemberService

    @BeforeEach
    fun setUp() {
        service = AdminMemberService(
            userRepository,
            systemFlagRepository,
            bannedMemberRepository,
            emailHasher,
            guard,
            passwordEncoder,
            serverRegistry,
        )
    }

    private fun user(
        id: Long,
        role: String = "USER",
        grade: Int? = null,
        deleteAfter: LocalDateTime? = null,
        lastLoginAt: LocalDateTime? = null,
    ) = UserEntity(
        id = id,
        username = "u$id",
        password = "enc",
        email = "u$id@x.com",
        nickname = "닉$id",
        role = role,
        grade = grade,
        deleteAfter = deleteAfter,
        lastLoginAt = lastLoginAt,
    )

    // ── B2a 목록 ──

    @Test
    fun `B2a listUsers returns sorted users + flags + servers`() {
        `when`(userRepository.findAll()).thenReturn(listOf(user(2L, role = "ADMIN"), user(1L)))
        `when`(systemFlagRepository.findSingleton())
            .thenReturn(SystemFlagEntity(id = 1, allowJoin = true, allowLogin = false))
        `when`(serverRegistry.all()).thenReturn(
            listOf(ServerDef("main", "통일", "http://a", "http://e", "opensamguk")),
        )

        val res = service.listUsers()

        assertEquals(listOf(1L, 2L), res.users.map { it.id }) // id 오름차순
        assertEquals("일반", res.users[0].gradeLabel) // USER + grade null → 일반
        assertEquals("운영자", res.users[1].gradeLabel) // ADMIN + grade null → 운영자
        assertTrue(res.allowJoin)
        assertFalse(res.allowLogin)
        assertEquals(listOf("main"), res.servers)
    }

    @Test
    fun `B2a gradeLabel maps legacy grade values`() {
        `when`(userRepository.findAll()).thenReturn(
            listOf(
                user(1L, grade = 0), user(2L, grade = 1), user(3L, grade = 4),
                user(4L, grade = 5), user(5L, grade = 6), user(6L, grade = 9),
            ),
        )
        `when`(systemFlagRepository.findSingleton()).thenReturn(null)
        `when`(serverRegistry.all()).thenReturn(emptyList())

        val labels = service.listUsers().users.map { it.gradeLabel }
        assertEquals(listOf("차단", "일반", "특별", "부운영자", "운영자", "9"), labels)
    }

    @Test
    fun `B2a flags fall back to false when system row missing`() {
        `when`(userRepository.findAll()).thenReturn(emptyList())
        `when`(systemFlagRepository.findSingleton()).thenReturn(null)
        `when`(serverRegistry.all()).thenReturn(emptyList())

        val res = service.listUsers()
        assertFalse(res.allowJoin)
        assertFalse(res.allowLogin)
    }

    // ── B2b 시스템 플래그 ──

    @Test
    fun `B2b setAllowLogin updates singleton`() {
        val flag = SystemFlagEntity(id = 1, allowJoin = false, allowLogin = false)
        `when`(systemFlagRepository.findSingleton()).thenReturn(flag)
        `when`(systemFlagRepository.save(any(SystemFlagEntity::class.java))).thenAnswer { it.getArgument(0) }

        val res = service.setAllowLogin(true)
        assertTrue(res.allowLogin)
        assertFalse(res.allowJoin)
    }

    @Test
    fun `B2b setAllowJoin creates singleton when missing`() {
        `when`(systemFlagRepository.findSingleton()).thenReturn(null)
        `when`(systemFlagRepository.save(any(SystemFlagEntity::class.java))).thenAnswer { it.getArgument(0) }

        val res = service.setAllowJoin(true)
        assertTrue(res.allowJoin)
    }

    // ── B2c 정리 ──

    @Test
    fun `B2c scrubDeleted removes delete_after before today`() {
        val past = LocalDateTime.now().minusDays(2)
        val future = LocalDateTime.now().plusDays(2)
        `when`(userRepository.findAll()).thenReturn(
            listOf(user(1L, deleteAfter = past), user(2L, deleteAfter = future), user(3L)),
        )

        val res = service.scrubDeleted()
        assertEquals(1, res.affected)

        // deleteAll 인자가 id=1만 담았는지 캡처로 검증.
        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(Iterable::class.java) as ArgumentCaptor<Iterable<UserEntity>>
        verify(userRepository).deleteAll(captor.capture())
        assertEquals(listOf(1L), captor.value.map { it.id })
    }

    @Test
    fun `B2c scrubOldUsers removes 6mo+ inactive and never-logged-in`() {
        val recent = LocalDateTime.now().minusDays(10)
        val old = LocalDateTime.now().minusMonths(8)
        `when`(userRepository.findAll()).thenReturn(
            listOf(
                user(1L, lastLoginAt = recent), // 최근 → 유지
                user(2L, lastLoginAt = old), // 6개월+ → 정리
                user(3L, lastLoginAt = null), // 기록 없음 → 정리
            ),
        )

        val res = service.scrubOldUsers()
        assertEquals(2, res.affected)
    }

    // ── B2d 회원 명령 ──

    @Test
    fun `B2d delete removes target`() {
        val target = user(2L)
        `when`(userRepository.findById(2L)).thenReturn(Optional.of(target))

        val res = service.runUserCommand(1L, "ADMIN", 2L, "delete", null)
        assertTrue(res.result)
        verify(userRepository).delete(target)
    }

    @Test
    fun `B2d reset_pw returns legacy detail string and re-encodes`() {
        val target = user(2L)
        `when`(userRepository.findById(2L)).thenReturn(Optional.of(target))
        `when`(userRepository.save(any(UserEntity::class.java))).thenAnswer { it.getArgument(0) }

        val res = service.runUserCommand(1L, "ADMIN", 2L, "reset_pw", null)
        assertTrue(res.result)
        assertNotNull(res.detail)
        // legacy `:210` 문구 패러티.
        assertTrue(res.detail!!.startsWith("비밀번호가 "))
        assertTrue(res.detail!!.endsWith("로 초기화되었습니다."))
        val temp = res.detail!!.removePrefix("비밀번호가 ").removeSuffix("로 초기화되었습니다.")
        assertEquals(6, temp.length) // randomStr(6)
        assertTrue(passwordEncoder.matches(temp, target.password)) // 평문→인코딩 일치
    }

    @Test
    fun `B2d block with positive param sets grade0 and blockUntil`() {
        val target = user(2L)
        `when`(userRepository.findById(2L)).thenReturn(Optional.of(target))
        `when`(userRepository.save(any(UserEntity::class.java))).thenAnswer { it.getArgument(0) }

        val res = service.runUserCommand(1L, "ADMIN", 2L, "block", 7)
        assertTrue(res.result)
        assertEquals(0, target.grade)
        assertNotNull(target.blockUntil)
        assertTrue(target.blockUntil!!.isAfter(LocalDateTime.now().plusDays(6)))
    }

    @Test
    fun `B2d block with non-positive param uses 50 years`() {
        val target = user(2L)
        `when`(userRepository.findById(2L)).thenReturn(Optional.of(target))
        `when`(userRepository.save(any(UserEntity::class.java))).thenAnswer { it.getArgument(0) }

        service.runUserCommand(1L, "ADMIN", 2L, "block", 0)
        // 50*365일 ≈ 49년 후보다 뒤.
        assertTrue(target.blockUntil!!.isAfter(LocalDateTime.now().plusYears(49)))
    }

    @Test
    fun `B2d block missing param is rejected`() {
        val target = user(2L)
        `when`(userRepository.findById(2L)).thenReturn(Optional.of(target))

        val res = service.runUserCommand(1L, "ADMIN", 2L, "block", null)
        assertFalse(res.result)
        assertEquals("올바르지 않은 param", res.reason)
        verify(userRepository, never()).save(any(UserEntity::class.java))
    }

    @Test
    fun `B2d unblock sets grade1 and clears blockUntil`() {
        val target = user(2L, grade = 0).apply { blockUntil = LocalDateTime.now().plusDays(10) }
        `when`(userRepository.findById(2L)).thenReturn(Optional.of(target))
        `when`(userRepository.save(any(UserEntity::class.java))).thenAnswer { it.getArgument(0) }

        val res = service.runUserCommand(1L, "ADMIN", 2L, "unblock", null)
        assertTrue(res.result)
        assertEquals(1, target.grade)
        assertNull(target.blockUntil)
    }

    @Test
    fun `B2d set_userlevel sets grade`() {
        val target = user(2L)
        `when`(userRepository.findById(2L)).thenReturn(Optional.of(target))
        `when`(userRepository.save(any(UserEntity::class.java))).thenAnswer { it.getArgument(0) }

        val res = service.runUserCommand(1L, "ADMIN", 2L, "set_userlevel", 4)
        assertTrue(res.result)
        assertEquals(4, target.grade)
    }

    @Test
    fun `B2d set_userlevel rejects level greater-equal operator grade`() {
        val target = user(2L)
        `when`(userRepository.findById(2L)).thenReturn(Optional.of(target))

        val res = service.runUserCommand(1L, "ADMIN", 2L, "set_userlevel", 6)
        assertFalse(res.result)
        assertEquals("관리자보다 같거나 높은 등급을 설정할 수 없습니다.", res.reason)
    }

    @Test
    fun `B2d set_userlevel rejects param less than 1`() {
        val target = user(2L)
        `when`(userRepository.findById(2L)).thenReturn(Optional.of(target))

        val res = service.runUserCommand(1L, "ADMIN", 2L, "set_userlevel", 0)
        assertFalse(res.result)
        assertEquals("올바르지 않은 param", res.reason)
    }

    @Test
    fun `B2d missing target user returns not-found`() {
        `when`(userRepository.findById(99L)).thenReturn(Optional.empty())

        val res = service.runUserCommand(1L, "ADMIN", 99L, "delete", null)
        assertFalse(res.result)
        assertEquals("해당하는 유저가 없습니다.", res.reason)
    }

    @Test
    fun `B2d unknown action is rejected`() {
        val target = user(2L)
        `when`(userRepository.findById(2L)).thenReturn(Optional.of(target))

        val res = service.runUserCommand(1L, "ADMIN", 2L, "frobnicate", null)
        assertFalse(res.result)
        assertTrue(res.reason!!.startsWith("알 수 없는 명령입니다."))
    }

    // ── B2d self/peer 보호 거부 ──

    @Test
    fun `B2d self target is rejected (self protection)`() {
        val self = user(1L, role = "ADMIN")
        `when`(userRepository.findById(1L)).thenReturn(Optional.of(self))

        val ex = assertThrows(SelfPeerProtectionException::class.java) {
            service.runUserCommand(1L, "ADMIN", 1L, "delete", null)
        }
        assertEquals("자기 자신은 변경할 수 없습니다.", ex.message)
        verify(userRepository, never()).delete(any(UserEntity::class.java))
    }

    @Test
    fun `B2d other ADMIN target is rejected (peer protection)`() {
        val peer = user(2L, role = "ADMIN")
        `when`(userRepository.findById(2L)).thenReturn(Optional.of(peer))

        val ex = assertThrows(SelfPeerProtectionException::class.java) {
            service.runUserCommand(1L, "ADMIN", 2L, "block", 7)
        }
        assertEquals("다른 운영자는 변경할 수 없습니다.", ex.message)
        verify(userRepository, never()).save(any(UserEntity::class.java))
    }

    @Test
    fun `B2d non-ADMIN actor is rejected defensively`() {
        val target = user(2L)
        `when`(userRepository.findById(2L)).thenReturn(Optional.of(target))

        assertThrows(SelfPeerProtectionException::class.java) {
            service.runUserCommand(3L, "USER", 2L, "delete", null)
        }
    }

    // ── B2e 영구차단 ──

    @Test
    fun `B2e banEmail inserts hash and returns legacy reason`() {
        `when`(bannedMemberRepository.existsByHashedEmail(emailHasher.hash("ban@x.com"))).thenReturn(false)
        `when`(bannedMemberRepository.save(any())).thenAnswer { it.getArgument(0) }

        val res = service.banEmail("ban@x.com")
        assertTrue(res.result)
        assertEquals("등록되었습니다.", res.reason)
        verify(bannedMemberRepository, times(1)).save(any())
    }

    @Test
    fun `B2e banEmail duplicate is rejected`() {
        `when`(bannedMemberRepository.existsByHashedEmail(emailHasher.hash("dup@x.com"))).thenReturn(true)

        val res = service.banEmail("dup@x.com")
        assertFalse(res.result)
        assertEquals("이미 등록된 이메일입니다.", res.reason)
        verify(bannedMemberRepository, never()).save(any())
    }
}

package opensamguk.gateway.service

import opensamguk.common.auth.GatewayProfileClaims
import opensamguk.gateway.dto.LoginRequest
import opensamguk.gateway.dto.RegisterRequest
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.gateway.security.JwtTokenProvider
import opensamguk.infra.entity.SystemFlagEntity
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.BannedMemberRepository
import opensamguk.infra.read.EmailHasher
import opensamguk.infra.read.SystemFlagRepository
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class AuthServiceTest {

    @Mock
    lateinit var userRepository: UserRepository

    @Mock
    lateinit var authenticationManager: AuthenticationManager

    @Mock
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Mock
    lateinit var systemFlagRepository: SystemFlagRepository

    @Mock
    lateinit var bannedMemberRepository: BannedMemberRepository

    lateinit var authService: AuthService
    private val passwordEncoder = BCryptPasswordEncoder()
    // GLOBAL_SALT 미설정 시 legacy 기본값 'goldensalt'와 동일.
    private val emailHasher = EmailHasher("goldensalt")

    /** allow_join/allow_login 둘 다 허용된 시스템 플래그(기본 정상 경로). */
    private fun allowAllFlag() = SystemFlagEntity(id = 1, allowJoin = true, allowLogin = true)

    private fun anyProfile(): GatewayProfileClaims =
        any(GatewayProfileClaims::class.java) ?: GatewayProfileClaims(0, "", "USER", null, 1, null, 0)

    private fun captureProfile(captor: ArgumentCaptor<GatewayProfileClaims>): GatewayProfileClaims =
        captor.capture() ?: GatewayProfileClaims(0, "", "USER", null, 1, null, 0)

    @BeforeEach
    fun setUp() {
        authService = AuthService(
            userRepository,
            passwordEncoder,
            jwtTokenProvider,
            authenticationManager,
            systemFlagRepository,
            bannedMemberRepository,
            emailHasher,
        )
    }

    @Test
    fun `register new user`() {
        `when`(systemFlagRepository.findSingleton()).thenReturn(allowAllFlag())
        `when`(userRepository.existsByUsername("newuser")).thenReturn(false)
        `when`(userRepository.save(org.mockito.ArgumentMatchers.any(UserEntity::class.java))).thenAnswer {
            val user = it.getArgument<UserEntity>(0)
            UserEntity(id = 1L, username = user.username, password = user.password)
        }
        `when`(jwtTokenProvider.generateAccessToken(anyProfile())).thenReturn("access-token")
        `when`(jwtTokenProvider.generateRefreshToken(anyLong())).thenReturn("refresh-token")

        val result = authService.register(RegisterRequest("newuser", "password123", null, "새유저"))

        val savedUser = ArgumentCaptor.forClass(UserEntity::class.java)
        verify(userRepository).save(savedUser.capture() ?: UserEntity(username = "", password = ""))
        assertEquals("새유저", savedUser.value.nickname)
        assertEquals("newuser", result.user.username)
        assertEquals("access-token", result.accessToken)
        assertEquals("refresh-token", result.refreshToken)
    }

    @Test
    fun `register duplicate username throws`() {
        `when`(systemFlagRepository.findSingleton()).thenReturn(allowAllFlag())
        `when`(userRepository.existsByUsername("existing")).thenReturn(true)

        assertThrows(IllegalArgumentException::class.java) {
            authService.register(RegisterRequest("existing", "password123", null, "새유저"))
        }
    }

    @Test
    fun `register duplicate nickname throws`() {
        `when`(systemFlagRepository.findSingleton()).thenReturn(allowAllFlag())
        `when`(userRepository.existsByUsername("newuser")).thenReturn(false)
        `when`(userRepository.existsByNickname("새유저")).thenReturn(true)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            authService.register(RegisterRequest("newuser", "password123", null, "  새유저  "))
        }
        assertEquals("이미 사용 중인 닉네임입니다: 새유저", ex.message)
    }

    @Test
    fun `register blocked when allow_join is false (B2b)`() {
        `when`(systemFlagRepository.findSingleton())
            .thenReturn(SystemFlagEntity(id = 1, allowJoin = false, allowLogin = true))

        val ex = assertThrows(IllegalArgumentException::class.java) {
            authService.register(RegisterRequest("newuser", "password123", null, "새유저"))
        }
        assertEquals("현재는 가입이 금지되어있습니다!", ex.message)
    }

    @Test
    fun `register blocked when email is banned (B2e)`() {
        `when`(systemFlagRepository.findSingleton()).thenReturn(allowAllFlag())
        `when`(bannedMemberRepository.existsByHashedEmail(emailHasher.hash("ban@me.com"))).thenReturn(true)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            authService.register(RegisterRequest("newuser", "password123", "ban@me.com", "새유저"))
        }
        assertEquals("가입할 수 없는 이메일입니다.", ex.message)
    }

    @Test
    fun `login with valid credentials`() {
        `when`(systemFlagRepository.findSingleton()).thenReturn(allowAllFlag())
        val user = UserEntity(
            id = 1L,
            username = "testuser",
            password = passwordEncoder.encode("pass123"),
            nickname = "테스터",
            grade = 4,
            picture = "profile.jpg",
            imgsvr = true,
        )
        `when`(authenticationManager.authenticate(org.mockito.ArgumentMatchers.any())).thenReturn(
            UsernamePasswordAuthenticationToken(CustomUserDetails(user), null, emptyList())
        )
        `when`(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user))
        `when`(jwtTokenProvider.generateAccessToken(anyProfile())).thenReturn("access-token")
        `when`(jwtTokenProvider.generateRefreshToken(anyLong())).thenReturn("refresh-token")

        val result = authService.login(LoginRequest("testuser", "pass123"))

        assertEquals("testuser", result.user.username)
        assertEquals("access-token", result.accessToken)
        val profile = ArgumentCaptor.forClass(GatewayProfileClaims::class.java)
        verify(jwtTokenProvider).generateAccessToken(captureProfile(profile))
        assertEquals("테스터", profile.value.nickname)
        assertEquals(4, profile.value.grade)
        assertEquals("profile.jpg", profile.value.picture)
        assertEquals(1, profile.value.imageServer)
    }

    @Test
    fun `login blocked when allow_login is false and not admin (B2b)`() {
        `when`(systemFlagRepository.findSingleton())
            .thenReturn(SystemFlagEntity(id = 1, allowJoin = true, allowLogin = false))
        val user = UserEntity(id = 1L, username = "testuser", password = passwordEncoder.encode("pass123"))
        `when`(authenticationManager.authenticate(org.mockito.ArgumentMatchers.any())).thenReturn(
            UsernamePasswordAuthenticationToken(CustomUserDetails(user), null, emptyList())
        )
        `when`(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user))

        val ex = assertThrows(IllegalArgumentException::class.java) {
            authService.login(LoginRequest("testuser", "pass123"))
        }
        assertEquals("현재는 로그인이 금지되어있습니다!", ex.message)
    }

    @Test
    fun `login allowed for ADMIN even when allow_login is false (grade override divergence)`() {
        `when`(systemFlagRepository.findSingleton())
            .thenReturn(SystemFlagEntity(id = 1, allowJoin = true, allowLogin = false))
        val admin = UserEntity(
            id = 9L,
            username = "peppone",
            password = passwordEncoder.encode("pass123"),
            role = "ADMIN",
        )
        `when`(authenticationManager.authenticate(org.mockito.ArgumentMatchers.any())).thenReturn(
            UsernamePasswordAuthenticationToken(CustomUserDetails(admin), null, emptyList())
        )
        `when`(userRepository.findByUsername("peppone")).thenReturn(Optional.of(admin))
        `when`(jwtTokenProvider.generateAccessToken(anyProfile())).thenReturn("access-token")
        `when`(jwtTokenProvider.generateRefreshToken(anyLong())).thenReturn("refresh-token")

        val result = authService.login(LoginRequest("peppone", "pass123"))

        assertEquals("peppone", result.user.username)
        assertEquals("access-token", result.accessToken)
    }

    @Test
    fun `refresh with valid token`() {
        val user = UserEntity(id = 1L, username = "testuser", password = "encoded", nickname = "testuser")
        `when`(jwtTokenProvider.validateRefreshToken("refresh-token")).thenReturn(true)
        `when`(jwtTokenProvider.getUserIdFromToken("refresh-token")).thenReturn(1L)
        `when`(userRepository.findById(1L)).thenReturn(Optional.of(user))
        `when`(jwtTokenProvider.generateAccessToken(anyProfile())).thenReturn("new-access")
        `when`(jwtTokenProvider.generateRefreshToken(anyLong())).thenReturn("new-refresh")

        val result = authService.refresh("refresh-token")

        assertEquals("testuser", result.user.username)
        assertEquals("new-access", result.accessToken)
        val profile = ArgumentCaptor.forClass(GatewayProfileClaims::class.java)
        verify(jwtTokenProvider).generateAccessToken(captureProfile(profile))
        assertEquals(1, profile.value.grade)
        assertEquals(0, profile.value.imageServer)
    }
}

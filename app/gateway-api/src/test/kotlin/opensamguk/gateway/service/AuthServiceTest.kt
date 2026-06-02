package opensamguk.gateway.service

import opensamguk.gateway.dto.LoginRequest
import opensamguk.gateway.dto.RegisterRequest
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.gateway.security.JwtTokenProvider
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
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

    lateinit var authService: AuthService
    private val passwordEncoder = BCryptPasswordEncoder()

    @BeforeEach
    fun setUp() {
        authService = AuthService(userRepository, passwordEncoder, jwtTokenProvider, authenticationManager)
    }

    @Test
    fun `register new user`() {
        `when`(userRepository.existsByUsername("newuser")).thenReturn(false)
        `when`(userRepository.save(org.mockito.ArgumentMatchers.any(UserEntity::class.java))).thenAnswer {
            val user = it.getArgument<UserEntity>(0)
            UserEntity(id = 1L, username = user.username, password = user.password)
        }
        `when`(jwtTokenProvider.generateAccessToken(anyLong(), anyString(), anyString())).thenReturn("access-token")
        `when`(jwtTokenProvider.generateRefreshToken(anyLong())).thenReturn("refresh-token")

        val result = authService.register(RegisterRequest("newuser", "password123", null, null))

        assertEquals("newuser", result.user.username)
        assertEquals("access-token", result.accessToken)
        assertEquals("refresh-token", result.refreshToken)
    }

    @Test
    fun `register duplicate username throws`() {
        `when`(userRepository.existsByUsername("existing")).thenReturn(true)

        assertThrows(IllegalArgumentException::class.java) {
            authService.register(RegisterRequest("existing", "password123", null, null))
        }
    }

    @Test
    fun `login with valid credentials`() {
        val user = UserEntity(id = 1L, username = "testuser", password = passwordEncoder.encode("pass123"))
        `when`(authenticationManager.authenticate(org.mockito.ArgumentMatchers.any())).thenReturn(
            UsernamePasswordAuthenticationToken(CustomUserDetails(user), null, emptyList())
        )
        `when`(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user))
        `when`(jwtTokenProvider.generateAccessToken(anyLong(), anyString(), anyString())).thenReturn("access-token")
        `when`(jwtTokenProvider.generateRefreshToken(anyLong())).thenReturn("refresh-token")

        val result = authService.login(LoginRequest("testuser", "pass123"))

        assertEquals("testuser", result.user.username)
        assertEquals("access-token", result.accessToken)
    }

    @Test
    fun `refresh with valid token`() {
        val user = UserEntity(id = 1L, username = "testuser", password = "encoded")
        `when`(jwtTokenProvider.validateToken("refresh-token")).thenReturn(true)
        `when`(jwtTokenProvider.getUserIdFromToken("refresh-token")).thenReturn(1L)
        `when`(userRepository.findById(1L)).thenReturn(Optional.of(user))
        `when`(jwtTokenProvider.generateAccessToken(anyLong(), anyString(), anyString())).thenReturn("new-access")
        `when`(jwtTokenProvider.generateRefreshToken(anyLong())).thenReturn("new-refresh")

        val result = authService.refresh("refresh-token")

        assertEquals("testuser", result.user.username)
        assertEquals("new-access", result.accessToken)
    }
}

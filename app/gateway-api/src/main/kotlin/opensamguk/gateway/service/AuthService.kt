package opensamguk.gateway.service

import opensamguk.gateway.dto.AuthResponse
import opensamguk.gateway.dto.LoginRequest
import opensamguk.gateway.dto.RegisterRequest
import opensamguk.gateway.dto.UserResponse
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.gateway.security.JwtTokenProvider
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.BannedMemberRepository
import opensamguk.infra.read.EmailHasher
import opensamguk.infra.read.SystemFlagRepository
import opensamguk.infra.read.UserRepository
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenProvider: JwtTokenProvider,
    private val authenticationManager: AuthenticationManager,
    private val systemFlagRepository: SystemFlagRepository,
    private val bannedMemberRepository: BannedMemberRepository,
    private val emailHasher: EmailHasher,
) {

    @Transactional
    fun register(request: RegisterRequest): AuthResponse {
        // B2b: 전역 가입 허용 게이트 — legacy system.REG (allow_join). 행 부재 시 미허용 폴백.
        val systemFlag = systemFlagRepository.findSingleton()
        if (systemFlag == null || !systemFlag.allowJoin) {
            throw IllegalArgumentException("현재는 가입이 금지되어있습니다!")
        }
        // B2e: 영구차단 이메일 검사 — legacy banned_member(sha512(salt|email|salt)).
        if (request.email != null && bannedMemberRepository.existsByHashedEmail(emailHasher.hash(request.email))) {
            throw IllegalArgumentException("가입할 수 없는 이메일입니다.")
        }
        if (userRepository.existsByUsername(request.username)) {
            throw IllegalArgumentException("이미 사용 중인 아이디입니다: ${request.username}")
        }
        if (request.email != null && userRepository.existsByEmail(request.email)) {
            throw IllegalArgumentException("이미 사용 중인 이메일입니다: ${request.email}")
        }
        val user = UserEntity(
            username = request.username,
            password = passwordEncoder.encode(request.password),
            email = request.email,
            nickname = request.nickname,
        )
        val saved = userRepository.save(user)
        val accessToken = jwtTokenProvider.generateAccessToken(saved.id, saved.username, saved.role)
        val refreshToken = jwtTokenProvider.generateRefreshToken(saved.id)
        return AuthResponse(accessToken, refreshToken, saved.toResponse())
    }

    @Transactional(readOnly = true)
    fun login(request: LoginRequest): AuthResponse {
        val auth = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken(request.username, request.password)
        )
        val userDetails = auth.principal as CustomUserDetails
        val user = userRepository.findByUsername(userDetails.username)
            .orElseThrow { IllegalArgumentException("사용자를 찾을 수 없습니다.") }
        // B2b: 전역 로그인 허용 게이트 — legacy `LOGIN != 'Y' && grade < 5` deny.
        // 0.9.0 divergence: grade≥5(부운영자+) 우회 = role==ADMIN 우회. 행 부재 시 미허용 폴백.
        val systemFlag = systemFlagRepository.findSingleton()
        if ((systemFlag == null || !systemFlag.allowLogin) && user.role != "ADMIN") {
            throw IllegalArgumentException("현재는 로그인이 금지되어있습니다!")
        }
        val accessToken = jwtTokenProvider.generateAccessToken(user.id, user.username, user.role)
        val refreshToken = jwtTokenProvider.generateRefreshToken(user.id)
        return AuthResponse(accessToken, refreshToken, user.toResponse())
    }

    @Transactional(readOnly = true)
    fun refresh(refreshToken: String): AuthResponse {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw IllegalArgumentException("유효하지 않은 리프레시 토큰입니다.")
        }
        val userId = jwtTokenProvider.getUserIdFromToken(refreshToken)
            ?: throw IllegalArgumentException("토큰에서 사용자 ID를 추출할 수 없습니다.")
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("사용자를 찾을 수 없습니다.") }
        val accessToken = jwtTokenProvider.generateAccessToken(user.id, user.username, user.role)
        val newRefreshToken = jwtTokenProvider.generateRefreshToken(user.id)
        return AuthResponse(accessToken, newRefreshToken, user.toResponse())
    }

    @Transactional(readOnly = true)
    fun me(userDetails: CustomUserDetails): UserResponse {
        val user = userRepository.findByUsername(userDetails.username)
            .orElseThrow { IllegalArgumentException("사용자를 찾을 수 없습니다.") }
        return user.toResponse()
    }

    private fun UserEntity.toResponse(): UserResponse = UserResponse(
        id = id,
        username = username,
        email = email,
        nickname = nickname,
        role = role,
    )
}

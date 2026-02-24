package com.opensam.gateway.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.opensam.gateway.config.JwtUtil
import com.opensam.gateway.entity.AppUser
import com.opensam.gateway.repository.AppUserRepository
import com.opensam.shared.dto.AuthResponse
import com.opensam.shared.dto.LoginRequest
import com.opensam.shared.dto.RegisterRequest
import com.opensam.shared.dto.UserInfo
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.OffsetDateTime
import java.util.UUID

@Service
class AuthService(
    private val appUserRepository: AppUserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtUtil: JwtUtil,
    private val systemSettingsService: SystemSettingsService,
    @Value("\${KAKAO_REST_API_KEY:}") private val kakaoRestApiKey: String,
) {
    private val http = HttpClient.newBuilder().build()
    private val mapper = ObjectMapper()

    fun register(request: RegisterRequest): AuthResponse {
        val flags = systemSettingsService.getAuthFlags()
        if (!flags.allowJoin) {
            throw IllegalStateException("Registration is disabled")
        }
        if (appUserRepository.existsByLoginId(request.loginId)) {
            throw IllegalArgumentException("Login ID already exists")
        }

        val user = AppUser(
            loginId = request.loginId,
            displayName = request.displayName,
            passwordHash = passwordEncoder.encode(request.password),
        )
        val saved = appUserRepository.save(user)

        val role = effectiveRole(saved)
        val token = jwtUtil.generateToken(saved.id, saved.loginId, saved.displayName, role, saved.grade.toInt())
        return AuthResponse(token, UserInfo(saved.id, saved.loginId, saved.displayName))
    }

    fun login(request: LoginRequest): AuthResponse {
        val flags = systemSettingsService.getAuthFlags()
        if (!flags.allowLogin) {
            throw IllegalStateException("Login is disabled")
        }
        val user = appUserRepository.findByLoginId(request.loginId)
            ?: throw IllegalArgumentException("Invalid credentials")

        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            throw IllegalArgumentException("Invalid credentials")
        }

        ensureNotBlocked(user)
        return loginUser(user)
    }

    fun oauthLogin(provider: String, code: String, redirectUri: String): AuthResponse {
        val flags = systemSettingsService.getAuthFlags()
        if (!flags.allowLogin) throw IllegalStateException("Login is disabled")
        if (provider.lowercase() != "kakao") throw IllegalArgumentException("Unsupported oauth provider")

        val profile = fetchKakaoProfile(code, redirectUri)
        val user = findUserByOAuth("kakao", profile.id)
            ?: throw IllegalArgumentException("OAuth account is not registered")

        ensureNotBlocked(user)
        return loginUser(user)
    }

    fun oauthRegister(provider: String, code: String, redirectUri: String, displayName: String?): AuthResponse {
        val flags = systemSettingsService.getAuthFlags()
        if (!flags.allowJoin) throw IllegalStateException("Registration is disabled")
        if (provider.lowercase() != "kakao") throw IllegalArgumentException("Unsupported oauth provider")

        val profile = fetchKakaoProfile(code, redirectUri)
        val existing = findUserByOAuth("kakao", profile.id)
        if (existing != null) {
            ensureNotBlocked(existing)
            return loginUser(existing)
        }

        val baseLoginId = "kakao_${profile.id}"
        val loginId = generateUniqueLoginId(baseLoginId)
        val name = (displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: profile.nickname?.trim()?.takeIf { it.isNotEmpty() }
            ?: "카카오${profile.id.takeLast(6)}")

        val meta = mutableMapOf<String, Any>()
        meta["oauthProviders"] = mutableListOf(
            mutableMapOf<String, Any>(
                "provider" to "kakao",
                "externalId" to profile.id,
                "linkedAt" to OffsetDateTime.now().toString(),
            ),
        )

        val user = AppUser(
            loginId = loginId,
            displayName = name,
            passwordHash = passwordEncoder.encode(UUID.randomUUID().toString()),
            meta = meta,
        )
        val saved = appUserRepository.save(user)
        return loginUser(saved)
    }

    private fun fetchKakaoProfile(code: String, redirectUri: String): KakaoProfile {
        if (kakaoRestApiKey.isBlank()) {
            throw IllegalStateException("Kakao OAuth is not configured")
        }

        val form = "grant_type=authorization_code" +
            "&client_id=$kakaoRestApiKey" +
            "&redirect_uri=${urlEncode(redirectUri)}" +
            "&code=${urlEncode(code)}"

        val tokenReq = HttpRequest.newBuilder(URI.create("https://kauth.kakao.com/oauth/token"))
            .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val tokenRes = http.send(tokenReq, HttpResponse.BodyHandlers.ofString())
        if (tokenRes.statusCode() !in 200..299) {
            throw IllegalArgumentException("Failed to exchange oauth token")
        }
        val accessToken = mapper.readTree(tokenRes.body())["access_token"]?.asText()
            ?: throw IllegalArgumentException("Invalid oauth token response")

        val meReq = HttpRequest.newBuilder(URI.create("https://kapi.kakao.com/v2/user/me"))
            .header("Authorization", "Bearer $accessToken")
            .GET()
            .build()
        val meRes = http.send(meReq, HttpResponse.BodyHandlers.ofString())
        if (meRes.statusCode() !in 200..299) {
            throw IllegalArgumentException("Failed to fetch oauth user")
        }
        val json = mapper.readTree(meRes.body())
        val id = json["id"]?.asText() ?: throw IllegalArgumentException("Invalid oauth user payload")
        val nickname = json.path("properties").path("nickname").asText(null)
        return KakaoProfile(id = id, nickname = nickname)
    }

    private fun findUserByOAuth(provider: String, externalId: String): AppUser? {
        return appUserRepository.findAll().firstOrNull { user ->
            val providers = user.meta["oauthProviders"] as? List<*> ?: return@firstOrNull false
            providers.any {
                val p = it as? Map<*, *> ?: return@any false
                p["provider"]?.toString() == provider && p["externalId"]?.toString() == externalId
            }
        }
    }

    private fun generateUniqueLoginId(base: String): String {
        if (!appUserRepository.existsByLoginId(base)) return base
        var i = 1
        while (appUserRepository.existsByLoginId("${base}_$i")) i++
        return "${base}_$i"
    }

    private fun loginUser(user: AppUser): AuthResponse {
        val role = effectiveRole(user)
        user.role = role
        user.lastLoginAt = OffsetDateTime.now()
        appUserRepository.save(user)

        val token = jwtUtil.generateToken(user.id, user.loginId, user.displayName, role, user.grade.toInt())
        return AuthResponse(token, UserInfo(user.id, user.loginId, user.displayName))
    }

    private fun ensureNotBlocked(user: AppUser) {
        val blockedUntil = (user.meta["blockedUntil"] as? String)?.let {
            runCatching { OffsetDateTime.parse(it) }.getOrNull()
        }
        if (user.grade.toInt() == 0) {
            if (blockedUntil == null || blockedUntil.isAfter(OffsetDateTime.now())) {
                throw IllegalStateException("User is blocked")
            }
        }
    }

    private fun effectiveRole(user: AppUser): String {
        return if (user.grade.toInt() >= 5) "ADMIN" else "USER"
    }

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    data class KakaoProfile(
        val id: String,
        val nickname: String?,
    )
}

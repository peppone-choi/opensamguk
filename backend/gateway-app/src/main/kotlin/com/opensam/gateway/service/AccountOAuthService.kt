package com.opensam.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.opensam.gateway.entity.AppUser
import com.opensam.gateway.repository.AppUserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

@Service
class AccountOAuthService(
    private val appUserRepository: AppUserRepository,
    @Value("\${KAKAO_REST_API_KEY:}") private val kakaoRestApiKey: String,
    @Value("\${OAUTH_ACCOUNT_LINK_CALLBACK_URI:}") private val configuredCallbackUri: String,
) {
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().build()

    data class OAuthProviderInfo(
        val provider: String,
        val externalId: String,
        val linkedAt: String,
    )

    data class OAuthLinkStartResponse(
        val redirectUrl: String,
        val provider: String,
        val state: String,
        val callbackUri: String,
    )

    fun getOAuthProviders(userId: Long): List<OAuthProviderInfo> {
        val user = findUser(userId)
        return getProviderEntries(user).mapNotNull { entry ->
            val provider = entry["provider"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val externalId = entry["externalId"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val linkedAt = entry["linkedAt"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            OAuthProviderInfo(provider = provider, externalId = externalId, linkedAt = linkedAt)
        }
    }

    fun startLinkOAuth(userId: Long, providerRaw: String, requestOrigin: String?): OAuthLinkStartResponse {
        val user = findUser(userId)
        val provider = providerRaw.lowercase()

        if (provider != "kakao") {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported oauth provider")
        }
        if (kakaoRestApiKey.isBlank()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Kakao OAuth is not configured")
        }

        val alreadyLinked = getProviderEntries(user).any { it["provider"]?.toString()?.lowercase() == provider }
        if (alreadyLinked) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "OAuth provider already linked")
        }

        val callbackUri = configuredCallbackUri.takeIf { it.isNotBlank() }
            ?: requestOrigin?.takeIf { it.isNotBlank() }?.let { "$it/auth/kakao/callback" }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth callback URI is not configured")

        val statePayload = mapOf(
            "action" to "link",
            "provider" to provider,
            "uid" to user.id,
            "nonce" to UUID.randomUUID().toString(),
            "issuedAt" to OffsetDateTime.now().toString(),
        )
        val state = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mapper.writeValueAsBytes(statePayload))

        val redirectUrl = "https://kauth.kakao.com/oauth/authorize" +
            "?response_type=code" +
            "&client_id=${urlEncode(kakaoRestApiKey)}" +
            "&redirect_uri=${urlEncode(callbackUri)}" +
            "&state=${urlEncode(state)}"

        return OAuthLinkStartResponse(
            redirectUrl = redirectUrl,
            provider = provider,
            state = state,
            callbackUri = callbackUri,
        )
    }

    fun completeLinkOAuth(userId: Long, providerRaw: String, code: String, redirectUri: String) {
        val user = findUser(userId)
        val provider = providerRaw.lowercase()
        if (provider != "kakao") {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported oauth provider")
        }
        if (kakaoRestApiKey.isBlank()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Kakao OAuth is not configured")
        }

        val externalId = fetchKakaoUserId(code, redirectUri)

        val existingOwner = appUserRepository.findAll().firstOrNull { candidate ->
            getProviderEntries(candidate).any {
                it["provider"]?.toString()?.lowercase() == provider &&
                    it["externalId"]?.toString() == externalId &&
                    candidate.id != user.id
            }
        }
        if (existingOwner != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "OAuth account already linked to another user")
        }

        val providers = getProviderEntries(user).toMutableList()
        val now = OffsetDateTime.now().toString()
        val existingIdx = providers.indexOfFirst { it["provider"]?.toString()?.lowercase() == provider }
        val newEntry = mutableMapOf<String, Any>(
            "provider" to provider,
            "externalId" to externalId,
            "linkedAt" to now,
        )

        if (existingIdx >= 0) providers[existingIdx] = newEntry
        else providers.add(newEntry)

        user.meta["oauthProviders"] = providers
        appUserRepository.save(user)
    }

    fun unlinkOAuth(userId: Long, providerRaw: String) {
        val user = findUser(userId)
        val provider = providerRaw.lowercase()

        val currentProviders = getProviderEntries(user).toMutableList()
        val filtered = currentProviders.filterNot { it["provider"]?.toString()?.lowercase() == provider }

        if (filtered.size == currentProviders.size) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth provider is not linked")
        }

        val removingLast = filtered.isEmpty()
        val oauthCreatedId = user.loginId.lowercase().startsWith("${provider}_")
        if (removingLast && oauthCreatedId) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Cannot unlink the last OAuth provider for this account")
        }

        if (filtered.isEmpty()) {
            user.meta.remove("oauthProviders")
        } else {
            user.meta["oauthProviders"] = filtered.toMutableList()
        }
        appUserRepository.save(user)
    }

    private fun fetchKakaoUserId(code: String, redirectUri: String): String {
        val form = "grant_type=authorization_code" +
            "&client_id=${urlEncode(kakaoRestApiKey)}" +
            "&redirect_uri=${urlEncode(redirectUri)}" +
            "&code=${urlEncode(code)}"

        val tokenReq = HttpRequest.newBuilder(URI.create("https://kauth.kakao.com/oauth/token"))
            .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val tokenRes = http.send(tokenReq, HttpResponse.BodyHandlers.ofString())
        if (tokenRes.statusCode() !in 200..299) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to exchange oauth token")
        }

        val accessToken = mapper.readTree(tokenRes.body())["access_token"]?.asText()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid oauth token response")

        val meReq = HttpRequest.newBuilder(URI.create("https://kapi.kakao.com/v2/user/me"))
            .header("Authorization", "Bearer $accessToken")
            .GET()
            .build()
        val meRes = http.send(meReq, HttpResponse.BodyHandlers.ofString())
        if (meRes.statusCode() !in 200..299) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to fetch oauth user")
        }

        return mapper.readTree(meRes.body())["id"]?.asText()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid oauth user payload")
    }

    private fun findUser(userId: Long): AppUser {
        return appUserRepository.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found")
        }
    }

    private fun getProviderEntries(user: AppUser): List<Map<String, Any?>> {
        val providers = user.meta["oauthProviders"] as? List<*> ?: return emptyList()
        return providers.mapNotNull { it as? Map<*, *> }
            .map { raw -> raw.entries.associate { it.key.toString() to it.value } }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

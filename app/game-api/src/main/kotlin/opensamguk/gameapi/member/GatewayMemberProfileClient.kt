package opensamguk.gameapi.member

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.http.HttpClient
import java.time.Duration

@Component
class GatewayMemberProfileClient(
    private val restClient: RestClient,
    private val serviceToken: String,
) : MemberProfileClient {
    @Autowired
    constructor(
        @Value("\${member-profile.gateway-origin:http://localhost:8080}") gatewayOrigin: String,
        @Value("\${member-profile.service-token:}") serviceToken: String,
    ) : this(buildRestClient(gatewayOrigin), serviceToken)

    override fun get(userId: Long): MemberProfile? {
        if (serviceToken.isBlank()) throw MemberProfileUnavailableException()
        return try {
            restClient.get()
                .uri("/internal/users/{id}/profile", userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $serviceToken")
                .retrieve()
                .body(MemberProfile::class.java)
                ?.takeIf { it.name.isNotBlank() && it.grade in 0..9 && it.imageServer in 0..1 }
                ?: throw MemberProfileUnavailableException()
        } catch (_: HttpClientErrorException.NotFound) {
            null
        } catch (error: RestClientException) {
            throw MemberProfileUnavailableException(error)
        }
    }

    private companion object {
        fun buildRestClient(gatewayOrigin: String): RestClient {
            val httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build()
            val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
                setReadTimeout(Duration.ofSeconds(2))
            }
            return RestClient.builder()
                .baseUrl(gatewayOrigin.trimEnd('/'))
                .requestFactory(requestFactory)
                .build()
        }
    }
}

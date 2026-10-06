package opensamguk.gameapi.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ServerAdmissionConfiguration {
    @Bean
    fun serverAdmissionSource(
        @Value("\${server-admission.gateway-origin}") origin: String,
        @Value("\${server-admission.server-id}") serverId: String,
        @Value("\${server-admission.service-token}") serviceToken: String,
    ): ServerAdmissionSource = GatewayServerAdmissionSource(origin, serverId, serviceToken)

    @Bean
    fun serverAdmissionPolicy(source: ServerAdmissionSource) = ServerAdmissionPolicy(source)
}

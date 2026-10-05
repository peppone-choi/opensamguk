package opensamguk.gateway.d101.infra

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.application.D101ExecutionService
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.D101PurposeGrantVerifier
import opensamguk.gateway.d101.security.UnavailableD101PurposeAuthority
import opensamguk.gateway.publication.domain.ServerPublicationRepository
import opensamguk.gateway.publication.domain.ServerPublicationWriter
import opensamguk.gateway.service.ServerRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

@Configuration
internal class D101Configuration {
    @Bean
    fun d101ExecutionService(
        mapper: ObjectMapper, jdbc: JdbcTemplate, source: ServerPublicationRepository,
        writer: ServerPublicationWriter, registry: ServerRegistry,
    ): D101ExecutionService {
        val json = D101StrictJson(mapper)
        val codec = D101RequestCodec(json, D101ApprovalIntentCodec(json))
        val store = JdbcD101ExecutionStore(jdbc, source, writer, registry, codec)
        // Actual approval/custody/clock/selected-byte and Root phase providers remain unimplemented.
        // There is no env flag or caller-supplied key that changes this default.
        return D101ExecutionService(json, codec, D101PurposeGrantVerifier(json, UnavailableD101PurposeAuthority()), store)
    }
}

package opensamguk.gateway.d101.infra

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.application.D101ExecutionService
import opensamguk.gateway.d101.domain.D101ApprovalIntentCodec
import opensamguk.gateway.d101.domain.D101RequestCodec
import opensamguk.gateway.d101.domain.D101StrictJson
import opensamguk.gateway.d101.security.D101PurposeGrantVerifier
import opensamguk.gateway.d101.security.D101RecoveryAuthority
import opensamguk.gateway.d101.security.UnavailableD101DispatchAuthority
import opensamguk.gateway.d101.security.UnavailableD101PurposeAuthority
import opensamguk.gateway.d101.security.UnavailableD101RecoveryAuthority
import opensamguk.gateway.d101.security.UnavailableD101TerminalAuthority
import opensamguk.gateway.publication.domain.ServerPublicationRepository
import opensamguk.gateway.publication.domain.ServerPublicationWriter
import opensamguk.gateway.service.ServerRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

/** Retain the existing unavailable endpoints and durable operation guards.
 * The unused native trust installer and signed Root readers have been removed. */
@Configuration
internal class D101Configuration {
    @Bean
    fun d101PreResetOriginalsStore(jdbc: JdbcTemplate, mapper: ObjectMapper): JdbcD101PreResetOriginalsStore =
        JdbcD101PreResetOriginalsStore(jdbc, mapper)

    @Bean
    fun d101RecoveryPurposeVerifier(mapper: ObjectMapper): D101PurposeGrantVerifier =
        D101PurposeGrantVerifier(D101StrictJson(mapper), UnavailableD101PurposeAuthority())

    @Bean
    fun d101RecoveryAuthority(): D101RecoveryAuthority = UnavailableD101RecoveryAuthority()

    @Bean
    fun d101ExecutionService(
        mapper: ObjectMapper, jdbc: JdbcTemplate, source: ServerPublicationRepository,
        writer: ServerPublicationWriter, registry: ServerRegistry,
        preResetOriginals: JdbcD101PreResetOriginalsStore,
    ): D101ExecutionService {
        val json = D101StrictJson(mapper)
        val codec = D101RequestCodec(json, D101ApprovalIntentCodec(json))
        val store = JdbcD101ExecutionStore(jdbc, source, writer, registry, codec, preResetOriginals)
        return D101ExecutionService(
            json, codec, D101PurposeGrantVerifier(json, UnavailableD101PurposeAuthority()), store,
            UnavailableD101DispatchAuthority(), UnavailableD101TerminalAuthority(),
        )
    }
}

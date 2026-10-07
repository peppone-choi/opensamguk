package opensamguk.gateway.d101.infra

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.application.D101RecoveryService
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.D101PurposeGrantVerifier
import opensamguk.gateway.d101.security.D101RecoveryAuthority
import opensamguk.gateway.publication.domain.ServerPublicationRepository
import opensamguk.gateway.publication.domain.ServerPublicationWriter
import opensamguk.gateway.service.ServerRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

/** Preserve the recovery endpoints with the unavailable production authorities. */
@Configuration
internal class D101RecoveryConfiguration {
    @Bean
    fun d101RecoveryService(
        mapper: ObjectMapper, jdbc: JdbcTemplate, publication: ServerPublicationRepository,
        publisher: ServerPublicationWriter, registry: ServerRegistry,
        verifier: D101PurposeGrantVerifier, authority: D101RecoveryAuthority,
    ): D101RecoveryService {
        val json = D101StrictJson(mapper)
        val executionCodec = D101RequestCodec(json, D101ApprovalIntentCodec(json))
        val recoveryCodec = D101RecoveryRequestCodec(json)
        val executions = JdbcD101ExecutionStore(jdbc, publication, publisher, registry, executionCodec)
        val store = JdbcD101RecoveryStore(jdbc, publication, registry, executions, recoveryCodec, json)
        return D101RecoveryService(json, recoveryCodec, verifier, executions, store, authority)
    }

    @Bean
    fun d101RecoveryBeginReader(
        mapper: ObjectMapper, jdbc: JdbcTemplate, publication: ServerPublicationRepository,
        publisher: ServerPublicationWriter, registry: ServerRegistry,
    ): D101RecoveryBeginReader {
        val json = D101StrictJson(mapper)
        val executions = JdbcD101ExecutionStore(jdbc, publication, publisher, registry,
            D101RequestCodec(json, D101ApprovalIntentCodec(json)))
        return JdbcD101RecoveryStore(jdbc, publication, registry, executions, D101RecoveryRequestCodec(json), json)
    }
}

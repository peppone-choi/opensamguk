package opensamguk.gateway.d101.infra

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.application.D101RecoveryService
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.*
import opensamguk.gateway.publication.domain.ServerPublicationRepository
import opensamguk.gateway.publication.domain.ServerPublicationWriter
import opensamguk.gateway.service.ServerRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

/** A recovery authority is installed only by C8's approved trust provider.
 * Missing any member of the fixed pair keeps both mutation routes unavailable. */
@Configuration
internal class D101RecoveryConfiguration {
    @Bean
    fun d101RecoveryService(
        mapper: ObjectMapper, jdbc: JdbcTemplate, publication: ServerPublicationRepository,
        publisher: ServerPublicationWriter, registry: ServerRegistry,
        purposeSources: ObjectProvider<D101PurposeAuthority>, rootBindings: ObjectProvider<D101RootReaderBinding>,
        recoverySources: ObjectProvider<D101RecoveryAuthority>,
    ): D101RecoveryService {
        val json = D101StrictJson(mapper)
        val executionCodec = D101RequestCodec(json, D101ApprovalIntentCodec(json))
        val recoveryCodec = D101RecoveryRequestCodec(json)
        val executions = JdbcD101ExecutionStore(jdbc, publication, publisher, registry, executionCodec)
        val store = JdbcD101RecoveryStore(jdbc, publication, registry, executions, recoveryCodec)
        val purpose = purposeSources.ifAvailable
        val root = rootBindings.ifAvailable
        val recovery = recoverySources.ifAvailable
        val ready = purpose != null && root != null && recovery != null
        return D101RecoveryService(
            json, recoveryCodec,
            D101PurposeGrantVerifier(json, if (ready) requireNotNull(purpose) else UnavailableD101PurposeAuthority()),
            executions, store, if (ready) requireNotNull(recovery) else UnavailableD101RecoveryAuthority(),
        )
    }
}

package opensamguk.gateway.d101.infra

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.application.D101ExecutionService
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.*
import opensamguk.gateway.publication.domain.ServerPublicationRepository
import opensamguk.gateway.publication.domain.ServerPublicationWriter
import opensamguk.gateway.service.ServerRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.beans.factory.ObjectProvider
import java.net.URI

/** Supplied only by the approved deployment trust installer. No request/env
 * switch creates this binding, a purpose authority, a token or a key. */
internal class D101RootReaderBinding(val fixedPrivateOrigin: URI, private val rootToken: () -> String) {
    fun token(): String = rootToken()
}

@Configuration
internal class D101Configuration {
    @Bean
    fun d101ExecutionService(
        mapper: ObjectMapper, jdbc: JdbcTemplate, source: ServerPublicationRepository,
        writer: ServerPublicationWriter, registry: ServerRegistry,
        purposeSources: ObjectProvider<D101PurposeAuthority>, rootBindings: ObjectProvider<D101RootReaderBinding>,
        installedTrusts: ObjectProvider<D101InstalledDeploymentTrust>,
    ): D101ExecutionService {
        val json = D101StrictJson(mapper)
        val codec = D101RequestCodec(json, D101ApprovalIntentCodec(json))
        val store = JdbcD101ExecutionStore(jdbc, source, writer, registry, codec)
        val installed = installedTrusts.ifAvailable
        val suppliedPurpose = purposeSources.ifAvailable
        val suppliedRoot = rootBindings.ifAvailable
        // Never combine an installed pair with unrelated individual providers.
        val mixed = installed != null && (suppliedPurpose != null || suppliedRoot != null)
        val purposeSource = if (mixed) null else (installed?.purpose ?: suppliedPurpose)
        val root = if (mixed) null else (installed?.root ?: suppliedRoot)
        val providersReady = purposeSource != null && root != null
        // PREPARE also reserves identity and closes publication. The purpose
        // verifier must stay unavailable until the actual Root binding exists.
        val purpose = if (providersReady) requireNotNull(purposeSource) else UnavailableD101PurposeAuthority()
        // Both actual approved providers must exist before constructing readers.
        // Missing trust keeps the service's original unavailable adapters.
        val dispatch = if (purposeSource != null && root != null) D101VerifiedDispatchAuthorityAdapter(
            D101RootPreparedProofClient(root.fixedPrivateOrigin, root::token, purposeSource, mapper = mapper),
        ) else UnavailableD101DispatchAuthority()
        val terminal = if (purposeSource != null && root != null) D101VerifiedTerminalAuthorityAdapter(
            D101RootExecutionResultClient(root.fixedPrivateOrigin, root::token, purposeSource, mapper = mapper),
        ) else UnavailableD101TerminalAuthority()
        return D101ExecutionService(json, codec, D101PurposeGrantVerifier(json, purpose), store, dispatch, terminal)
    }
}

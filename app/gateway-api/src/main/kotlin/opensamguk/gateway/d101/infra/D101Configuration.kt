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
    fun d101PreResetOriginalsStore(jdbc: JdbcTemplate, mapper: ObjectMapper): JdbcD101PreResetOriginalsStore =
        JdbcD101PreResetOriginalsStore(jdbc, mapper)

    @Bean
    fun d101RecoveryPurposeVerifier(mapper: ObjectMapper,
        purposeSources: ObjectProvider<D101PurposeAuthority>, rootBindings: ObjectProvider<D101RootReaderBinding>,
        installedTrusts: ObjectProvider<D101InstalledDeploymentTrust>): D101PurposeGrantVerifier {
        val providers = recoveryProviders(purposeSources, rootBindings, installedTrusts)
        return D101PurposeGrantVerifier(D101StrictJson(mapper), providers?.first ?: UnavailableD101PurposeAuthority())
    }

    @Bean
    fun d101RecoveryAuthority(mapper: ObjectMapper,
        purposeSources: ObjectProvider<D101PurposeAuthority>, rootBindings: ObjectProvider<D101RootReaderBinding>,
        installedTrusts: ObjectProvider<D101InstalledDeploymentTrust>): D101RecoveryAuthority {
        val (purpose, root) = recoveryProviders(purposeSources, rootBindings, installedTrusts)
            ?: return UnavailableD101RecoveryAuthority()
        return D101VerifiedRecoveryAuthorityAdapter(
            D101RootExecutionResultClient(root.fixedPrivateOrigin, root::token, purpose, mapper = mapper),
            D101RootRecoveryResultClient(root.fixedPrivateOrigin, root::token, purpose, mapper = mapper))
    }

    private fun recoveryProviders(purposeSources: ObjectProvider<D101PurposeAuthority>,
        rootBindings: ObjectProvider<D101RootReaderBinding>, installedTrusts: ObjectProvider<D101InstalledDeploymentTrust>
    ): Pair<D101PurposeAuthority, D101RootReaderBinding>? {
        val installed = installedTrusts.ifAvailable
        val purpose = purposeSources.ifAvailable
        val root = rootBindings.ifAvailable
        if (installed != null) return if (purpose == null && root == null) installed.purpose to installed.root else null
        return if (purpose != null && root != null) purpose to root else null
    }

    @Bean
    fun d101ExecutionService(
        mapper: ObjectMapper, jdbc: JdbcTemplate, source: ServerPublicationRepository,
        writer: ServerPublicationWriter, registry: ServerRegistry,
        purposeSources: ObjectProvider<D101PurposeAuthority>, rootBindings: ObjectProvider<D101RootReaderBinding>,
        installedTrusts: ObjectProvider<D101InstalledDeploymentTrust>,
        preResetOriginals: JdbcD101PreResetOriginalsStore = JdbcD101PreResetOriginalsStore(jdbc, mapper),
    ): D101ExecutionService {
        val json = D101StrictJson(mapper)
        val codec = D101RequestCodec(json, D101ApprovalIntentCodec(json))
        val store = JdbcD101ExecutionStore(jdbc, source, writer, registry, codec, preResetOriginals)
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

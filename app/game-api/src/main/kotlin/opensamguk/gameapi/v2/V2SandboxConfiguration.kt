package opensamguk.gameapi.v2

import opensamguk.infra.sandbox.SandboxGate
import opensamguk.infra.sandbox.SandboxMarker
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * OPENSAM-35 0A-b — v2 bean-registration gate for game-api.
 *
 * Its conditions and semantics match `opensamguk.engine.v2.V2SandboxConfiguration` (`@Profile` AND
 * `@ConditionalOnProperty`, unset is disabled). v2 read/intake beans will also belong to game-api (round-3
 * proposal §7.1-2), so both applications need this gate; otherwise another application's v2 beans could be
 * unconditionally registered in its production context.
 *
 * Package `opensamguk.gameapi.v2` lies inside `GameApiApplication`'s component-scan root
 * (`opensamguk.gameapi`). v2 does not use JPA, so `@EntityScan` and `@EnableJpaRepositories` base packages are
 * irrelevant.
 *
 * Future concrete v2 beans, including read/intake controllers, belong here as `@Bean` methods.
 */
@Configuration(proxyBeanMethods = false)
@Profile(SandboxGate.PROFILE)
@ConditionalOnProperty(name = [SandboxGate.PROPERTY], havingValue = "true", matchIfMissing = false)
class V2SandboxConfiguration {
    @Bean
    fun v2SandboxMarker(): SandboxMarker = SandboxMarker()
}

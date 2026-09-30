package opensamguk.gameapi.sandbox

import opensamguk.infra.sandbox.SandboxGate
import opensamguk.infra.sandbox.SandboxMarker
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * OPENSAM-35 0A-b — sandbox bean-registration gate for game-api.
 *
 * Its conditions and semantics match `opensamguk.engine.sandbox.SandboxConfiguration` (`@Profile` AND
 * `@ConditionalOnProperty`, unset is disabled). sandbox read/intake beans will also belong to game-api (round-3
 * proposal §7.1-2), so both applications need this gate; otherwise another application's sandbox beans could be
 * unconditionally registered in its production context.
 *
 * Package `opensamguk.gameapi.sandbox` lies inside `GameApiApplication`'s component-scan root
 * (`opensamguk.gameapi`). sandbox does not use JPA, so `@EntityScan` and `@EnableJpaRepositories` base packages are
 * irrelevant.
 *
 * Future concrete sandbox beans, including read/intake controllers, belong here as `@Bean` methods.
 */
@Configuration(proxyBeanMethods = false)
@Profile(SandboxGate.PROFILE)
@ConditionalOnProperty(name = [SandboxGate.PROPERTY], havingValue = "true", matchIfMissing = false)
class SandboxConfiguration {
    @Bean
    fun sandboxMarker(): SandboxMarker = SandboxMarker()
}

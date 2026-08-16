package opensamguk.engine.v2

import opensamguk.infra.v2.V2ContentCatalog
import opensamguk.infra.v2.V2CityCatalogAdapter
import opensamguk.infra.v2.V2SandboxGate
import opensamguk.infra.v2.V2SandboxMarker
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * OPENSAM-35 0A-b — v2 bean-registration gate for game-engine.
 *
 * `@Profile` and `@ConditionalOnProperty` are combined with AND. If either condition is false, Spring skips this
 * `@Configuration` and registers none of its beans. `matchIfMissing = false` is the default, but remains explicit
 * because the gate direction is load-bearing: an unset `V2_ENABLED` is disabled.
 *
 * This class belongs in package `opensamguk.engine.v2`, inside `GameEngineApplication`'s
 * `@SpringBootApplication` component-scan root (`opensamguk.engine`). It needs no existing-file modification to be
 * registered.
 *
 * Since OPENSAM-189 this package IS inside `HotColdCatalog.runtimeSourceDirectories` and
 * `DaemonWriteGuard.writePathPackages`: v2 code reaches the daemon write path through `ChangeRecorder`, so it
 * carries the same JDBC-only and cataloged-read obligations as v1. Its own reads/writes are none, so the guards
 * are vacuously satisfied here.
 *
 * Future concrete v2 beans, including ledger stores and command handlers, belong here as `@Bean` methods, and
 * each bean name must be added to `APPROVED_V2_BEAN_NAMES` in
 * `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ProductionContextBeanGateIT.kt` (OPENSAM-184). A v2
 * bean outside the gate (for example, an `@Component`) violates 0A-b.
 */
@Configuration(proxyBeanMethods = false)
@Profile(V2SandboxGate.PROFILE)
@ConditionalOnProperty(name = [V2SandboxGate.PROPERTY], havingValue = "true", matchIfMissing = false)
class V2SandboxConfiguration {
    @Bean
    fun v2SandboxMarker(): V2SandboxMarker = V2SandboxMarker()

    /**
     * OPENSAM-35 0A-d — read-only `content/v2/` catalog loader.
     *
     * It exists only inside the gate. It reads nothing at boot (it is not an `ApplicationRunner`) and writes
     * nothing to the database. `V2ContentCatalogTest` proves the former by constant-pool scan, while
     * `V2ContentCatalogBeanTest` measures the gate's 0/1 state. Contract:
     * `infra/src/main/resources/content/v2/README.md`.
     */
    @Bean
    fun v2ContentCatalog(): V2ContentCatalog = V2ContentCatalog()

    @Bean
    fun v2CityCatalogAdapter(catalog: V2ContentCatalog): V2CityCatalogAdapter = V2CityCatalogAdapter(catalog)
}

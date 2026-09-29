package opensamguk.engine.sandbox

import opensamguk.engine.city.ProcessCityIncomeAction

import opensamguk.engine.city.CityLedgerStore

import opensamguk.infra.content.ContentCatalog
import opensamguk.infra.content.CityCatalogAdapter
import opensamguk.infra.sandbox.SandboxGate
import opensamguk.infra.sandbox.SandboxMarker
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * OPENSAM-35 0A-b — sandbox bean-registration gate for game-engine.
 *
 * `@Profile` and `@ConditionalOnProperty` are combined with AND. If either condition is false, Spring skips this
 * `@Configuration` and registers none of its beans. `matchIfMissing = false` is the default, but remains explicit
 * because the gate direction is load-bearing: an unset `SANDBOX_ENABLED` is disabled.
 *
 * This class belongs in package `opensamguk.engine.sandbox`, inside `GameEngineApplication`'s
 * `@SpringBootApplication` component-scan root (`opensamguk.engine`). It needs no existing-file modification to be
 * registered.
 *
 * Since OPENSAM-189 this package IS inside `HotColdCatalog.runtimeSourceDirectories` and
 * `DaemonWriteGuard.writePathPackages`: sandbox code reaches the daemon write path through `ChangeRecorder`, so it
 * carries the same JDBC-only and cataloged-read obligations as v1. Its own reads/writes are none, so the guards
 * are vacuously satisfied here.
 *
 * Future concrete sandbox beans, including ledger stores and command handlers, belong here as `@Bean` methods, and
 * each bean name must be added to `APPROVED_V2_BEAN_NAMES` in
 * `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ProductionContextBeanGateIT.kt` (OPENSAM-184). A sandbox
 * bean outside the gate (for example, an `@Component`) violates 0A-b.
 */
@Configuration(proxyBeanMethods = false)
@Profile(SandboxGate.PROFILE)
@ConditionalOnProperty(name = [SandboxGate.PROPERTY], havingValue = "true", matchIfMissing = false)
class SandboxConfiguration {
    @Bean
    fun sandboxMarker(): SandboxMarker = SandboxMarker()

    /**
     * OPENSAM-35 0A-d — read-only `content/catalog/` catalog loader.
     *
     * It exists only inside the gate. It reads nothing at boot (it is not an `ApplicationRunner`) and writes
     * nothing to the database. `ContentCatalogTest` proves the former by constant-pool scan, while
     * `ContentCatalogBeanTest` measures the gate's 0/1 state. Contract:
     * `infra/src/main/resources/content/catalog/README.md`.
     */
    @Bean
    fun contentCatalog(): ContentCatalog = ContentCatalog()

    @Bean
    fun cityCatalogAdapter(catalog: ContentCatalog): CityCatalogAdapter = CityCatalogAdapter(catalog)

    /**
     * OPENSAM-151 — 도시 금·쌀·수비병 원장 스토어(OPENSAM-150이 만든 것). [ProcessCityIncomeAction]이
     * 유일한 소비처이고, 데몬은 `ObjectProvider`로 **있으면 쓰고 없으면 null**로 받는다. 그래서 게이트가
     * 꺼진 v1 프로덕션에서는 이 빈이 아예 없고, sandbox leaf가 (시나리오 실수로) 돌면 조용한 no-op이 아니라
     * `ProcessCityIncomeAction`에서 죽는다.
     */
    @Bean
    fun cityLedgerStore(jdbc: NamedParameterJdbcTemplate): CityLedgerStore = CityLedgerStore(jdbc)
}

package opensamguk.engine.v2

import opensamguk.infra.v2.V2ContentCatalog
import opensamguk.infra.v2.V2SandboxGate
import opensamguk.infra.v2.V2SandboxMarker
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * OPENSAM-35 0A-b — game-engine 쪽 v2 빈 등록 게이트.
 *
 * `@Profile` + `@ConditionalOnProperty`가 **AND**로 걸린다. 둘 중 하나만 참이면 이 `@Configuration`
 * 자체가 스킵되고 안의 빈은 하나도 등록되지 않는다. `matchIfMissing = false`(기본값이지만 게이트의
 * 방향이 load-bearing이라 명시)이므로 `V2_ENABLED` **미설정 = 비활성**이다.
 *
 * 이 클래스는 `opensamguk.engine.v2` 패키지에 있어야 한다 — `GameEngineApplication`의
 * `@SpringBootApplication` 컴포넌트 스캔 루트(`opensamguk.engine`) 안이라 등록을 위해 기존 파일을 열
 * 필요가 없고, `HotColdCatalog.runtimeSourceDirectories`/`DaemonWriteGuard.writePathPackages` 어디에도
 * 속하지 않아 v1 가드 테스트를 건드리지 않는다.
 *
 * 앞으로 v2 원장 store·v2 커맨드 핸들러 등 **실제 v2 빈은 전부 이 클래스 안의 `@Bean`으로** 들어온다.
 * 게이트 밖(`@Component` 등)에 v2 빈을 만들면 0A-b 위반이다.
 */
@Configuration(proxyBeanMethods = false)
@Profile(V2SandboxGate.PROFILE)
@ConditionalOnProperty(name = [V2SandboxGate.PROPERTY], havingValue = "true", matchIfMissing = false)
class V2SandboxConfiguration {
    @Bean
    fun v2SandboxMarker(): V2SandboxMarker = V2SandboxMarker()

    /**
     * OPENSAM-35 0A-d — `content/v2/` read-only 카탈로그 로더.
     *
     * 게이트 안에서만 산다. 부팅 시 아무것도 읽지 않고(=`ApplicationRunner` 아님) DB에 아무것도 쓰지
     * 않는다 — 근거는 `V2ContentCatalogTest`의 상수풀 스캔이고, 게이트 0/1은
     * `V2ContentCatalogBeanTest`가 실측한다. 규약: `infra/src/main/resources/content/v2/README.md`.
     */
    @Bean
    fun v2ContentCatalog(): V2ContentCatalog = V2ContentCatalog()
}

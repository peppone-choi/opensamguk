package opensamguk.gameapi.v2

import opensamguk.infra.v2.V2SandboxGate
import opensamguk.infra.v2.V2SandboxMarker
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * OPENSAM-35 0A-b — game-api 쪽 v2 빈 등록 게이트.
 *
 * 조건·의미는 `opensamguk.engine.v2.V2SandboxConfiguration`과 동일하다(`@Profile` AND
 * `@ConditionalOnProperty`, 미설정 = 비활성). game-api에도 v2 read/intake 빈이 들어올 예정이라
 * (round3 proposal §7.1-2) 게이트를 양쪽에 함께 설치한다 — 한쪽만 설치하면 다른 앱의 v2 빈이
 * production 컨텍스트에 무조건 등록된다.
 *
 * `opensamguk.gameapi.v2` 패키지 = `GameApiApplication`의 컴포넌트 스캔 루트(`opensamguk.gameapi`) 안.
 * v2는 JPA를 쓰지 않으므로 `@EntityScan`/`@EnableJpaRepositories` basePackages와 무관하다.
 *
 * 앞으로 v2 read/intake 컨트롤러 등 **실제 v2 빈은 전부 이 클래스 안의 `@Bean`으로** 들어온다.
 */
@Configuration(proxyBeanMethods = false)
@Profile(V2SandboxGate.PROFILE)
@ConditionalOnProperty(name = [V2SandboxGate.PROPERTY], havingValue = "true", matchIfMissing = false)
class V2SandboxConfiguration {
    @Bean
    fun v2SandboxMarker(): V2SandboxMarker = V2SandboxMarker()
}

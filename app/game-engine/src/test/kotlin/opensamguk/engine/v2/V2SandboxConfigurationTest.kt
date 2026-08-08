package opensamguk.engine.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import opensamguk.infra.v2.V2SandboxGate
import opensamguk.infra.v2.V2SandboxMarker
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * OPENSAM-35 0A-b — 게이트 4조합 실측.
 *
 * DB가 필요 없는 [ApplicationContextRunner]로 조건 평가만 잰다. Testcontainers 풀 컨텍스트에서
 * v1 프로세스의 v2 빈 0개를 실측하는 것은 S4(0A-f)의 몫이라 여기서 중복하지 않는다.
 */
class V2SandboxConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withUserConfiguration(V2SandboxConfiguration::class.java)

    private fun ApplicationContextRunner.withProfile() =
        withPropertyValues("spring.profiles.active=${V2SandboxGate.PROFILE}")

    private fun ApplicationContextRunner.withEnabled(value: String) =
        withPropertyValues("${V2SandboxGate.PROPERTY}=$value")

    @Test
    fun `neither condition - no v2 bean`() {
        runner.run { context -> assertEquals(0, context.getBeansOfType(V2SandboxMarker::class.java).size) }
    }

    @Test
    fun `property only - no v2 bean`() {
        runner.withEnabled("true")
            .run { context -> assertEquals(0, context.getBeansOfType(V2SandboxMarker::class.java).size) }
    }

    @Test
    fun `profile only - no v2 bean`() {
        runner.withProfile()
            .run { context -> assertEquals(0, context.getBeansOfType(V2SandboxMarker::class.java).size) }
    }

    @Test
    fun `both conditions - v2 bean registered`() {
        runner.withProfile().withEnabled("true")
            .run { context -> assertEquals(1, context.getBeansOfType(V2SandboxMarker::class.java).size) }
    }

    @Test
    fun `property set to false with profile active - no v2 bean`() {
        runner.withProfile().withEnabled("false")
            .run { context -> assertEquals(0, context.getBeansOfType(V2SandboxMarker::class.java).size) }
    }

    /**
     * `@ConditionalOnProperty`의 값 비교가 대소문자 무시(`equalsIgnoreCase`)임을 실측으로 고정한다.
     * 프론트 게이트(`web/game/middleware.ts`)는 `=== 'true'` strict라 이 비대칭은 의도된 것이다 —
     * 근거는 `web/game/app/game/v2-lab/layout.tsx` 주석.
     */
    @Test
    fun `property value is matched case-insensitively`() {
        for (value in listOf("TRUE", "True", "tRuE")) {
            runner.withProfile().withEnabled(value)
                .run { context ->
                    assertEquals(1, context.getBeansOfType(V2SandboxMarker::class.java).size, "v2.enabled=$value")
                }
        }
    }

    /**
     * env `V2_ENABLED` ↔ 프로퍼티 키 `v2.enabled` 대응을 기억이 아니라 실측으로 고정한다.
     * Spring의 [SystemEnvironmentPropertySource]가 relaxed binding을 수행하므로 실제 OS 환경변수
     * 없이도 같은 매핑을 잰다.
     */
    @Test
    fun `V2_ENABLED env var maps onto the gate property`() {
        runner.withProfile()
            .withInitializer { context ->
                context.environment.propertySources.addFirst(
                    SystemEnvironmentPropertySource("test-systemEnvironment", mapOf("V2_ENABLED" to "true")),
                )
            }
            .run { context -> assertEquals(1, context.getBeansOfType(V2SandboxMarker::class.java).size) }
    }
}

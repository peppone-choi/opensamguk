package opensamguk.gameapi.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import opensamguk.infra.v2.V2SandboxGate
import opensamguk.infra.v2.V2SandboxMarker
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * OPENSAM-35 0A-b — game-api 게이트 4조합 실측.
 * env `V2_ENABLED` ↔ `v2.enabled` relaxed binding은 game-engine 쪽 동일 테스트에서 한 번 실측한다.
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
}

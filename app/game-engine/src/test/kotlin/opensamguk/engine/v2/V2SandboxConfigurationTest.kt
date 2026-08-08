package opensamguk.engine.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import opensamguk.infra.v2.V2SandboxGate
import opensamguk.infra.v2.V2SandboxMarker
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * OPENSAM-35 0A-b — executable coverage of the four gate combinations.
 *
 * It uses [ApplicationContextRunner] to measure condition evaluation without a database. S4 (0A-f) measures
 * zero v2 beans in a full Testcontainers v1-process context, so that work is not duplicated here.
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
     * Proves that `@ConditionalOnProperty` compares values case-insensitively (`equalsIgnoreCase`). The frontend
     * gate (`web/game/middleware.ts`) uses strict `=== 'true'`; the asymmetry is intentional and documented in
     * `web/game/app/game/v2-lab/layout.tsx`.
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
     * Proves the mapping from env `V2_ENABLED` to property key `v2.enabled` rather than relying on memory.
     * [SystemEnvironmentPropertySource] performs Spring relaxed binding, so the same mapping is measured without
     * a real operating-system environment variable.
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

package opensamguk.engine.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.engine.config.EngineProcessWorld
import opensamguk.engine.config.WorldIdConfig
import opensamguk.infra.v2.V2CityCatalogAdapter
import opensamguk.infra.v2.V2ContentCatalog
import opensamguk.infra.v2.V2SandboxGate
import opensamguk.infra.v2.V2SandboxMarker
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.context.ApplicationContext

/**
 * OPENSAM-35 0A-b — executable coverage of the four gate combinations.
 *
 * It uses [ApplicationContextRunner] to measure condition evaluation without a database. S4 (0A-f) measures
 * zero v2 beans in a full Testcontainers v1-process context, so that work is not duplicated here.
 */
class V2SandboxConfigurationTest {
    private fun runner() = ApplicationContextRunner()
        .withUserConfiguration(WorldIdConfig::class.java, V2SandboxConfiguration::class.java)
        // OPENSAM-151 — v2CityLedgerStore 가 NamedParameterJdbcTemplate 을 요구한다. 이 테스트의 측정
        // 대상은 조건 평가이지 DB 가용성이 아니므로, DataSource 없는 껍데기만 넣어 컨텍스트를 띄운다
        // (쿼리를 던지는 순간 죽지만 이 테스트는 던지지 않는다).
        .withBean(NamedParameterJdbcTemplate::class.java, { NamedParameterJdbcTemplate(JdbcTemplate()) })

    private fun ApplicationContextRunner.withProfile() =
        withPropertyValues("spring.profiles.active=${V2SandboxGate.PROFILE}")

    private fun ApplicationContextRunner.withEnabled(value: String) =
        withPropertyValues("${V2SandboxGate.PROPERTY}=$value")

    private fun ApplicationContextRunner.withWorldId(value: Int) =
        withPropertyValues("OPENSAMGUK_WORLD_ID=$value")

    @Test
    fun `neither condition - no v2 bean`() {
        runner().withWorldId(1).run { context -> context.assertNoEngineRuntimeV2Beans() }
    }

    @Test
    fun `property only - no v2 bean`() {
        runner().withWorldId(1).withEnabled("true")
            .run { context -> context.assertNoEngineRuntimeV2Beans() }
    }

    @Test
    fun `profile only - no v2 bean`() {
        runner().withWorldId(1).withProfile()
            .run { context -> context.assertNoEngineRuntimeV2Beans() }
    }

    @Test
    fun `both conditions register the existing sandbox process world and exact engine v2 beans`() {
        runner().withWorldId(9001).withProfile().withEnabled("true")
            .run { context ->
                assertTrue(V2SandboxGate.PROFILE in context.environment.activeProfiles)

                val processWorlds = context.getBeansOfType(EngineProcessWorld::class.java)
                assertEquals(1, processWorlds.size, "EngineProcessWorld beans")
                assertEquals(WorldId(9001), processWorlds.values.single().worldId)

                assertEquals(1, context.getBeansOfType(V2SandboxMarker::class.java).size)
                assertEquals(1, context.getBeansOfType(V2ContentCatalog::class.java).size)
                assertEquals(1, context.getBeansOfType(V2CityCatalogAdapter::class.java).size)
            }
    }

    @Test
    fun `property set to false with profile active - no v2 bean`() {
        runner().withWorldId(1).withProfile().withEnabled("false")
            .run { context -> context.assertNoEngineRuntimeV2Beans() }
    }

    /**
     * Proves that `@ConditionalOnProperty` compares values case-insensitively (`equalsIgnoreCase`). The frontend
     * gate (`web/game/middleware.ts`) uses strict `=== 'true'`; the asymmetry is intentional and documented in
     * `web/game/app/game/v2-lab/layout.tsx`.
     */
    @Test
    fun `property value is matched case-insensitively`() {
        for (value in listOf("TRUE", "True", "tRuE")) {
            runner().withWorldId(1).withProfile().withEnabled(value)
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
        runner().withWorldId(1).withProfile()
            .withInitializer { context ->
                context.environment.propertySources.addFirst(
                    SystemEnvironmentPropertySource("test-systemEnvironment", mapOf("V2_ENABLED" to "true")),
                )
            }
            .run { context -> assertEquals(1, context.getBeansOfType(V2SandboxMarker::class.java).size) }
    }

    private fun ApplicationContext.assertNoEngineRuntimeV2Beans() {
        assertEquals(0, getBeansOfType(V2SandboxMarker::class.java).size, "V2SandboxMarker beans")
        assertEquals(0, getBeansOfType(V2ContentCatalog::class.java).size, "V2ContentCatalog beans")
        assertEquals(0, getBeansOfType(V2CityCatalogAdapter::class.java).size, "V2CityCatalogAdapter beans")
    }
}

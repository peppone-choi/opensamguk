package opensamguk.gameapi.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.config.GameApiProcessWorldIdConfiguration
import opensamguk.infra.v2.V2CityCatalogAdapter
import opensamguk.infra.v2.V2ContentCatalog
import opensamguk.infra.v2.V2SandboxGate
import opensamguk.infra.v2.V2SandboxMarker
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext

/**
 * OPENSAM-35 0A-b — executable coverage of game-api's four gate combinations.
 * The matching game-engine test measures relaxed binding from env `V2_ENABLED` to `v2.enabled` once.
 */
class V2SandboxConfigurationTest {
    private fun runner() = ApplicationContextRunner()
        .withUserConfiguration(GameApiProcessWorldIdConfiguration::class.java, V2SandboxConfiguration::class.java)

    private fun ApplicationContextRunner.withProfile() =
        withPropertyValues("spring.profiles.active=${V2SandboxGate.PROFILE}")

    private fun ApplicationContextRunner.withEnabled(value: String) =
        withPropertyValues("${V2SandboxGate.PROPERTY}=$value")

    private fun ApplicationContextRunner.withWorldId(value: Int) =
        withPropertyValues("opensamguk.world-id=$value")

    @Test
    fun `neither condition - no v2 bean`() {
        runner().withWorldId(1).run { context -> context.assertNoGameApiRuntimeV2Beans() }
    }

    @Test
    fun `property only - no v2 bean`() {
        runner().withWorldId(1).withEnabled("true")
            .run { context -> context.assertNoGameApiRuntimeV2Beans() }
    }

    @Test
    fun `profile only - no v2 bean`() {
        runner().withWorldId(1).withProfile()
            .run { context -> context.assertNoGameApiRuntimeV2Beans() }
    }

    @Test
    fun `both conditions register the existing sandbox process world and only the game api marker`() {
        runner().withWorldId(9001).withProfile().withEnabled("true")
            .run { context ->
                assertTrue(V2SandboxGate.PROFILE in context.environment.activeProfiles)

                val processWorlds = context.getBeansOfType(GameApiProcessWorld::class.java)
                assertEquals(1, processWorlds.size, "GameApiProcessWorld beans")
                assertEquals(WorldId(9001), processWorlds.values.single().worldId)

                assertEquals(1, context.getBeansOfType(V2SandboxMarker::class.java).size)
                assertEquals(0, context.getBeansOfType(V2ContentCatalog::class.java).size)
                assertEquals(0, context.getBeansOfType(V2CityCatalogAdapter::class.java).size)
            }
    }

    @Test
    fun `property set to false with profile active - no v2 bean`() {
        runner().withWorldId(1).withProfile().withEnabled("false")
            .run { context -> context.assertNoGameApiRuntimeV2Beans() }
    }

    private fun ApplicationContext.assertNoGameApiRuntimeV2Beans() {
        assertEquals(0, getBeansOfType(V2SandboxMarker::class.java).size, "V2SandboxMarker beans")
        assertEquals(0, getBeansOfType(V2ContentCatalog::class.java).size, "V2ContentCatalog beans")
        assertEquals(0, getBeansOfType(V2CityCatalogAdapter::class.java).size, "V2CityCatalogAdapter beans")
    }
}

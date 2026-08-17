package opensamguk.gameapi.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.v2.V2CityCatalogAdapter
import opensamguk.infra.v2.V2ContentCatalog
import opensamguk.infra.v2.V2SandboxGate
import opensamguk.infra.v2.V2SandboxMarker
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * OPENSAM-35 0A-f (S4) — measures v2 bean counts in game-api's **actual booted context**.
 *
 * S2 installed the gate in both game-engine and game-api, so both applications measure it. The structure matches
 * `opensamguk.engine.v2.V2ProductionContextBeanGateIT`; the only difference is one observed type:
 * `V2ContentCatalog` is registered only in game-engine (S3-a), so it must be **zero in every case**, including
 * when the gate is open.
 */
internal fun ApplicationContext.v2PackageBeans(): Map<String, String> =
    beanDefinitionNames.mapNotNull { name ->
        val type = runCatching { getType(name, false) }.getOrNull()?.name ?: return@mapNotNull null
        if (type.startsWith("opensamguk.") && type.contains(".v2.")) name to type else null
    }.toMap()

internal fun ApplicationContext.assertNoV2Beans() {
    assertEquals(0, getBeansOfType(V2SandboxMarker::class.java).size, "V2SandboxMarker beans")
    assertEquals(0, getBeansOfType(V2ContentCatalog::class.java).size, "V2ContentCatalog beans")
    assertEquals(0, getBeansOfType(V2CityCatalogAdapter::class.java).size, "V2CityCatalogAdapter beans")
    assertEquals(emptyMap(), v2PackageBeans(), "beans whose type lives in an opensamguk *.v2.* package")
}

private fun postgresProps(
    registry: DynamicPropertyRegistry,
    container: PostgreSQLContainer<*>,
    worldId: Int = 1,
) {
    registry.add("spring.datasource.url", container::getJdbcUrl)
    registry.add("spring.datasource.username", container::getUsername)
    registry.add("spring.datasource.password", container::getPassword)
    registry.add("opensamguk.world-id") { worldId.toString() }
    registry.add("management.health.redis.enabled") { "false" }
}

internal class V2EnabledEnvironmentInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        context.environment.propertySources.addFirst(
            SystemEnvironmentPropertySource("test-systemEnvironment", mapOf("V2_ENABLED" to "true")),
        )
    }
}

/** ① Production shape — `V2_ENABLED` unset and profile inactive. Expect zero v2 beans. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class V2ProductionShapeBeanGateIT {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `production context registers no v2 bean`() = context.assertNoV2Beans()

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) = postgresProps(registry, postgres)
    }
}

/** ② `v2.enabled=true` only — no profile. Expect zero beans. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = ["${V2SandboxGate.PROPERTY}=true"])
class V2PropertyOnlyBeanGateIT {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `property alone registers no v2 bean`() = context.assertNoV2Beans()

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) = postgresProps(registry, postgres)
    }
}

/** ③ Profile `v2-sandbox` only — no property. Expect zero beans. */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles(V2SandboxGate.PROFILE)
@SpringBootTest
class V2ProfileOnlyBeanGateIT {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `profile alone registers no v2 bean`() = context.assertNoV2Beans()

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) = postgresProps(registry, postgres)
    }
}

/**
 * ④ **Positive control** — both conditions are true. Expect registration.
 *
 * Without this case, ①–③ could pass even if the context never starts.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles(V2SandboxGate.PROFILE)
@ContextConfiguration(initializers = [V2EnabledEnvironmentInitializer::class])
@SpringBootTest
class V2BothConditionsBeanGateIT {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `both conditions register the v2 beans`() {
        assertTrue(V2SandboxGate.PROFILE in context.environment.activeProfiles)
        assertEquals("true", context.environment.getProperty(V2SandboxGate.PROPERTY))

        val processWorlds = context.getBeansOfType(GameApiProcessWorld::class.java)
        assertEquals(1, processWorlds.size, "GameApiProcessWorld beans")
        assertEquals(WorldId(9001), processWorlds.values.single().worldId)

        assertEquals(1, context.getBeansOfType(V2SandboxMarker::class.java).size, "V2SandboxMarker beans")
        // game-api has no v2 content consumer (S3-a), so opening the gate does not register the loader.
        assertEquals(0, context.getBeansOfType(V2ContentCatalog::class.java).size, "V2ContentCatalog beans")
        assertEquals(0, context.getBeansOfType(V2CityCatalogAdapter::class.java).size, "V2CityCatalogAdapter beans")
        val byPackage = context.v2PackageBeans()
        assertEquals(
            // OPENSAM-153 (v2 R4) — V2GarrisonRecruitController shares this gate's @Profile/@ConditionalOnProperty,
            // so it registers alongside the marker when both conditions are true.
            // OPENSAM-154 (v2 R5) — V2CityTransportController shares the same gate.
            // OPENSAM-155 (v2 R6) — V2CityLedgerReadController is read-only but sits behind the SAME gate,
            // so a closed gate hides the ledger endpoint too (404), not just the intake ones.
            setOf(
                "v2SandboxConfiguration",
                "v2SandboxMarker",
                "v2GarrisonRecruitController",
                "v2CityTransportController",
                "v2CityLedgerReadController",
            ),
            byPackage.keys,
            "game-api v2 package beans: $byPackage",
        )
    }

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) = postgresProps(registry, postgres, worldId = 9001)
    }
}

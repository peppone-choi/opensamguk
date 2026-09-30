package opensamguk.gameapi.sandbox

import opensamguk.gameapi.sandbox.SandboxConfiguration

import opensamguk.gameapi.city.CityLedgerReadController
import opensamguk.gameapi.city.CityTransportController
import opensamguk.gameapi.city.GarrisonRecruitController

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.content.CityCatalogAdapter
import opensamguk.infra.content.ContentCatalog
import opensamguk.infra.sandbox.SandboxGate
import opensamguk.infra.sandbox.SandboxMarker
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
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
 * `opensamguk.engine.sandbox.ProductionContextBeanGateIT`; the only difference is one observed type:
 * `ContentCatalog` is registered only in game-engine (S3-a), so it must be **zero in every case**, including
 * when the gate is open.
 */
internal fun ApplicationContext.v2PackageBeans(): Map<String, String> =
    beanDefinitionNames.mapNotNull { name ->
        val type = runCatching { getType(name, false) }.getOrNull()?.name ?: return@mapNotNull null
        if (SandboxGate.isGatedTypeName(type)) name to type else null
    }.toMap()

internal fun ApplicationContext.assertNoV2Beans() {
    assertEquals(0, getBeansOfType(SandboxMarker::class.java).size, "SandboxMarker beans")
    assertEquals(0, getBeansOfType(ContentCatalog::class.java).size, "ContentCatalog beans")
    assertEquals(0, getBeansOfType(CityCatalogAdapter::class.java).size, "CityCatalogAdapter beans")
    assertEquals(emptyMap(), v2PackageBeans(), "sandbox feature beans")
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

internal class EnabledEnvironmentInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        context.environment.propertySources.addFirst(
            SystemEnvironmentPropertySource("test-systemEnvironment", mapOf("SANDBOX_ENABLED" to "true")),
        )
    }
}

/** ① Production shape — `SANDBOX_ENABLED` unset and profile inactive. Expect zero v2 beans. */
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class ProductionShapeBeanGateIT {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `production context registers no v2 bean`() = context.assertNoV2Beans()

    @Test
    fun `retired endpoints are absent while campaign reads and queue shift remain registered`() {
        val mapping = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping::class.java)
        val paths = mapping.handlerMethods.keys.flatMap { it.patternValues }.toSet()
        val retired = setOf(
            "/api/global-menu", "/api/inherit-point", "/api/instant-action/{code}",
            "/api/nation/{id}/finance", "/api/nation/npc-policy",
            "/api/generals/claimable", "/api/general/claim", "/api/my-retinue",
            "/api/generals/{id}/retinue", "/api/select-pool", "/api/select-pool/refresh",
            "/api/simulate-battle", "/api/votes", "/api/votes/{id}", "/api/battlefields",
            "/api/rankings/generals", "/api/rankings/npcs", "/api/rankings/hall-of-fame",
            "/api/rankings/traffic", "/api/rankings/emperor", "/api/rankings/emperor/{id}",
            "/api/my-boss",
        )
        assertEquals(emptySet(), paths.intersect(retired), "은퇴한 API 등록")
        val active = setOf("/api/retinue", "/api/command/push", "/api/battles/replays/{id}", "/api/city/{id}", "/api/generals")
        assertTrue(paths.containsAll(active), "유지해야 할 API 누락: ${active - paths}")
    }

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) = postgresProps(registry, postgres)
    }
}

/** ② `sandbox.enabled=true` only — no profile. Expect zero beans. */
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = ["${SandboxGate.PROPERTY}=true"])
class PropertyOnlyBeanGateIT {
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

/** ③ Profile `sandbox` only — no property. Expect zero beans. */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test", SandboxGate.PROFILE)
@SpringBootTest
class ProfileOnlyBeanGateIT {
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
@ActiveProfiles("test", SandboxGate.PROFILE)
@ContextConfiguration(initializers = [EnabledEnvironmentInitializer::class])
@SpringBootTest
class BothConditionsBeanGateIT {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `both conditions register the v2 beans`() {
        assertTrue(SandboxGate.PROFILE in context.environment.activeProfiles)
        assertEquals("true", context.environment.getProperty(SandboxGate.PROPERTY))

        val processWorlds = context.getBeansOfType(GameApiProcessWorld::class.java)
        assertEquals(1, processWorlds.size, "GameApiProcessWorld beans")
        assertEquals(WorldId(9001), processWorlds.values.single().worldId)

        assertEquals(1, context.getBeansOfType(SandboxMarker::class.java).size, "SandboxMarker beans")
        // game-api has no v2 content consumer (S3-a), so opening the gate does not register the loader.
        assertEquals(0, context.getBeansOfType(ContentCatalog::class.java).size, "ContentCatalog beans")
        assertEquals(0, context.getBeansOfType(CityCatalogAdapter::class.java).size, "CityCatalogAdapter beans")
        val byPackage = context.v2PackageBeans()
        assertEquals(
            // OPENSAM-153 (v2 R4) — GarrisonRecruitController shares this gate's @Profile/@ConditionalOnProperty,
            // so it registers alongside the marker when both conditions are true.
            // OPENSAM-154 (v2 R5) — CityTransportController shares the same gate.
            // OPENSAM-155 (v2 R6) — CityLedgerReadController is read-only but sits behind the SAME gate,
            // so a closed gate hides the ledger endpoint too (404), not just the intake ones.
            setOf(
                "sandboxConfiguration",
                "sandboxMarker",
                "garrisonRecruitController",
                "cityTransportController",
                "cityLedgerReadController",
                "canonicalCommandController",
                "cityCommandPrecheckService",
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

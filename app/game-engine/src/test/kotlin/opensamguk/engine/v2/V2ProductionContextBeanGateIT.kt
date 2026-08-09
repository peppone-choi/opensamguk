package opensamguk.engine.v2

import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.engine.config.EngineProcessWorld
import opensamguk.infra.v2.V2CityCatalogAdapter
import opensamguk.infra.v2.V2ContentCatalog
import opensamguk.infra.v2.V2SandboxGate
import opensamguk.infra.v2.V2SandboxMarker
import org.flywaydb.core.Flyway
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
 * OPENSAM-35 0A-f (S4) — measures v2 bean counts in game-engine's **actual booted context**.
 *
 * The S2 and S3-a `ApplicationContextRunner` tests measure only condition evaluation. ADR-LITE-021 (iii)
 * requires measurement rather than declaration, so this uses `@SpringBootTest` and Testcontainers to boot a
 * context shaped like the v1 process and count [ApplicationContext.getBeansOfType]. Existing repository
 * architecture tests such as `DaemonNoEntityManagerTest` are static scans and cannot replace this layer.
 *
 * The four classes are the four cells of the decision matrix. The final [V2BothConditionsBeanGateIT] is the
 * **positive control**, proving that an all-zero result comes from a running context.
 */
private const val SECURITY_EXCLUDES =
    // As in GameEngineApplicationTests, game-api(:mainClassesForTest)'s transitive security starter reaches the
    // engine test classpath and enables security auto-configuration absent from runtime. This is a test artifact.
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
        "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration," +
        "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration," +
        "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"

/**
 * A defense that does not depend on hard-coded type names. It scans **all bean definitions** for types from a v2
 * package, so a new v2 bean cannot silently bypass the gate merely because nobody added it to this test's type
 * list. `allowFactoryBeanInit = false` resolves types without creating beans.
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
    registry.add("management.health.redis.enabled") { "false" }
    registry.add("OPENSAMGUK_WORLD_ID") { worldId.toString() }
    registry.add("SCENARIO_SEED_ENABLED") { "false" }
    // Starting the turn loop would consume the Redis stream; construct the bean but prevent it from starting.
    registry.add("opensamguk.daemon.enabled") { "false" }
}

internal class V2EnabledEnvironmentInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        context.environment.propertySources.addFirst(
            SystemEnvironmentPropertySource(
                "test-systemEnvironment",
                mapOf(
                    "V2_ENABLED" to "true",
                    "SPRING_FLYWAY_LOCATIONS" to V2_SANDBOX_FLYWAY_LOCATIONS,
                ),
            ),
        )
    }
}

/** ① Production shape — `V2_ENABLED` unset and profile inactive. Expect zero v2 beans. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = [SECURITY_EXCLUDES])
class V2ProductionShapeBeanGateIT {
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var flyway: Flyway
    @Autowired lateinit var dataSource: DataSource

    @Test
    fun `production context registers no v2 bean`() = context.assertNoV2Beans()

    @Test
    fun `production context resolves application default Flyway location and excludes V900`() {
        assertEquals(V1_FLYWAY_LOCATION, context.environment.getProperty("spring.flyway.locations"))
        V2FlywayIsolationAssertions(flyway, dataSource).assertV1DefaultRuntime()
    }

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) = postgresProps(registry, postgres)
    }
}

/** ② `v2.enabled=true` only — no profile. Expect zero beans. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = [SECURITY_EXCLUDES, "${V2SandboxGate.PROPERTY}=true"])
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
@SpringBootTest(properties = [SECURITY_EXCLUDES])
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
 * Without this case, ①–③ could pass even if the context never starts. Finding the actual bean here proves that
 * the three preceding zeros were measured in a live context.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles(V2SandboxGate.PROFILE)
@ContextConfiguration(initializers = [V2EnabledEnvironmentInitializer::class])
@SpringBootTest(properties = [SECURITY_EXCLUDES])
class V2BothConditionsBeanGateIT {
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var flyway: Flyway
    @Autowired lateinit var dataSource: DataSource

    @Test
    fun `both conditions register the v2 beans`() {
        assertTrue(V2SandboxGate.PROFILE in context.environment.activeProfiles)
        assertEquals("true", context.environment.getProperty(V2SandboxGate.PROPERTY))

        val processWorlds = context.getBeansOfType(EngineProcessWorld::class.java)
        assertEquals(1, processWorlds.size, "EngineProcessWorld beans")
        assertEquals(WorldId(9001), processWorlds.values.single().worldId)

        assertEquals(1, context.getBeansOfType(V2SandboxMarker::class.java).size, "V2SandboxMarker beans")
        assertEquals(1, context.getBeansOfType(V2ContentCatalog::class.java).size, "V2ContentCatalog beans")
        assertEquals(1, context.getBeansOfType(V2CityCatalogAdapter::class.java).size, "V2CityCatalogAdapter beans")
        val byPackage = context.v2PackageBeans()
        assertEquals(
            setOf("v2SandboxConfiguration", "v2SandboxMarker", "v2ContentCatalog", "v2CityCatalogAdapter"),
            byPackage.keys,
            "engine v2 package beans: $byPackage",
        )
    }

    @Test
    fun `v2 sandbox resolves the literal environment Flyway override and applies V900`() {
        assertTrue(V2SandboxGate.PROFILE in context.environment.activeProfiles)
        assertEquals("true", context.environment.getProperty(V2SandboxGate.PROPERTY))
        assertEquals(V2_SANDBOX_FLYWAY_LOCATIONS, context.environment.getProperty("spring.flyway.locations"))
        V2FlywayIsolationAssertions(flyway, dataSource).assertV2SandboxRuntime()
    }

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) = postgresProps(registry, postgres, worldId = 9001)
    }
}

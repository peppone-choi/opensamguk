package opensamguk.gateway.sandbox

import kotlin.test.Test
import kotlin.test.assertEquals
import opensamguk.gateway.controller.AuthController
import opensamguk.gateway.profile.ProfileIconSecureStorageTestConfiguration
import opensamguk.infra.content.CityCatalogAdapter
import opensamguk.infra.content.ContentCatalog
import opensamguk.infra.sandbox.SandboxGate
import opensamguk.infra.sandbox.SandboxMarker
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * OPENSAM-35 0A-f (S4) — measures v2 bean counts in gateway-api's **actual booted context**.
 *
 * It is the same gate as the identically named game-engine and game-api ITs, but one expectation differs:
 * gateway-api has **no** `SandboxConfiguration`. It is an authentication/profile service with no consumer for
 * a v2 marker bean, so it does not duplicate an unused third conditional configuration. Therefore it must remain
 * **zero even when the gate is fully open (④)**. This test catches v2 code that leaks into gateway-api later.
 *
 * The context starts the same way as [opensamguk.gateway.GatewayApiApplicationTests]: the `test` profile overlays
 * H2 and disables Flyway through `src/test/resources/application-test.yml`; Docker and Testcontainers are unnecessary.
 */
internal fun ApplicationContext.sandboxPackageBeans(): Map<String, String> =
    beansByTypePrefix("opensamguk.").filterValues(SandboxGate::isGatedTypeName)

/**
 * Judges by bean **type**, not **name**. Component-scan bean-definition names are decapitalized simple names rather
 * than FQNs, so a name-prefix comparison would be dead logic in the real application.
 */
internal fun ApplicationContext.beansByTypePrefix(prefix: String): Map<String, String> =
    beanDefinitionNames.mapNotNull { name ->
        val type = runCatching { getType(name, false) }.getOrNull()?.name ?: return@mapNotNull null
        if (type.startsWith(prefix)) name to type else null
    }.toMap()

/**
 * Shared body for the four decision cells. Zero must be proven to come from a booted context, but gateway-api has
 * no v2 bean to use as a positive control. Instead, it also confirms that a specific production-scanned
 * application bean is available by **type** (observed 30 type-prefixed beans on 2026-08-08).
 */
abstract class BeanGateContract {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `gateway-api registers no v2 bean`() {
        assertEquals(
            1,
            context.getBeansOfType(AuthController::class.java).size,
            "context did not actually boot the production AuthController",
        )
        assertEquals(0, context.getBeansOfType(SandboxMarker::class.java).size, "SandboxMarker beans")
        assertEquals(0, context.getBeansOfType(ContentCatalog::class.java).size, "ContentCatalog beans")
        assertEquals(0, context.getBeansOfType(CityCatalogAdapter::class.java).size, "CityCatalogAdapter beans")
        assertEquals(emptyMap(), context.sandboxPackageBeans(), "sandbox feature beans")
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("management.health.redis.enabled") { "false" }
        }
    }
}

/** ① Production shape — `SANDBOX_ENABLED` unset and profile inactive. */
@ActiveProfiles("test")
@SpringBootTest
@Import(ProfileIconSecureStorageTestConfiguration::class)
class ProductionShapeBeanGateIT : BeanGateContract()

/** ② `sandbox.enabled=true` only. */
@ActiveProfiles("test")
@SpringBootTest(properties = ["${SandboxGate.PROPERTY}=true"])
@Import(ProfileIconSecureStorageTestConfiguration::class)
class PropertyOnlyBeanGateIT : BeanGateContract()

/** ③ Profile `sandbox` only. */
@ActiveProfiles("test", SandboxGate.PROFILE)
@SpringBootTest
@Import(ProfileIconSecureStorageTestConfiguration::class)
class ProfileOnlyBeanGateIT : BeanGateContract()

/** ④ Both conditions are true — still zero because gateway-api has no `SandboxConfiguration`. */
@ActiveProfiles("test", SandboxGate.PROFILE)
@SpringBootTest(properties = ["${SandboxGate.PROPERTY}=true"])
@Import(ProfileIconSecureStorageTestConfiguration::class)
class BothConditionsBeanGateIT : BeanGateContract()

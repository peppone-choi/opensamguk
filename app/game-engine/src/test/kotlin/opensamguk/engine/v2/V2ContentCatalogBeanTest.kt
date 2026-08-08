package opensamguk.engine.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import opensamguk.infra.v2.V2ContentCatalog
import opensamguk.infra.v2.V2SandboxGate
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * OPENSAM-35 0A-d — measures, with the same [ApplicationContextRunner] approach as S2, that the loader bean
 * exists **only inside the gate**. `infra`'s `V2ContentCatalogTest` judges the loader's scope and read-only nature.
 */
class V2ContentCatalogBeanTest {

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(V2SandboxConfiguration::class.java)

    @Test
    fun `gate closed - no content catalog bean`() {
        runner.run { assertEquals(0, it.getBeansOfType(V2ContentCatalog::class.java).size) }
        runner.withPropertyValues("${V2SandboxGate.PROPERTY}=true")
            .run { assertEquals(0, it.getBeansOfType(V2ContentCatalog::class.java).size) }
        runner.withPropertyValues("spring.profiles.active=${V2SandboxGate.PROFILE}")
            .run { assertEquals(0, it.getBeansOfType(V2ContentCatalog::class.java).size) }
    }

    @Test
    fun `gate open - content catalog bean registered and reads an empty catalog`() {
        gateOpen().run { context ->
            val catalog = context.getBean(V2ContentCatalog::class.java)
            assertNotNull(catalog)
            // There are no v2 content files yet (only a README), so an empty list is normal rather than an error.
            assertEquals(emptyList(), catalog.names())
        }
    }

    /**
     * Proves that even with the gate open, **[V2SandboxConfiguration] itself** registers no boot-invoked bean.
     *
     * Measurement scope: a bare [ApplicationContextRunner] registering only this configuration class. The real
     * application context has v1 runners such as `ScenarioSeedRunner`, so zero here does not mean an application
     * context with the gate open has no startup runner. It fixes only that this configuration adds no new boot hook.
     */
    @Test
    fun `gate open - registers no startup runner`() {
        gateOpen().run { context ->
            assertEquals(0, context.getBeansOfType(ApplicationRunner::class.java).size)
            assertEquals(0, context.getBeansOfType(CommandLineRunner::class.java).size)
        }
    }

    private fun gateOpen() = runner.withPropertyValues(
        "spring.profiles.active=${V2SandboxGate.PROFILE}",
        "${V2SandboxGate.PROPERTY}=true",
    )
}

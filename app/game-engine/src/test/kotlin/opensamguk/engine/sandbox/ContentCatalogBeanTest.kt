package opensamguk.engine.sandbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import opensamguk.infra.content.CityCatalogAdapter
import opensamguk.infra.content.ContentCatalog
import opensamguk.infra.sandbox.SandboxGate
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * OPENSAM-35 0A-d — measures, with the same [ApplicationContextRunner] approach as S2, that the loader bean
 * exists **only inside the gate**. `infra`'s `ContentCatalogTest` judges the loader's scope and read-only nature.
 */
class ContentCatalogBeanTest {

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(SandboxConfiguration::class.java)
        // OPENSAM-151 — 위와 같은 이유의 DataSource 없는 JDBC 껍데기.
        .withBean(NamedParameterJdbcTemplate::class.java, { NamedParameterJdbcTemplate(JdbcTemplate()) })

    @Test
    fun `gate closed - no content catalog or city adapter bean`() {
        runner.run {
            assertEquals(0, it.getBeansOfType(ContentCatalog::class.java).size)
            assertEquals(0, it.getBeansOfType(CityCatalogAdapter::class.java).size)
        }
        runner.withPropertyValues("${SandboxGate.PROPERTY}=true")
            .run {
                assertEquals(0, it.getBeansOfType(ContentCatalog::class.java).size)
                assertEquals(0, it.getBeansOfType(CityCatalogAdapter::class.java).size)
            }
        runner.withPropertyValues("spring.profiles.active=${SandboxGate.PROFILE}")
            .run {
                assertEquals(0, it.getBeansOfType(ContentCatalog::class.java).size)
                assertEquals(0, it.getBeansOfType(CityCatalogAdapter::class.java).size)
            }
    }

    @Test
    fun `gate open - content catalog and city adapter beans are registered`() {
        gateOpen().run { context ->
            val catalog = context.getBean(ContentCatalog::class.java)
            assertNotNull(catalog)
            assertEquals(listOf("cities_1010.json"), catalog.names())

            val adapter = context.getBean(CityCatalogAdapter::class.java)
            assertNotNull(adapter)
            assertNotNull(adapter.load().cities.firstOrNull())
        }
    }

    /**
     * Proves that even with the gate open, **[SandboxConfiguration] itself** registers no boot-invoked bean.
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
        "spring.profiles.active=${SandboxGate.PROFILE}",
        "${SandboxGate.PROPERTY}=true",
    )
}

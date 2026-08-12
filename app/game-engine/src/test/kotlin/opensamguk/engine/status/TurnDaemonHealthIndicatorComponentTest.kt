package opensamguk.engine.status

import opensamguk.engine.boot.WorldStateAvailability
import opensamguk.engine.run.TurnDaemonRunner
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TurnDaemonHealthIndicatorComponentTest {

    @Test
    fun `the indicator is registered as a component and its health path runs`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withPropertyValues("opensamguk.profile=che:test", "opensamguk.daemon.enabled=false")
            .withBean(WorldStateAvailability::class.java, { WorldStateAvailability { false } })
            .withBean(TurnDaemonRunner::class.java)
            .withUserConfiguration(StatusPackageScan::class.java)
            .run { context ->
                assertNull(context.startupFailure, "context failure: ${context.startupFailure}")
                val indicator = context.getBean(TurnDaemonHealthIndicator::class.java)
                val health = indicator.health()
                assertNotNull(health.status)
                assertEquals("disabled", health.details["daemon"])
            }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan("opensamguk.engine.status")
    class StatusPackageScan
}

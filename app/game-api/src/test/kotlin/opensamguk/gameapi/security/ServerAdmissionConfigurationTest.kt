package opensamguk.gameapi.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity

class ServerAdmissionConfigurationTest {
    @Configuration @EnableWebSecurity open class JwtOnly {
        @Bean open fun verifier() = GameApiJwtVerifier("", java.util.Base64.getEncoder().encodeToString(ByteArray(48) { (it + 1).toByte() }), "2099-01-01T00:00:00Z")
        @Bean open fun jwt(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
    }
    @Test fun `security chain cannot start without mandatory policy bean`() {
        WebApplicationContextRunner().withUserConfiguration(JwtOnly::class.java, GameApiSecurityConfig::class.java).run { context ->
            assertNotNull(context.startupFailure)
            assertTrue(generateSequence(context.startupFailure) { it.cause }.any { it.message.orEmpty().contains("ServerAdmissionPolicy") })
        }
    }
    @Test fun `production configuration missing any source coordinate does not create permissive bean`() {
        val required = listOf("server-admission.server-id=pep", "server-admission.gateway-origin=http://127.0.0.1:1", "server-admission.service-token=test-only-service")
        for (missing in required.indices) ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java)).withUserConfiguration(ServerAdmissionConfiguration::class.java)
            .withPropertyValues(*required.filterIndexed { i, _ -> i != missing }.toTypedArray()).run { context ->
                assertNotNull(context.startupFailure)
            }
    }
    @Test fun `configured beans are singleton but unavailable source is never implicit PUBLIC`() {
        ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java)).withUserConfiguration(ServerAdmissionConfiguration::class.java).withPropertyValues(
            "server-admission.server-id=pep", "server-admission.gateway-origin=http://127.0.0.1:1", "server-admission.service-token=test-only-service",
        ).run { context ->
            assertNull(context.startupFailure)
            val policy = context.getBean(ServerAdmissionPolicy::class.java)
            assertSame(policy, context.getBean(ServerAdmissionPolicy::class.java))
            assertEquals(ServerAdmissionDecision.Denied.UNAVAILABLE, policy.checkOrdinary())
        }
    }
}

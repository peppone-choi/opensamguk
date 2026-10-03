package opensamguk.engine.config

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.DispatcherType
import opensamguk.engine.security.TurnDaemonControlBinding
import opensamguk.engine.security.TurnDaemonControlFilter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

@Configuration(proxyBeanMethods = false)
class TurnDaemonControlSecurityConfiguration {
    @Bean
    fun turnDaemonControlBinding(
        @Value("\${ENGINE_CONTROL_BINDING_JSON:}") raw: String,
        mapper: ObjectMapper,
        processWorld: EngineProcessWorld,
    ): TurnDaemonControlBinding = TurnDaemonControlBinding(raw, mapper, processWorld)

    @Bean
    fun turnDaemonControlFilterRegistration(
        binding: TurnDaemonControlBinding,
        @Qualifier("requestMappingHandlerMapping") mappings: RequestMappingHandlerMapping,
    ): FilterRegistrationBean<TurnDaemonControlFilter> =
        FilterRegistrationBean(TurnDaemonControlFilter(binding, mappings)).apply {
            setName("turnDaemonControlFilter")
            addUrlPatterns("/*")
            setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.FORWARD, DispatcherType.ERROR, DispatcherType.ASYNC)
            isAsyncSupported = true
            // After method decoration and before DispatcherServlet, without OncePerRequestFilter skips.
            order = Ordered.LOWEST_PRECEDENCE - 100
        }
}

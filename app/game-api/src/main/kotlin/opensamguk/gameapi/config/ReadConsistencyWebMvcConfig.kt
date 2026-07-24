package opensamguk.gameapi.config

import opensamguk.gameapi.consistency.ReadConsistencyInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class ReadConsistencyWebMvcConfig(
    private val readConsistencyInterceptor: ReadConsistencyInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(readConsistencyInterceptor)
            .addPathPatterns("/api/**")
    }
}

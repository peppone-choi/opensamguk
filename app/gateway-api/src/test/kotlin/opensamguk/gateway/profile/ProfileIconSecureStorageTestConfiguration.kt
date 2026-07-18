package opensamguk.gateway.profile

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration(proxyBeanMethods = false)
class ProfileIconSecureStorageTestConfiguration {
    @Bean
    @Primary
    fun secureProfileIconRootStreamFactory(): ProfileIconRootStreamFactory = secureTestRootStreamFactory()
}

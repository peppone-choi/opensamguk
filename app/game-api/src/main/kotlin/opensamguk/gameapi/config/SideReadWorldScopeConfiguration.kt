package opensamguk.gameapi.config

import opensamguk.infra.read.SideReadRepositoryConfiguration
import opensamguk.infra.read.SideReadWorldScope
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

@Configuration(proxyBeanMethods = false)
@Import(SideReadRepositoryConfiguration::class)
class SideReadWorldScopeConfiguration {
    @Bean
    fun sideReadWorldScope(processWorld: GameApiProcessWorld): SideReadWorldScope =
        SideReadWorldScope(processWorld.worldId)
}

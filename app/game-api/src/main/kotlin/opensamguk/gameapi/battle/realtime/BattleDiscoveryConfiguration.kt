package opensamguk.gameapi.battle.realtime

import opensamguk.infra.battle.realtime.BattleSessionDiscovery
import opensamguk.infra.battle.realtime.JdbcBattleSessionDiscovery
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

@Configuration(proxyBeanMethods = false)
class BattleDiscoveryConfiguration {
    @Bean
    fun battleSessionDiscovery(jdbc: NamedParameterJdbcTemplate): BattleSessionDiscovery =
        JdbcBattleSessionDiscovery(jdbc)
}

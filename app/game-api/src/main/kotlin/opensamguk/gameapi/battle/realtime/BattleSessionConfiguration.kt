package opensamguk.gameapi.battle.realtime

import javax.sql.DataSource
import opensamguk.infra.battle.realtime.BattleSessionStore
import opensamguk.infra.battle.realtime.JdbcBattleSessionStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

@Configuration(proxyBeanMethods = false)
class BattleSessionConfiguration {
    @Bean
    fun battleSessionStore(jdbc: NamedParameterJdbcTemplate, dataSource: DataSource): BattleSessionStore =
        JdbcBattleSessionStore(jdbc, dataSource)

    @Bean
    fun battleSessionCoordinator(store: BattleSessionStore): BattleSessionCoordinator =
        BattleSessionCoordinator(store)
}

package opensamguk.gameapi.battle.realtime

import opensamguk.logic.battle.realtime.TacticalBoardCatalog
import opensamguk.logic.battle.realtime.WaryongBoardCatalogResource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class BattleCatalogConfiguration {
    @Bean
    fun tacticalBoardCatalog(): TacticalBoardCatalog = WaryongBoardCatalogResource.load()

    @Bean
    fun battleFrozenInputCodec(catalog: TacticalBoardCatalog): BattleFrozenInputCodec =
        BattleFrozenInputCodec(catalog)
}

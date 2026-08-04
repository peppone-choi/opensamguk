package opensamguk.infra.read

import opensamguk.common.world.WorldId
import opensamguk.infra.worldstate.ProcessWorldStateRepository
import opensamguk.infra.worldstate.WorldStateRawRepository
import opensamguk.infra.worldstate.WorldStateRepository
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class SideReadWorldScope(val worldId: WorldId)

@Configuration(proxyBeanMethods = false)
class SideReadRepositoryConfiguration {
    @Bean
    fun selectPoolRepository(jdbc: NamedParameterJdbcTemplate, scope: SideReadWorldScope): SelectPoolRepository =
        SelectPoolRepository(jdbc, scope.worldId)

    @Bean
    fun votePollRepository(jdbc: NamedParameterJdbcTemplate, scope: SideReadWorldScope): VotePollRepository =
        VotePollRepository(jdbc, scope.worldId)

    @Bean
    fun diplomacyLetterRepository(jdbc: NamedParameterJdbcTemplate, scope: SideReadWorldScope): DiplomacyLetterRepository =
        DiplomacyLetterRepository(jdbc, scope.worldId)

    @Bean
    fun auctionRepository(context: ApplicationContext, scope: SideReadWorldScope): AuctionRepository =
        WorldScopedAuctionRepository(context.getBean(AuctionRawRepository::class.java), scope.worldId)

    @Bean
    fun auctionBidRepository(context: ApplicationContext, scope: SideReadWorldScope): AuctionBidRepository =
        WorldScopedAuctionBidRepository(context.getBean(AuctionBidRawRepository::class.java), scope.worldId)

    @Bean
    fun bettingRepository(context: ApplicationContext, scope: SideReadWorldScope): BettingRepository =
        WorldScopedBettingRepository(context.getBean(BettingRawRepository::class.java), scope.worldId)

    @Bean
    fun boardPostRepository(context: ApplicationContext, scope: SideReadWorldScope): BoardPostRepository =
        WorldScopedBoardPostRepository(context.getBean(BoardPostRawRepository::class.java), scope.worldId)

    @Bean
    fun gameKvRepository(context: ApplicationContext, scope: SideReadWorldScope): GameKvRepository =
        WorldScopedGameKvRepository(context.getBean(GameKvRawRepository::class.java), scope.worldId)

    @Bean
    fun inheritanceRepository(context: ApplicationContext): InheritanceRepository =
        GlobalInheritanceRepository(context.getBean(InheritanceRawRepository::class.java))

    @Bean
    fun messageRepository(raw: MessageRawRepository, scope: SideReadWorldScope): MessageRepository =
        MessageRepository(raw, scope.worldId.value, 0)

    @Bean
    fun diplomacyRepository(context: ApplicationContext, scope: SideReadWorldScope): DiplomacyRepository =
        WorldScopedDiplomacyRepository(context.getBean(DiplomacyRawRepository::class.java), scope.worldId)

    @Bean
    fun worldStateRepository(context: ApplicationContext, scope: SideReadWorldScope): WorldStateRepository =
        ProcessWorldStateRepository(context.getBean(WorldStateRawRepository::class.java), scope.worldId)
}

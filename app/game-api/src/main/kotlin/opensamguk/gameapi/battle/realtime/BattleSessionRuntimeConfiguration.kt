package opensamguk.gameapi.battle.realtime

import java.util.UUID
import opensamguk.infra.battle.realtime.BattleSessionDiscovery
import opensamguk.infra.battle.realtime.BattleSessionStore
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/** The co-located actor is a development slice; production requires the battle-engine process. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "battle.session", name = ["enabled"], havingValue = "true")
class BattleSessionRuntimeConfiguration {
    @Bean
    fun battleSessionCadence(store: BattleSessionStore): BattleSessionCadence =
        BattleSessionCadence(store, FixedRateBattleTimer(), FixedPoolBattleWorker())

    @Bean
    fun battleSessionResultPublisher(store: BattleSessionStore): BattleSessionResultPublisher =
        BattleSessionResultPublisher(store)

    @Bean
    fun battleSessionBootstrap(discovery: BattleSessionDiscovery, store: BattleSessionStore,
                               cadence: BattleSessionCadence, codec: BattleFrozenInputCodec,
                               results: BattleSessionResultPublisher): BattleSessionBootstrap {
        val log = LoggerFactory.getLogger(BattleSessionBootstrap::class.java)
        return BattleSessionBootstrap(discovery, store, cadence, codec::initialState,
            "game-api-${UUID.randomUUID()}", { key, attempt ->
                if (attempt is BattleTickAttempt.Resolved && !results.publish(key, attempt))
                    error("battle result publication rejected: ${key.worldId}/${key.battleId}")
            }, { key, failure ->
                log.error("battle actor failed: world={} battle={} epoch={}",
                    key.worldId, key.battleId, key.epoch, failure)
            })
    }

    @Bean
    fun battleSessionScanner(bootstrap: BattleSessionBootstrap): BattleSessionScanner =
        BattleSessionScanner(bootstrap)
}

class BattleSessionScanner(private val bootstrap: BattleSessionBootstrap) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${battle.session.scan-delay-ms:1000}")
    fun scan() {
        runCatching { bootstrap.scan() }.onFailure { log.error("battle discovery scan failed", it) }
    }
}

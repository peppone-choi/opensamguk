package opensamguk.gameapi.battle.realtime

import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/** Disabled until V73 and the campaign producer are installed and accepted. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "battle.handoff-intake", name = ["enabled"], havingValue = "true")
class BattleHandoffIntakeConfiguration(
    private val db: NamedParameterJdbcTemplate,
    private val coordinator: BattleSessionCoordinator,
    private val processWorld: GameApiProcessWorld,
) {
    @Bean
    fun battleHandoffIntake(): BattleHandoffIntake = BattleHandoffIntake(
        JdbcCommittedBattleHandoffReader(db), coordinator, JdbcBattleHandoffRejectionWriter(db))

    @Bean
    fun battleHandoffScanner(intake: BattleHandoffIntake): BattleHandoffScanner =
        BattleHandoffScanner(intake, processWorld)
}

class BattleHandoffScanner(private val intake: BattleHandoffIntake,
                           private val processWorld: GameApiProcessWorld) {
    @Scheduled(fixedDelayString = "\${battle.handoff-intake.scan-delay-ms:5000}")
    fun scan() { intake.scan(processWorld.worldId) }
}

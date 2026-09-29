package opensamguk.engine.config

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import opensamguk.engine.campaign.BattleOutcomeBatchSink
import opensamguk.engine.campaign.QaBattleOutcomeFileSink
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class QaBattleOutcomeFileConfig {
    @Bean
    @ConditionalOnProperty(name = ["qa.battle-outcome-file.enabled"], havingValue = "true")
    fun qaBattleOutcomeFileSink(
        @Value("\${qa.battle-outcome-file.directory}") directory: String,
        processWorld: EngineProcessWorld,
        objectMapper: ObjectMapper,
    ): BattleOutcomeBatchSink {
        require(processWorld.worldId.value == 990002) {
            "QA battle file sink is limited to the isolated scenario_990002 world"
        }
        return QaBattleOutcomeFileSink(Path.of(directory), objectMapper)
    }
}

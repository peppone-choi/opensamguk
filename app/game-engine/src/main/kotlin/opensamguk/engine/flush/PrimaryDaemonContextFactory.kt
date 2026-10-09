package opensamguk.engine.flush

import opensamguk.engine.GameEngineApplication
import opensamguk.engine.run.TurnRunService
import org.springframework.boot.runApplication
import org.springframework.context.ConfigurableApplicationContext

/** A recovery context always loads existing primary data and cannot seed an empty world. */
class PrimaryDaemonContextFactory {
    fun prepare(arguments: Array<String>): ConfigurableApplicationContext {
        val fresh = runApplication<GameEngineApplication>(*arguments, "--SCENARIO_SEED_ENABLED=false")
        return try {
            fresh.getBean(TurnRunService::class.java)
            fresh
        } catch (error: Exception) {
            fresh.close()
            throw error
        }
    }
}

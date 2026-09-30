package opensamguk.engine.config

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import opensamguk.engine.campaign.BattleOutcomeBatchSink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QaBattleOutcomeFileConfigTest {
    @TempDir lateinit var directory: Path

    private fun runner(worldId: Int) = ApplicationContextRunner()
        .withUserConfiguration(QaBattleOutcomeFileConfig::class.java)
        .withBean(EngineProcessWorld::class.java, { EngineProcessWorld(worldId) })
        .withBean(ObjectMapper::class.java, { ObjectMapper() })

    @Test
    fun `file sink is absent unless explicitly enabled`() {
        runner(990002).run { context ->
            assertTrue(context.getBeansOfType(BattleOutcomeBatchSink::class.java).isEmpty())
        }
    }

    @Test
    fun `file sink accepts only the isolated QA world`() {
        val enabled = "qa.battle-outcome-file.enabled=true"
        val path = "qa.battle-outcome-file.directory=$directory"
        runner(990002).withPropertyValues(enabled, path).run { context ->
            assertEquals(1, context.getBeansOfType(BattleOutcomeBatchSink::class.java).size)
        }
        runner(1).withPropertyValues(enabled, path).run { context ->
            assertNotNull(context.startupFailure)
        }
    }
}

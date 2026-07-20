package opensamguk.gameapi.config

import opensamguk.common.world.WorldId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameApiProcessWorldIdConfigurationTest {
    private val configuration = GameApiProcessWorldIdConfiguration()

    @Test
    fun `process world id accepts only positive ids`() {
        assertEquals(WorldId(7), configuration.gameApiProcessWorld(7).worldId)
        assertFailsWith<IllegalArgumentException> { GameApiProcessWorld(0) }
    }

    @Test
    fun `production world id is required from OPENSAMGUK_WORLD_ID`() {
        val production = readProjectFile("src/main/resources/application.yml")
        val test = readProjectFile("src/test/resources/application-test.yml")

        assertTrue(production.contains("world-id: \${OPENSAMGUK_WORLD_ID}"))
        assertFalse(production.contains("world-id: \${OPENSAMGUK_WORLD_ID:"))
        assertTrue(test.contains("opensamguk:\n  world-id: 1"))
    }

    private fun readProjectFile(relativePath: String): String {
        val candidates = listOf(Path.of(relativePath), Path.of("app/game-api").resolve(relativePath))
        return Files.readString(candidates.first { Files.exists(it) })
    }
}

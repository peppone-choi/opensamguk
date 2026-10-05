package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.infra.seed.CapturedScenarioOriginal
import opensamguk.infra.seed.SelectedScenarioOrigin
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** The classpath fixture exercises only the same-capture hook, never FINAL_SELECTED custody. */
class D101SeedSelectionHookTest {
    @Test
    fun `selected original callback sees the same classpath bytes used for parsing`() {
        val selected = mutableListOf<CapturedScenarioOriginal>()
        val scenario = SeedBootstrap(scenarioCode = "scenario_3190", worldId = WorldId(1),
            onSelectedOriginal = { selected += it }).loadScenario()
        val original = assertNotNull(selected.singleOrNull())
        val classpathBytes = requireNotNull(javaClass.classLoader.getResourceAsStream("scenario/scenario_3190.json"))
            .use { it.readBytes() }
        assertEquals(190, scenario.startYear)
        assertEquals(SelectedScenarioOrigin.CLASSPATH, original.origin)
        assertEquals("scenario/scenario_3190.json", original.logicalId)
        assertContentEquals(classpathBytes, original.originalBytes())
        assertEquals(classpathBytes.size.toLong(), original.byteLength)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(classpathBytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }, original.rawSha256)
    }

    @Test
    fun `observer failure rejects selected source without classpath fallback`() {
        val bootstrap = SeedBootstrap(scenarioCode = "scenario_3190", worldId = WorldId(1),
            onSelectedOriginal = { throw IllegalStateException("observer unavailable") })
        assertFailsWith<IllegalStateException> { bootstrap.loadScenario() }
    }
}

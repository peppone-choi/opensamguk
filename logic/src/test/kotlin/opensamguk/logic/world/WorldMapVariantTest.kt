package opensamguk.logic.world

import org.junit.jupiter.api.Test
import kotlin.test.*

/** These counts identify archived save fixtures, not a limit on current or future maps. */
class WorldMapVariantTest {
    @Test fun `historical roster has no path to new cities and retains its logical name`() {
        val old = CityConstRegistry.hanWorld(WorldMapVariant.V3_832)
        val current = CityConstRegistry.hanWorld(WorldMapVariant.V3_835)
        assertEquals("han-world-v3", old.mapName)
        assertEquals((1..832).toSet(), old.all().keys)
        assertEquals((1..835).toSet(), current.all().keys)
        assertTrue(old.all().values.all { city -> city.path.keys.all { it in old.all() } })
        assertNull(old.byId(833))
        assertTrue(old.gateKeys(833).isEmpty())
    }
    @Test fun `runtime selection keeps logical name and rejects use on another map`() {
        val old = ActiveWorldMap.requireVariant(mapOf("mapName" to "han-world-v3"), emptyMap(), WorldMapVariant.V3_832)
        assertEquals(832, old.all().size)
        assertEquals("han-world-v3", old.mapName)
        assertFailsWith<IllegalArgumentException> {
            ActiveWorldMap.requireVariant(mapOf("mapName" to "che"), emptyMap(), WorldMapVariant.V3_832)
        }
    }

    @Test fun `action and constraint contexts share selected historical constants`() {
        for (variant in WorldMapVariant.entries) {
            val env = opensamguk.logic.domain.WorldEnv(200, 190, 40,
                mapName = "han-world-v3", worldMapVariant = variant)
            for (mode in opensamguk.logic.constraints.ConstraintMode.entries) {
                val constraint = opensamguk.logic.constraints.ConstraintContext(actorId = 1,
                    env = mapOf("mapName" to env.mapName), mode = mode, worldMapVariant = variant)
                assertSame(CityConstRegistry.hanWorld(variant), env.cityConst)
                assertSame(env.cityConst, constraint.selectedCityConst())
            }
        }
        assertFailsWith<IllegalArgumentException> {
            opensamguk.logic.domain.WorldEnv(200, 190, 40, mapName = "han-world-v3").cityConst
        }
        assertNull(opensamguk.logic.constraints.ConstraintContext(actorId = 1,
            env = mapOf("mapName" to "han-world-v3"), mode = opensamguk.logic.constraints.ConstraintMode.FULL).selectedCityConst())
    }

}

package opensamguk.logic.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ActiveWorldMapTest {
    @Test
    fun `requires explicit known map without che fallback`() {
        assertFailsWith<IllegalStateException> { ActiveWorldMap.requireName(emptyMap(), emptyMap()) }
        assertFailsWith<IllegalArgumentException> {
            ActiveWorldMap.requireName(mapOf("mapName" to "unknown"), emptyMap())
        }
    }

    @Test
    fun `uses canonical priority and resolves the same variant`() {
        val config = mapOf<String, Any?>("map" to mapOf("mapName" to "han"))
        val meta = mapOf<String, Any?>("mapName" to "che")

        assertEquals("han", ActiveWorldMap.requireName(config, meta))
        assertSame(CityConstRegistry.of("han"), ActiveWorldMap.requireVariant(config, meta))
    }
}

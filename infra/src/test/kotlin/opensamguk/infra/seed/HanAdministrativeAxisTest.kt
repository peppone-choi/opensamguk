package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HanAdministrativeAxisTest {
    private fun resource(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }

    @Test
    fun `pinned world projects canonical counties and explicitly classifies gaps`() {
        val projection = HanAdministrativeAxis.loadPinned()
        assertEquals(1447, projection.counties.size)
        assertEquals(105, projection.commanderyToZhou.size)
        assertEquals(1127, projection.counties.values.count { it.coverage == AdministrativeCoverage.CANONICAL })
        assertEquals(61, projection.counties.values.count { it.coverage == AdministrativeCoverage.OUTSIDE_CANON })
        assertEquals(259, projection.counties.values.count { it.coverage == AdministrativeCoverage.UNRESOLVED_PARENT })
        assertTrue(projection.counties.values.filter { it.coverage == AdministrativeCoverage.CANONICAL }.all { it.commanderyId != null && it.zhouId != null })
        assertNull(projection.commanderyFor(849))
    }

    @Test
    fun `removing one commandery zhou assignment fails independently of pin`() {
        val world = resource("/map/han-world-v3.json")
        val mapper = ObjectMapper()
        val axis = mapper.readTree(resource("/administration/administrative-zhou-axis.json"))
        (axis["rows"] as ArrayNode).remove(0)
        assertFailsWith<IllegalArgumentException> { HanAdministrativeAxis.project(world, mapper.writeValueAsBytes(axis)) }
    }

    @Test
    fun `changing world bundle fails its administrative pin`() {
        val world = resource("/map/han-world-v3.json") + byteArrayOf(32)
        val axis = resource("/administration/administrative-zhou-axis.json")
        val pin = resource("/administration/administrative-axis-pin.json")
        assertFailsWith<IllegalArgumentException> { HanAdministrativeAxis.fromPinned(world, axis, pin) }
    }
}

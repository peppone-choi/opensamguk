package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
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
        assertEquals(1, projection.baseSeatFor("hhs-group:109:京兆尹"))
        assertEquals(101, projection.baseCommanderySeatById.size)
        assertEquals(setOf("hhs-group:110:清河國", "hhs-group:111:泰山郡", "hhs-group:112:齊國", "hhs-group:113:張掖屬國"), projection.unresolvedBaseSeatIds)
        assertNull(projection.baseSeatFor("hhs-group:111:泰山郡"))
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

    @Test
    fun `second base seat in one commandery fails projection`() {
        val mapper = ObjectMapper()
        val world = mapper.readTree(resource("/map/han-world-v3.json"))
        val city = world["cities"].first { it["id"].asInt() == 3 }
        (city["meta"] as ObjectNode).put("isSeat", true)
        assertFailsWith<IllegalArgumentException> {
            HanAdministrativeAxis.project(mapper.writeValueAsBytes(world), resource("/administration/administrative-zhou-axis.json"))
        }
    }
}

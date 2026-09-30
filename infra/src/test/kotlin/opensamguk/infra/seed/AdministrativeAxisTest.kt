package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdministrativeAxisTest {
    private fun resource(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }

    @Test
    fun `pinned world projects canonical counties and explicitly classifies gaps`() {
        val projection = AdministrativeAxis.loadPinned()
        // 2026-09-27 1428 판: 중복 합성 城 23곳(郡國志 단위를 달고 있었다)이 빠지고 동명 실결손 4곳이 들어왔다.
        assertEquals(1428, projection.counties.size)
        assertEquals(105, projection.commanderyToZhou.size)
        assertEquals(1108, projection.counties.values.count { it.coverage == AdministrativeCoverage.CANONICAL })
        assertEquals(61, projection.counties.values.count { it.coverage == AdministrativeCoverage.OUTSIDE_CANON })
        assertEquals(259, projection.counties.values.count { it.coverage == AdministrativeCoverage.UNRESOLVED_PARENT })
        assertEquals(1, projection.baseSeatFor("hhs-group:109:京兆尹"))
        // 東海·鉅鹿·左馮翊·魯國 治所는 은퇴한 합성 城이 맡고 있었다 — 같은 縣인 기존 城은 아직 郡國志 단위 없이 선다.
        assertEquals(97, projection.baseCommanderySeatById.size)
        assertEquals(setOf("hhs-group:110:清河國", "hhs-group:111:泰山郡", "hhs-group:112:齊國", "hhs-group:113:張掖屬國",
            "hhs-group:111:東海郡", "hhs-group:110:鉅鹿郡", "hhs-group:109:左馮翊", "hhs-group:110:魯國"), projection.unresolvedBaseSeatIds)
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
        assertFailsWith<IllegalArgumentException> { AdministrativeAxis.project(world, mapper.writeValueAsBytes(axis)) }
    }

    @Test
    fun `changing world bundle fails its administrative pin`() {
        val world = resource("/map/han-world-v3.json") + byteArrayOf(32)
        val axis = resource("/administration/administrative-zhou-axis.json")
        val pin = resource("/administration/administrative-axis-pin.json")
        assertFailsWith<IllegalArgumentException> { AdministrativeAxis.fromPinned(world, axis, pin) }
    }

    @Test
    fun `second base seat in one commandery fails projection`() {
        val mapper = ObjectMapper()
        val world = mapper.readTree(resource("/map/han-world-v3.json"))
        val city = world["cities"].first { it["id"].asInt() == 3 }
        (city["meta"] as ObjectNode).put("isSeat", true)
        assertFailsWith<IllegalArgumentException> {
            AdministrativeAxis.project(mapper.writeValueAsBytes(world), resource("/administration/administrative-zhou-axis.json"))
        }
    }
}

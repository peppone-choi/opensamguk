package opensamguk.gameapi.controller

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.dto.SupplyReasonDto
import kotlin.test.*

class MapPreviewSupplyReasonTest {
    private fun snapshot(code: String = "NO_SOURCE") = linkedMapOf<String, Any?>(
        "version" to 1, "code" to code, "cityId" to 2, "nationId" to 1, "worldId" to 1,
        "year" to 200, "month" to 1, "phase" to 1, "mapName" to "han-world-v3", "topologyHash" to "a".repeat(64))
    private fun read(raw: Any?) = SupplyReasonDto.fromSnapshot(raw, 2, 1, false, 1, 200, 1, 1, "han-world-v3", "a".repeat(64))
    @Test fun `only matching persisted city owner date and topology expose a reason`() {
        assertEquals("자국 수도 보급원 없음", read(snapshot())?.label)
        for ((key, wrong) in listOf("cityId" to 3, "nationId" to 2, "worldId" to 2, "year" to 201,
            "month" to 2, "phase" to 2, "mapName" to "other", "topologyHash" to "b".repeat(64), "version" to 2, "nationId" to 1.5)) {
            assertNull(read(snapshot().apply { this[key] = wrong }), key)
        }
        assertNull(read(null)); assertNull(read(snapshot("INVENTED_ENEMY")))
    }
    @Test fun `unknown remains explicit and no private graph information is serialized`() {
        val value = read(snapshot("UNKNOWN"))!!
        assertEquals("단절 사유 확인 불가", value.label)
        val json = ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(value)
        assertEquals(setOf("code", "label", "year", "month", "phase"), json.fieldNames().asSequence().toSet())
    }
    @Test fun `supplied and neutral cities never show an obsolete cut reason`() {
        assertNull(SupplyReasonDto.fromSnapshot(snapshot(), 2, 1, true, 1, 200, 1, 1, "han-world-v3", "a".repeat(64)))
        assertNull(SupplyReasonDto.fromSnapshot(snapshot().apply { this["nationId"] = 0 }, 2, 0, false, 1, 200, 1, 1, "han-world-v3", "a".repeat(64)))
    }
}

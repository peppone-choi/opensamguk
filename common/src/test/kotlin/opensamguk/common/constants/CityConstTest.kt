package opensamguk.common.constants

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CityConstTest {

    private fun loadGolden(): JsonArray {
        val text = checkNotNull(this::class.java.getResource("/golden/city_const.golden.json")) {
            "missing golden fixture"
        }.readText()
        return Json.parseToJsonElement(text).jsonArray
    }

    private fun pathMap(obj: JsonObject): Map<Int, String> =
        obj.entries.associate { (k, v) -> k.toInt() to v.jsonPrimitive.content }

    @Test fun allCitiesMatchGolden() {
        val golden = loadGolden()
        val all = CityConst.all()
        assertEquals(golden.size, all.size, "row count mismatch")
        assertEquals(94, all.size, "expected 94 cities")

        for (el in golden) {
            val o = el.jsonObject
            val id = o.getValue("id").jsonPrimitive.int
            val c = checkNotNull(all[id]) { "missing city id=$id" }

            assertEquals(o.getValue("id").jsonPrimitive.int, c.id, "id")
            assertEquals(o.getValue("name").jsonPrimitive.content, c.name, "name id=$id")
            assertEquals(o.getValue("level").jsonPrimitive.int, c.level, "level id=$id")
            assertEquals(o.getValue("population").jsonPrimitive.int, c.population, "population id=$id")
            assertEquals(o.getValue("agriculture").jsonPrimitive.int, c.agriculture, "agriculture id=$id")
            assertEquals(o.getValue("commerce").jsonPrimitive.int, c.commerce, "commerce id=$id")
            assertEquals(o.getValue("security").jsonPrimitive.int, c.security, "security id=$id")
            assertEquals(o.getValue("defence").jsonPrimitive.int, c.defence, "defence id=$id")
            assertEquals(o.getValue("wall").jsonPrimitive.int, c.wall, "wall id=$id")
            assertEquals(o.getValue("region").jsonPrimitive.int, c.region, "region id=$id")
            assertEquals(o.getValue("posX").jsonPrimitive.int, c.posX, "posX id=$id")
            assertEquals(o.getValue("posY").jsonPrimitive.int, c.posY, "posY id=$id")
            assertEquals(pathMap(o.getValue("path").jsonObject), c.path, "path id=$id")
        }
    }

    @Test fun statsScaledByHundred() {
        // 업(1) raw agri 125 -> 12500; pop 6205 -> 620500
        val 업 = checkNotNull(CityConst.byName("업"))
        assertEquals(12500, 업.agriculture)
        assertEquals(620500, 업.population)
        // posX/posY NOT scaled
        assertEquals(345, 업.posX)
        assertEquals(130, 업.posY)
    }

    @Test fun levelAndRegionResolvedToInts() {
        assertEquals(8, CityConst.byName("업")!!.level)       // 특
        assertEquals(1, CityConst.byName("업")!!.region)      // 하북
        assertEquals(4, CityConst.byName("강")!!.level)       // 이 (이민족 전용)
        assertEquals(5, CityConst.byName("진양")!!.level)     // 소 (한족 군 치소) — distinct from 이
    }

    @Test fun bidirectionalConnectivity() {
        // Mirrors CityConstBase::test(): every connected city lists this city back.
        for ((id, city) in CityConst.all()) {
            for ((connId, _) in city.path) {
                val conn = checkNotNull(CityConst.byId(connId)) { "missing conn id=$connId" }
                assertTrue(
                    conn.path.containsKey(id),
                    "도시 연결이 올바르지 않음! ${city.name} <=> ${conn.name}",
                )
            }
        }
    }

    @Test fun byRegionLastWinsQuirk() {
        // PHP overwrites constRegion[region] with each city, so byRegion returns the LAST city
        // (highest id, insertion order) in that region. Verify against the golden's last-per-region.
        val lastPerRegion = HashMap<Int, CityInitialDetail>()
        for ((_, c) in CityConst.all()) lastPerRegion[c.region] = c
        for ((region, expected) in lastPerRegion) {
            assertEquals(expected.id, CityConst.byRegion(region)!!.id, "byRegion last-wins region=$region")
        }
    }
}

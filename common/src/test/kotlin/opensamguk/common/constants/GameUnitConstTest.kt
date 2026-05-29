package opensamguk.common.constants

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameUnitConstTest {

    private fun loadGolden(): JsonArray {
        val text = checkNotNull(this::class.java.getResource("/golden/game_unit_const.golden.json")) {
            "missing golden fixture"
        }.readText()
        return Json.parseToJsonElement(text).jsonArray
    }

    private fun intKeyMap(obj: JsonObject): Map<Int, Double> =
        obj.entries.associate { (k, v) -> k.toInt() to v.jsonPrimitive.double }

    private fun strListOrNull(arr: kotlinx.serialization.json.JsonElement?): List<String>? =
        when (arr) {
            null, is kotlinx.serialization.json.JsonNull -> null
            else -> arr.jsonArray.map { it.jsonPrimitive.content }
        }

    @Test fun allUnitsMatchGoldenFieldByField() {
        val golden = loadGolden()
        val all = GameUnitConst.all()
        assertEquals(golden.size, all.size, "row count mismatch")

        for (el in golden) {
            val o = el.jsonObject
            val id = o.getValue("id").jsonPrimitive.int
            val unit = checkNotNull(all[id]) { "missing unit id=$id" }

            assertEquals(o.getValue("id").jsonPrimitive.int, unit.id, "id")
            assertEquals(o.getValue("armType").jsonPrimitive.int, unit.armType, "armType id=$id")
            assertEquals(o.getValue("name").jsonPrimitive.content, unit.name, "name id=$id")
            assertEquals(o.getValue("attack").jsonPrimitive.int, unit.attack, "attack id=$id")
            assertEquals(o.getValue("defence").jsonPrimitive.int, unit.defence, "defence id=$id")
            assertEquals(o.getValue("speed").jsonPrimitive.int, unit.speed, "speed id=$id")
            assertEquals(o.getValue("avoid").jsonPrimitive.int, unit.avoid, "avoid id=$id")
            assertEquals(o.getValue("magicCoef").jsonPrimitive.double, unit.magicCoef, "magicCoef id=$id")
            assertEquals(o.getValue("cost").jsonPrimitive.int, unit.cost, "cost id=$id")
            assertEquals(o.getValue("rice").jsonPrimitive.int, unit.rice, "rice id=$id")
            assertEquals(intKeyMap(o.getValue("attackCoef").jsonObject), unit.attackCoef, "attackCoef id=$id")
            assertEquals(intKeyMap(o.getValue("defenceCoef").jsonObject), unit.defenceCoef, "defenceCoef id=$id")
            assertEquals(o.getValue("info").jsonArray.map { it.jsonPrimitive.content }, unit.info, "info id=$id")
            assertEquals(strListOrNull(o["initSkillTrigger"]), unit.initSkillTrigger, "initSkillTrigger id=$id")
            assertEquals(strListOrNull(o["phaseSkillTrigger"]), unit.phaseSkillTrigger, "phaseSkillTrigger id=$id")
            assertEquals(strListOrNull(o["iActionList"]), unit.iActionList, "iActionList id=$id")
        }
    }

    @Test fun specificIdCoefResolvesBeforeArmType() {
        // 정란(1500) attackCoef has both a specific unit-id (1106) and armType T_CASTLE entries.
        val unit = checkNotNull(GameUnitConst.byId(1500))
        // specific id 1106 wins (1.112) over its armType T_FOOTMAN (which is NOT in the map for 1106's armType anyway)
        assertEquals(1.112, unit.resolveAttackCoef(1106, GameUnitConst.T_FOOTMAN), 0.0)
        // a 1100 footman has no specific entry → falls back to armType T_FOOTMAN coef (1.25)
        assertEquals(1.25, unit.resolveAttackCoef(1100, GameUnitConst.T_FOOTMAN), 0.0)
        // a castle (1000) → armType T_CASTLE coef (1.8)
        assertEquals(1.8, unit.resolveAttackCoef(1000, GameUnitConst.T_CASTLE), 0.0)
    }

    @Test fun byIdBelow1000Throws() {
        assertFailsWith<IllegalArgumentException> { GameUnitConst.byId(999) }
        assertNull(GameUnitConst.byId(9999))
    }

    @Test fun infoEndsWithAppendedConstraintGetInfo() {
        // 1101 청주병: base info ["저렴하고 튼튼합니다."] + ReqTech(1000) + ReqRegions(중원)
        val unit = checkNotNull(GameUnitConst.byId(1101))
        assertEquals(
            listOf("저렴하고 튼튼합니다.", "기술력 1000 이상 필요", "중원 지역 소유시 가능"),
            unit.info,
        )
        // 1000 성벽: Impossible appended
        assertTrue(GameUnitConst.byId(1000)!!.info.last() == "불가능")
    }

    @Test fun byTypeAndByNameIndexed() {
        assertEquals("성벽", checkNotNull(GameUnitConst.byName("성벽")).name)
        // T_WIZARD has 9 units (1400-1408)
        assertEquals(9, GameUnitConst.byType(GameUnitConst.T_WIZARD).size)
        assertEquals(emptyList(), GameUnitConst.byType(GameUnitConst.T_MISC))
    }
}

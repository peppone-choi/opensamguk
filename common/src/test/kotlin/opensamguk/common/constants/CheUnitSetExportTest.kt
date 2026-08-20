package opensamguk.common.constants

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `data/unitset/units.json` 의 **che 세트** 행이 [GameUnitConst] 와 같은지 검사한다.
 *
 * 병종표는 파일 하나다(che·han 두 세트가 그 안에 함께 있다). 그런데 che 세트의 **런타임
 * 진실은 여전히 코틀린**이다 — 골든이 덮고 있는 패러티 면이고, 상수 외부화는 CLAUDE.md 의
 * `M-config` 마일스톤(풀 패러티 close 이후)이다. 그래서 이 테스트는 cutover 가 아니라
 * **드리프트 게이트**다: 코틀린을 고치면 여기서 깨진다.
 *
 * 깨졌을 때 되살리는 길:
 *   1. `./gradlew :common:test --tests '*CheUnitSetExportTest*' -Dunitset.write=true`
 *      → `common/build/che-export.json` 이 나온다.
 *   2. `python3 tools/unitset/build_unitset.py --che common/build/che-export.json`
 */
class CheUnitSetExportTest {

    private val units by lazy {
        Json.parseToJsonElement(File(repoRoot(), "data/unitset/units.json").readText()).jsonObject
    }

    @Test
    fun `units json 의 che 세트가 GameUnitConst 와 같다`() {
        if (System.getProperty("unitset.write") == "true") {
            File(repoRoot(), "common/build/che-export.json")
                .apply { parentFile.mkdirs() }
                .writeText(export())
            return
        }

        val rows = units["crewTypes"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["set"]!!.jsonPrimitive.content == "che" }
            .associateBy { it["id"]!!.jsonPrimitive.int }
        val kotlin = GameUnitConst.all()

        assertEquals(kotlin.keys.sorted(), rows.keys.sorted(), "che 세트 행 목록이 코틀린과 다르다")
        for ((id, u) in kotlin) {
            val r = rows.getValue(id)
            fun i(k: String) = r[k]!!.jsonPrimitive.int
            fun s(k: String) = r[k]!!.jsonPrimitive.content
            assertEquals(u.name, s("name"), "id=$id name")
            assertEquals(u.armType, i("armType"), "id=$id armType")
            assertEquals(u.attack, i("attack"), "id=$id attack")
            assertEquals(u.defence, i("defence"), "id=$id defence")
            assertEquals(u.speed, i("speed"), "id=$id speed")
            assertEquals(u.avoid, i("avoid"), "id=$id avoid")
            assertEquals(u.magicCoef, r["magicCoef"]!!.jsonPrimitive.double, "id=$id magicCoef")
            assertEquals(u.cost, i("cost"), "id=$id cost")
            assertEquals(u.rice, i("rice"), "id=$id rice")
            assertEquals(coefOf(r, "attackCoef"), u.attackCoef, "id=$id attackCoef")
            assertEquals(coefOf(r, "defenceCoef"), u.defenceCoef, "id=$id defenceCoef")
            assertEquals(strsOf(r, "initSkillTrigger"), u.initSkillTrigger, "id=$id initSkillTrigger")
            assertEquals(strsOf(r, "phaseSkillTrigger"), u.phaseSkillTrigger, "id=$id phaseSkillTrigger")
            assertEquals(strsOf(r, "iActionList"), u.iActionList, "id=$id iActionList")
            assertEquals(u.info, strsOf(r, "info"), "id=$id info")
            assertEquals(
                u.reqConstraints.map { Json.parseToJsonElement(constraint(it)) },
                r["reqConstraints"]!!.jsonArray.toList(),
                "id=$id reqConstraints",
            )
        }
    }

    /** 세트 경계가 무너지면 두 표가 섞인다 — id 대역과 기본 병종은 세트 안에 있어야 한다. */
    @Test
    fun `세트 선언이 실제 행과 맞는다`() {
        val rows = units["crewTypes"]!!.jsonArray.map { it.jsonObject }
        for ((set, decl) in units["sets"]!!.jsonObject) {
            val d = decl.jsonObject
            val (lo, hi) = d["idRange"]!!.jsonArray.map { it.jsonPrimitive.int }
            val ids = rows.filter { it["set"]!!.jsonPrimitive.content == set }
                .map { it["id"]!!.jsonPrimitive.int }
            assertTrue(ids.all { it in lo..hi }, "$set 세트 id 대역 이탈")
            for (k in listOf("defaultCrewTypeId", "castleCrewTypeId")) {
                assertTrue(d[k]!!.jsonPrimitive.int in ids, "$set.$k 가 그 세트에 없다")
            }
        }
        assertEquals(GameUnitConst.DEFAULT_CREWTYPE, units["sets"]!!.jsonObject["che"]!!
            .jsonObject["defaultCrewTypeId"]!!.jsonPrimitive.int)
        assertEquals(GameUnitConst.CREWTYPE_CASTLE, units["sets"]!!.jsonObject["che"]!!
            .jsonObject["castleCrewTypeId"]!!.jsonPrimitive.int)
    }

    private fun coefOf(r: JsonObject, k: String): Map<Int, Double> =
        r[k]!!.jsonObject.entries.associate { (a, b) -> a.toInt() to b.jsonPrimitive.double }

    private fun strsOf(r: JsonObject, k: String): List<String>? =
        (r[k] as? JsonArray)?.map { it.jsonPrimitive.content }

    private fun repoRoot(): File {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (!File(d, "settings.gradle.kts").exists()) {
            d = d.parentFile ?: error("저장소 루트를 못 찾았다")
        }
        return d
    }

    // ── 재출력 (-Dunitset.write=true) ──────────────────────────────────────────────
    private fun export(): String = GameUnitConst.all().values.joinToString(",\n", "[\n", "\n]\n") { u ->
        val fields = listOf(
            "\"set\": \"che\"", "\"id\": ${u.id}", "\"name\": ${str(u.name)}",
            "\"armType\": ${u.armType}", "\"attack\": ${u.attack}", "\"defence\": ${u.defence}",
            "\"speed\": ${u.speed}", "\"avoid\": ${u.avoid}", "\"magicCoef\": ${u.magicCoef}",
            "\"cost\": ${u.cost}", "\"rice\": ${u.rice}",
            "\"reqConstraints\": [${u.reqConstraints.joinToString(", ", transform = ::constraint)}]",
            "\"attackCoef\": ${coef(u.attackCoef)}", "\"defenceCoef\": ${coef(u.defenceCoef)}",
            "\"initSkillTrigger\": ${strs(u.initSkillTrigger)}",
            "\"phaseSkillTrigger\": ${strs(u.phaseSkillTrigger)}",
            "\"iActionList\": ${strs(u.iActionList)}",
            "\"info\": [${u.info.joinToString(", ", transform = ::str)}]",
        )
        fields.joinToString(", ", " {", "}")
    }

    private fun str(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    private fun coef(m: Map<Int, Double>) =
        m.entries.joinToString(", ", "{", "}") { (k, v) -> "\"$k\": $v" }

    private fun strs(v: List<String>?) = v?.joinToString(", ", "[", "]", transform = ::str) ?: "null"

    private fun constraint(c: UnitConstraint): String = when (c) {
        is UnitConstraint.Impossible -> "{\"type\": \"Impossible\"}"
        is UnitConstraint.ReqChief -> "{\"type\": \"ReqChief\"}"
        is UnitConstraint.ReqNotChief -> "{\"type\": \"ReqNotChief\"}"
        is UnitConstraint.ReqTech -> "{\"type\": \"ReqTech\", \"reqTech\": ${c.reqTech}}"
        is UnitConstraint.ReqCities -> "{\"type\": \"ReqCities\", \"reqCities\": ${strs(c.reqCities)}}"
        is UnitConstraint.ReqRegions -> "{\"type\": \"ReqRegions\", \"reqRegions\": ${strs(c.reqRegions)}}"
        is UnitConstraint.ForbidRegions ->
            "{\"type\": \"ForbidRegions\", \"forbidRegions\": ${strs(c.forbidRegions)}}"
        is UnitConstraint.ReqMinRelYear -> "{\"type\": \"ReqMinRelYear\", \"reqMinRelYear\": ${c.reqMinRelYear}}"
        is UnitConstraint.ReqCitiesWithCityLevel ->
            "{\"type\": \"ReqCitiesWithCityLevel\", \"reqCityLevel\": ${c.reqCityLevel}, " +
                "\"reqCities\": ${strs(c.reqCities)}}"
        is UnitConstraint.ReqHighLevelCities ->
            "{\"type\": \"ReqHighLevelCities\", \"reqCityLevel\": ${c.reqCityLevel}, " +
                "\"reqCityCount\": ${c.reqCityCount}}"
        is UnitConstraint.ReqNationAux ->
            "{\"type\": \"ReqNationAux\", \"reqNationAuxKey\": ${str(c.reqNationAuxKey)}, " +
                "\"cmp\": ${str(c.cmp)}, \"value\": ${c.value}}"
    }
}

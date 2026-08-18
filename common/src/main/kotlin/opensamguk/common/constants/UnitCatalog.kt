package opensamguk.common.constants

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 병종 세트 카탈로그 — `che`(devsam 원본)와 `han`(후한 군현) 을 한 자리에서 찾는다.
 *
 * **`che` 는 JSON 을 읽지 않는다.** [GameUnitConst] 를 그대로 위임한다 — 골든이 덮고 있는
 * 패러티 면이라 로더를 거치게 두면 파싱 하나로 조용히 어긋날 수 있다. JSON 쪽 che 행은
 * `CheUnitSetExportTest` 가 지키는 사본일 뿐이고, 런타임은 코틀린을 본다.
 *
 * id 대역이 세트마다 갈려 있어(che 1000~1999 · han 2000~2999) **[byId] 는 세트를 몰라도 된다**.
 * 그래서 전투·표시 경로는 세트 문자열을 실어나르지 않고 id 하나로 찾는다. 뽑을 수 있는 병종을
 * *나열*할 때만 세트가 필요하고, 그 자리는 이미 `UnitSetTable` 이 세트를 받고 있다.
 *
 * 원본은 `data/unitset/units.json`, 빌더는 `tools/unitset/build_unitset.py`.
 * 리소스 사본은 gradle `processResources` 가 넣는다.
 */
object UnitCatalog {
    const val CHE = "che"

    data class SetMeta(
        val id: String,
        val name: String,
        val defaultCrewTypeId: Int,
        val castleCrewTypeId: Int,
        val idRange: IntRange,
    )

    private class Loaded(val sets: Map<String, SetMeta>, val units: Map<String, Map<Int, GameUnitDetail>>)

    private val loaded: Loaded by lazy { load() }

    fun sets(): Map<String, SetMeta> = loaded.sets

    fun meta(unitSet: String): SetMeta? = loaded.sets[unitSet]

    fun all(unitSet: String): Map<Int, GameUnitDetail> = loaded.units[unitSet].orEmpty()

    /** 세트를 몰라도 되는 조회 — id 대역이 겹치지 않는다. che 는 [GameUnitConst] 가 답한다. */
    fun byId(id: Int): GameUnitDetail? =
        if (id in 1000..1999) GameUnitConst.byId(id) else loaded.units.values.firstNotNullOfOrNull { it[id] }

    fun byId(unitSet: String, id: Int): GameUnitDetail? = all(unitSet)[id]

    fun setOf(id: Int): String? = loaded.sets.entries.firstOrNull { id in it.value.idRange }?.key

    // ── 적재 ────────────────────────────────────────────────────────────────────────
    private fun load(): Loaded {
        val text = javaClass.getResourceAsStream("/unitset/units.json")?.bufferedReader()?.use { it.readText() }
            ?: error("classpath:/unitset/units.json 이 없다 — gradle processResources 를 확인하라")
        val doc = Json.parseToJsonElement(text).jsonObject

        val sets = doc.getValue("sets").jsonObject.entries.associate { (id, raw) ->
            val o = raw.jsonObject
            val range = o.getValue("idRange").jsonArray.map { it.jsonPrimitive.int }
            id to SetMeta(
                id = id,
                name = o.getValue("name").jsonPrimitive.content,
                defaultCrewTypeId = o.getValue("defaultCrewTypeId").jsonPrimitive.int,
                castleCrewTypeId = o.getValue("castleCrewTypeId").jsonPrimitive.int,
                idRange = range[0]..range[1],
            )
        }

        val units = HashMap<String, MutableMap<Int, GameUnitDetail>>()
        units[CHE] = LinkedHashMap(GameUnitConst.all())   // 코틀린이 답한다
        for (row in doc.getValue("crewTypes").jsonArray.map { it.jsonObject }) {
            val set = row.getValue("set").jsonPrimitive.content
            if (set == CHE) continue
            units.getOrPut(set) { LinkedHashMap() }[row.getValue("id").jsonPrimitive.int] = detail(row)
        }
        for ((set, rows) in units) {
            val range = sets[set]?.idRange ?: error("units.json: 선언되지 않은 세트 '$set'")
            val stray = rows.keys.filterNot { it in range }
            require(stray.isEmpty()) { "units.json: $set 세트 id 대역 이탈 $stray" }
        }
        return Loaded(sets, units.mapValues { it.value.toMap() })
    }

    private fun detail(r: JsonObject): GameUnitDetail {
        fun i(k: String) = r.getValue(k).jsonPrimitive.int
        fun strs(k: String) = (r[k] as? JsonArray)?.map { it.jsonPrimitive.content }
        fun coef(k: String) = r.getValue(k).jsonObject.entries
            .associateTo(LinkedHashMap()) { (a, b) -> a.toInt() to b.jsonPrimitive.content.toDouble() }
        val constraints = r.getValue("reqConstraints").jsonArray.map { constraint(it.jsonObject) }
        return GameUnitDetail(
            id = i("id"),
            armType = i("armType"),
            name = r.getValue("name").jsonPrimitive.content,
            attack = i("attack"),
            defence = i("defence"),
            speed = i("speed"),
            avoid = i("avoid"),
            magicCoef = r.getValue("magicCoef").jsonPrimitive.content.toDouble(),
            cost = i("cost"),
            rice = i("rice"),
            reqConstraints = constraints,
            attackCoef = coef("attackCoef"),
            defenceCoef = coef("defenceCoef"),
            // PHP GameUnitConstBase::_generate() 와 같다 — 기본 info 뒤에 제약 설명을 잇는다.
            info = strs("info").orEmpty() + constraints.map { it.getInfo() },
            initSkillTrigger = strs("initSkillTrigger"),
            phaseSkillTrigger = strs("phaseSkillTrigger"),
            iActionList = strs("iActionList"),
        )
    }

    private fun constraint(o: JsonObject): UnitConstraint {
        fun i(k: String) = o.getValue(k).jsonPrimitive.int
        fun strs(k: String) = o.getValue(k).jsonArray.map { it.jsonPrimitive.content }
        return when (val t = o.getValue("type").jsonPrimitive.content) {
            "Impossible" -> UnitConstraint.Impossible
            "ReqChief" -> UnitConstraint.ReqChief
            "ReqNotChief" -> UnitConstraint.ReqNotChief
            "ReqTech" -> UnitConstraint.ReqTech(i("reqTech"))
            "ReqCities" -> UnitConstraint.ReqCities(strs("reqCities"))
            "ReqRegions" -> UnitConstraint.ReqRegions(strs("reqRegions"))
            "ForbidRegions" -> UnitConstraint.ForbidRegions(strs("forbidRegions"))
            "ReqMinRelYear" -> UnitConstraint.ReqMinRelYear(i("reqMinRelYear"))
            "ReqCitiesWithCityLevel" -> UnitConstraint.ReqCitiesWithCityLevel(i("reqCityLevel"), strs("reqCities"))
            "ReqHighLevelCities" -> UnitConstraint.ReqHighLevelCities(i("reqCityLevel"), i("reqCityCount"))
            "ReqNationAux" -> UnitConstraint.ReqNationAux(
                o.getValue("reqNationAuxKey").jsonPrimitive.content,
                o.getValue("cmp").jsonPrimitive.content,
                o.getValue("value").jsonPrimitive.content.toDouble(),
            )
            else -> error("units.json: 모르는 제약 '$t'")
        }
    }
}

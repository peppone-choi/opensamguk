package opensamguk.common.constants

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `GameUnitConst` 의 하드코딩 병종표를 `data/unitset/che.json` 으로 내보내고, 커밋된 파일이
 * 코틀린과 같은지 검사한다. **런타임 진실은 여전히 코틀린이다** — 이 표는 골든이 덮고 있는
 * 패러티 면이고, 상수 외부화는 CLAUDE.md 의 `M-config` 마일스톤(풀 패러티 close 이후)이다.
 * 그래서 이건 cutover 가 아니라 export + drift gate 다: 코틀린을 고치면 이 검사가 깨지고,
 * `-Dunitset.write=true` 로 다시 돌리면 JSON 이 따라온다.
 *
 * 스키마는 `data/unitset/han.json` 의 crewType 행과 같은 자리를 쓴다(두 병종표를 나란히 읽는다).
 */
class CheUnitSetExportTest {

    @Test
    fun `che 병종표가 커밋된 json 과 같다`() {
        val out = File(repoRoot(), "data/unitset/che.json")
        val json = render()
        if (System.getProperty("unitset.write") == "true") {
            out.writeText(json)
            return
        }
        assertEquals(
            json, out.readText(),
            "GameUnitConst 가 data/unitset/che.json 과 어긋난다. " +
                "다시 내보내라: ./gradlew :common:test --tests '*CheUnitSetExportTest*' -Dunitset.write=true",
        )
    }

    private fun repoRoot(): File {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (!File(d, "settings.gradle.kts").exists() && !File(d, "settings.gradle").exists()) {
            d = d.parentFile ?: error("저장소 루트를 못 찾았다")
        }
        return d
    }

    private fun render(): String {
        val units = GameUnitConst.all().values.map { u ->
            obj(
                "id" to num(u.id),
                "name" to str(u.name),
                "armType" to num(u.armType),
                "armTypeName" to (GameUnitConst.allType()[u.armType]?.let(::str) ?: "null"),
                "attack" to num(u.attack),
                "defence" to num(u.defence),
                "speed" to num(u.speed),
                "avoid" to num(u.avoid),
                "magicCoef" to u.magicCoef.toString(),
                "cost" to num(u.cost),
                "rice" to num(u.rice),
                "reqConstraints" to arr(u.reqConstraints.map(::constraint)),
                "attackCoef" to coef(u.attackCoef),
                "defenceCoef" to coef(u.defenceCoef),
                "initSkillTrigger" to strs(u.initSkillTrigger),
                "phaseSkillTrigger" to strs(u.phaseSkillTrigger),
                "iActionList" to strs(u.iActionList),
                "info" to arr(u.info.map(::str)),
            )
        }
        val meta = obj(
            "id" to str("che"),
            "name" to str("devsam/core che 병종표"),
            "generator" to str("common CheUnitSetExportTest (-Dunitset.write=true)"),
            "note" to str(
                "손으로 고치지 마라. 진실은 common/…/GameUnitConst.kt 이고 이 파일은 그 사본이다. " +
                    "골든이 덮고 있는 패러티 면이라 런타임은 계속 코틀린을 읽는다(M-config 이후 뒤집는다).",
            ),
            "armTypes" to obj(*GameUnitConst.allType().map { (k, v) -> k.toString() to str(v) }.toTypedArray()),
            "counts" to obj("units" to num(units.size)),
        )
        return obj(
            "_meta" to meta,
            "id" to str("che"),
            "defaultCrewTypeId" to num(GameUnitConst.DEFAULT_CREWTYPE),
            "castleCrewTypeId" to num(GameUnitConst.CREWTYPE_CASTLE),
            "crewTypes" to arr(units),
        ) + "\n"
    }

    // ── 최소 JSON 작성기 — 한 줄 한 항목, 들여쓰기 1칸(han.json 과 같은 모양) ──────────────
    private fun str(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
            '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    private fun num(n: Int) = n.toString()
    private fun strs(v: List<String>?) = v?.let { arr(it.map(::str)) } ?: "null"
    private fun coef(m: Map<Int, Double>) = obj(*m.map { (k, v) -> k.toString() to v.toString() }.toTypedArray())

    private fun indent(body: String) = body.lines().joinToString("\n ")

    private fun obj(vararg pairs: Pair<String, String>): String =
        if (pairs.isEmpty()) "{}"
        else pairs.joinToString(",\n ", "{\n ", "\n}") { (k, v) -> "${str(k)}: ${indent(v)}" }

    private fun arr(items: List<String>): String =
        if (items.isEmpty()) "[]" else items.joinToString(",\n ", "[\n ", "\n]") { indent(it) }

    private fun constraint(c: UnitConstraint): String = when (c) {
        is UnitConstraint.Impossible -> obj("type" to str("Impossible"))
        is UnitConstraint.ReqChief -> obj("type" to str("ReqChief"))
        is UnitConstraint.ReqNotChief -> obj("type" to str("ReqNotChief"))
        is UnitConstraint.ReqTech -> obj("type" to str("ReqTech"), "reqTech" to num(c.reqTech))
        is UnitConstraint.ReqCities -> obj("type" to str("ReqCities"), "reqCities" to arr(c.reqCities.map(::str)))
        is UnitConstraint.ReqRegions -> obj("type" to str("ReqRegions"), "reqRegions" to arr(c.reqRegions.map(::str)))
        is UnitConstraint.ForbidRegions ->
            obj("type" to str("ForbidRegions"), "forbidRegions" to arr(c.forbidRegions.map(::str)))
        is UnitConstraint.ReqMinRelYear -> obj("type" to str("ReqMinRelYear"), "reqMinRelYear" to num(c.reqMinRelYear))
        is UnitConstraint.ReqCitiesWithCityLevel -> obj(
            "type" to str("ReqCitiesWithCityLevel"),
            "reqCityLevel" to num(c.reqCityLevel),
            "reqCities" to arr(c.reqCities.map(::str)),
        )
        is UnitConstraint.ReqHighLevelCities -> obj(
            "type" to str("ReqHighLevelCities"),
            "reqCityLevel" to num(c.reqCityLevel),
            "reqCityCount" to num(c.reqCityCount),
        )
        is UnitConstraint.ReqNationAux -> obj(
            "type" to str("ReqNationAux"),
            "reqNationAuxKey" to str(c.reqNationAuxKey),
            "cmp" to str(c.cmp),
            "value" to c.value.toString(),
        )
    }
}

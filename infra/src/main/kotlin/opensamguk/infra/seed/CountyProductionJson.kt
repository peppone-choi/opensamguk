package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Collections
import opensamguk.logic.economy.Resources

/**
 * 縣별 월 산지 생산(철·목재·말). 전·곡은 담지 않는다 — 그쪽은 `CountyIncome` 의 식이 만든다.
 *
 * 산출물은 `tools/map/build_county_resource_production.py` 가 낸다. 철·말의 위치는 사료 산지 원장
 * (`resource-sites-v1`), 목재는 han-tiles 의 삼림 가능 칸 수이고, 단가만 게임 설계다. 단가를 바꾸려면
 * 그 도구의 상수를 고치고 다시 생성한다 — 런타임이 수치를 추정하지 않는다.
 *
 * 파일이 없으면 **빈 표**다. 생산이 0 이 되는 것이 조용히 다른 수치를 만들어 내는 것보다 안전하다.
 */
object CountyProductionJson {
    const val RESOURCE = "campaign/county-production-v1.json"

    private val cached: Map<Int, Resources> by lazy { load(RESOURCE) }

    /** 런타임 표. 없는 縣은 생산 0 이다. */
    fun table(): Map<Int, Resources> = cached

    internal fun load(resource: String): Map<Int, Resources> {
        val json = CountyProductionJson::class.java.classLoader.getResourceAsStream(resource)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return emptyMap()
        return parse(json)
    }

    internal fun parse(json: String): Map<Int, Resources> {
        val root = ObjectMapper().readTree(json)
        require(root.path("schemaVersion").asInt() == 1) { "Unsupported county production schema" }
        val table = LinkedHashMap<Int, Resources>()
        for (row in root.path("counties")) {
            val countyId = row.path("countyId").let {
                require(it.isInt) { "County production row needs an integer countyId" }
                it.asInt()
            }
            require(countyId > 0) { "County production countyId must be positive" }
            val monthly = row.path("monthly")
            require(monthly.isObject) { "County production row needs a monthly object" }
            val fields = monthly.fieldNames().asSequence().toSet()
            require(fields.isNotEmpty() && fields.all { it in ALLOWED }) {
                "County production may only name iron, timber and horses"
            }
            val produced = Resources(
                iron = amount(monthly, "iron"),
                timber = amount(monthly, "timber"),
                horses = amount(monthly, "horses"),
            )
            require(produced != Resources()) { "County production row must produce something" }
            require(table.put(countyId, produced) == null) { "Duplicate county production row" }
        }
        return Collections.unmodifiableMap(table)
    }

    private val ALLOWED = setOf("iron", "timber", "horses")

    private fun amount(monthly: com.fasterxml.jackson.databind.JsonNode, key: String): Long {
        val node = monthly.path(key)
        if (node.isMissingNode || node.isNull) return 0
        require(node.isIntegralNumber) { "County production $key must be an integer" }
        val value = node.asLong()
        require(value >= 0) { "County production $key must not be negative" }
        return value
    }
}

package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticIds

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 縣治 城 하나의 郡·관할. [commanderyId] 는 런타임 지도 `meta.junCh`(한자, 화면의 郡 표시 `meta.jun` 과 같은 행)이고,
 * [jurisdictionId] 는 `han-tiles.json provinceRecords[provinceId].jurisdictionId` 다.
 *
 * 주의: han-tiles `jurisdictionRecords[*].commanderyId` 는 1133 판에서 40 城이 `meta.junCh` 와 다른 郡을 가리킨다
 * (예: 譙 → 汝南郡). 화면이 보여 주는 郡과 방침이 걸리는 郡을 맞추려고 여기서는 `meta.junCh` 를 쓴다.
 */
data class CountyPlace(val countyId: Int, val commanderyId: String, val commanderyName: String?, val jurisdictionId: String?) {
    init { require(countyId > 0 && DomesticIds.commandery(commanderyId)) }
}

class CountyGeography(places: Collection<CountyPlace>,
    private val provinceIdsByJurisdiction: Map<String, Set<String>> = emptyMap()) {
    val byCounty: Map<Int, CountyPlace> = places.sortedBy { it.countyId }.associateBy { it.countyId }.also {
        require(it.size == places.size) { "duplicate county geography" }
    }
    private val countyByJurisdiction: Map<String, Int> = places.filter { it.jurisdictionId != null }
        .groupBy { it.jurisdictionId!! }.filterValues { it.size == 1 }.mapValues { it.value.single().countyId }

    fun commanderyOf(countyId: Int): String? = byCounty[countyId]?.commanderyId
    fun countiesOf(commanderyId: String): List<Int> = byCounty.values.filter { it.commanderyId == commanderyId }.map { it.countyId }
    fun commanderyName(commanderyId: String): String? = byCounty.values.firstOrNull { it.commanderyId == commanderyId }?.commanderyName
    /** Every 省 in this 縣's jurisdiction, including pieces without a 城 seat. */
    fun provincesOfCounty(countyId: Int): Set<String> =
        byCounty[countyId]?.jurisdictionId?.let(provinceIdsByJurisdiction::get).orEmpty()

    /** 관할 id 가 정확히 한 縣治 城에 닿을 때만 그 城. */
    fun countyOfJurisdiction(jurisdictionId: String): Int? = countyByJurisdiction[jurisdictionId]
}

/**
 * 인물 본관 원장(`officer-native-county-v1.json`)에서 향당 보너스에 쓰는 부분만 읽는다. game-api 의
 * `HwihaCampLedgers.nativeCountyOf` 와 같은 규칙이다: `scenarioLink == EXACT`, `method == DIRECT`, 그 이름을 실은 원장 행이
 * 하나뿐(동명이인 제외). 여기서는 **`jurisdictionId` 가 있는 행만** 쓴다 — 관할에 못 붙은 행(沛國 譙 등)은 이름 정규화가
 * 필요해 이번 범위에서 향당 보너스를 주지 않는다(추정하지 않는다).
 */
class NativeCountyLedger internal constructor(private val jurisdictionByName: Map<String, String>) {
    fun jurisdictionOf(scenarioName: String): String? = jurisdictionByName[scenarioName]

    /**
     * 장수의 본관 縣治 城. 사람이 만든 장수(시나리오 표지 `npc_org` 없음, 또는 휘하 정책 출처가 신규 창작)는 이름이 같아도 주지 않는다.
     */
    fun homeCounty(name: String, meta: Map<String, Any?>, geography: CountyGeography): Int? {
        if ("npc_org" !in meta) return null
        val policy = try { PersonPolicyState.read(meta) } catch (_: IllegalArgumentException) { return null }
        if (policy?.statSourceId == CREATED_GENERAL_SOURCE) return null
        return jurisdictionOf(name)?.let(geography::countyOfJurisdiction)
    }

    companion object {
        const val RESOURCE = "hwiha/officer-native-county-v1.json"
        const val CREATED_GENERAL_SOURCE = "opensamguk:created-general"

        fun load(): NativeCountyLedger? =
            NativeCountyLedger::class.java.classLoader.getResource(RESOURCE)?.readText()?.let(::parse)

        fun parse(payload: String): NativeCountyLedger {
            val root = Json.parseToJsonElement(payload).jsonObject
            require(root.getValue("schemaVersion").jsonPrimitive.content == "1") { "unsupported native county schemaVersion" }
            require(root.getValue("ledgerId").jsonPrimitive.content == "officer-native-county-v1") { "unexpected native county ledgerId" }
            val rows = root.getValue("officers").jsonArray.map { it.jsonObject }
            val carriers = rows.flatMap { row -> row.getValue("scenarioNames").jsonArray.map { it.jsonPrimitive.content }.distinct() }
                .groupingBy { it }.eachCount()
            val result = linkedMapOf<String, String>()
            for (row in rows) {
                if (row.text("scenarioLink") != "EXACT" || row.text("method") != "DIRECT") continue
                if (row.text("nativeCounty").isNullOrBlank()) continue
                val jurisdiction = row.text("jurisdictionId")?.takeIf { it.isNotBlank() } ?: continue
                row.getValue("scenarioNames").jsonArray.map { it.jsonPrimitive.content }
                    .filter { carriers[it] == 1 }.forEach { result[it] = jurisdiction }
            }
            return NativeCountyLedger(result)
        }

        private fun kotlinx.serialization.json.JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

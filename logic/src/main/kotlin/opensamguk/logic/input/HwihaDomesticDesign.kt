package opensamguk.logic.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import opensamguk.logic.economy.HwihaResources

/**
 * 휘하 내정 입력의 **잠정** 수치(`data/curated/han/hwiha-domestic-v1.json`, classpath `hwiha/`).
 * 설계 문서가 정하지 않은 효과량·비용·기간은 모두 이 파일 한 곳에만 있고 `status` 가
 * 「PROVISIONAL — 사용자 결정 대기」다 — 코드에 박지 않는다. 파일이 없거나 꼴이 어긋나면 기본값으로 가지 않고 실패한다.
 */
class HwihaDomesticDesign internal constructor(
    val status: String,
    val scaling: Scaling,
    val defaultCountyPolicy: CountyPolicy,
    val countyPolicies: Map<CountyPolicy, PolicyEffect>,
    val maxActiveWorksPerCounty: Int,
    val progressPerPhase: Int,
    val works: Map<DomesticWork, WorkDesign>,
) {
    enum class Indicator { POPULATION, AGRICULTURE, COMMERCE, SECURITY, TRUST, DEFENCE, WALL }
    enum class Stat { LEADERSHIP, STRENGTH, INTELLIGENCE, POLITICS, CHARM }
    enum class Unit { ABSOLUTE, PERMILLE_OF_CURRENT }
    enum class Resource { MONEY, GRAIN, IRON, TIMBER, HORSES }
    enum class Direction { CREDIT, DEBIT }

    data class Scaling(val neutralStat: Int, val permillePerStatPoint: Int, val minimumPermille: Int,
        val emptySeatPermille: Int, val hometownBonusPermille: Int) {
        init { require(permillePerStatPoint >= 0 && minimumPermille >= 0 && emptySeatPermille >= 0 && hometownBonusPermille >= 0) }
    }
    /** [stat] null 이면 배율을 받지 않는 고정량이다(벌점·비용). */
    data class IndicatorEffect(val indicator: Indicator, val amount: Int, val unit: Unit, val stat: Stat?)
    data class ResourceFlow(val resource: Resource, val direction: Direction, val perHouseholdPermille: Int, val stat: Stat?) {
        init { require(perHouseholdPermille >= 0) }
    }
    data class PolicyEffect(val policy: CountyPolicy, val indicators: List<IndicatorEffect>, val resources: List<ResourceFlow>)
    data class CompletionEffect(val indicator: Indicator, val amount: Int)
    data class WorkDesign(val work: DomesticWork, val requiredProgress: Int, val cost: HwihaResources,
        val completion: List<CompletionEffect>) {
        init { require(requiredProgress > 0) }
    }

    companion object {
        const val RESOURCE = "hwiha/hwiha-domestic-v1.json"
        const val PROVISIONAL = "PROVISIONAL — 사용자 결정 대기"

        val CANON: HwihaDomesticDesign by lazy {
            parse(checkNotNull(HwihaDomesticDesign::class.java.classLoader.getResource(RESOURCE)) {
                "hwiha domestic design resource is missing: $RESOURCE"
            }.readText())
        }

        fun parse(payload: String): HwihaDomesticDesign {
            val root = Json.parseToJsonElement(payload).jsonObject
            require(root.int("schemaVersion") == 1) { "unsupported hwiha domestic schemaVersion" }
            require(root.text("ledgerId") == "hwiha-domestic-v1") { "unexpected hwiha domestic ledgerId" }
            val status = root.text("status")
            val scalingNode = root.obj("scaling")
            val scaling = Scaling(scalingNode.int("neutralStat"), scalingNode.int("permillePerStatPoint"),
                scalingNode.int("minimumPermille"), scalingNode.int("emptySeatPermille"), scalingNode.int("hometownBonusPermille"))
            val defaultPolicy = CountyPolicy.valueOf(root.obj("defaultCountyPolicy").text("policy"))
            val policies = root.getValue("countyPolicies").jsonArray.map { raw ->
                val row = raw.jsonObject
                val code = CountyPolicy.valueOf(row.text("code"))
                require(row.text("name") == code.label) { "county policy label mismatch for $code" }
                PolicyEffect(code,
                    row.getValue("indicators").jsonArray.map { item ->
                        val entry = item.jsonObject
                        IndicatorEffect(Indicator.valueOf(entry.text("indicator").uppercase()), entry.int("amount"),
                            Unit.valueOf(entry.text("unit")), entry.stat())
                    },
                    row.getValue("resources").jsonArray.map { item ->
                        val entry = item.jsonObject
                        ResourceFlow(Resource.valueOf(entry.text("resource").uppercase()), Direction.valueOf(entry.text("direction")),
                            entry.int("perHouseholdPermille"), entry.stat())
                    })
            }
            require(policies.map { it.policy } == CountyPolicy.entries) { "every county policy must be designed exactly once, in order" }
            val corps = root.getValue("corpsPolicies").jsonArray.map { CorpsPolicy.valueOf(it.jsonObject.text("code")) }
            require(corps == CorpsPolicy.entries) { "every corps policy must be listed exactly once, in order" }
            val worksNode = root.obj("works")
            val works = worksNode.getValue("kinds").jsonArray.map { raw ->
                val row = raw.jsonObject
                val code = DomesticWork.valueOf(row.text("code"))
                require(row.text("name") == code.label) { "work label mismatch for $code" }
                val cost = row.obj("cost")
                require(cost.keys == setOf("money", "grain", "iron", "timber", "horses")) { "work cost fields for $code" }
                WorkDesign(code, row.int("requiredProgress"),
                    HwihaResources(cost.long("money"), cost.long("grain"), cost.long("iron"), cost.long("timber"), cost.long("horses")),
                    row.getValue("completion").jsonArray.map { item ->
                        val entry = item.jsonObject
                        CompletionEffect(Indicator.valueOf(entry.text("indicator").uppercase()), entry.int("amount"))
                    })
            }
            require(works.map { it.work } == DomesticWork.entries) { "every work must be designed exactly once, in order" }
            return HwihaDomesticDesign(status, scaling, defaultPolicy, policies.associateBy { it.policy },
                worksNode.int("maxActiveWorksPerCounty").also { require(it == 1) { "only one active work per county is supported" } },
                worksNode.int("progressPerPhase").also { require(it > 0) }, works.associateBy { it.work })
        }

        private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
        private fun JsonObject.text(key: String): String = (getValue(key) as JsonPrimitive).also { require(it.isString) { "$key must be text" } }.content
        private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.also { require(!it.isString) }.int
        private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.also { require(!it.isString) }.long
        private fun JsonObject.stat(): Stat? = when (val value = getValue("stat")) {
            JsonNull -> null
            is JsonPrimitive -> Stat.valueOf(value.also { require(it.isString) }.content.uppercase())
            is JsonArray, is JsonObject -> throw IllegalArgumentException("stat must be text or null")
        }
    }
}

package opensamguk.logic.domestic

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
import opensamguk.logic.economy.Resources
import opensamguk.logic.domestic.CorpsPolicy
import opensamguk.logic.domestic.CountyPolicy
import opensamguk.logic.domestic.DomesticWork
import opensamguk.logic.domestic.FieldInput

/**
 * 휘하 내정 입력의 확정 수치(`data/curated/han/domestic-v1.json`, classpath `campaign/`).
 * 설계 문서가 정하지 않은 효과량·비용·기간은 모두 이 파일 한 곳에만 있고 `status` 가
 * 「CONFIRMED」다 — 코드에 박지 않는다. 파일이 없거나 꼴이 어긋나면 기본값으로 가지 않고 실패한다.
 */
class DomesticDesign internal constructor(
    val status: String,
    val scaling: Scaling,
    val defaultCountyPolicy: CountyPolicy,
    val countyPolicies: Map<CountyPolicy, PolicyEffect>,
    val directActionStatus: String,
    val directActions: Map<String, DirectAction>,
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
    data class DirectAction(val inputId: String, val equivalent: String,
        val indicators: List<IndicatorEffect>, val resources: List<ResourceFlow>, val fixedCost: Resources,
        val costStat: Stat?, val experience: Int, val dedication: Int) {
        init { require(experience >= 0 && dedication >= 0) }
    }
    data class CompletionEffect(val indicator: Indicator, val amount: Int)
    data class WorkDesign(val work: DomesticWork, val requiredProgress: Int, val cost: Resources,
        val completion: List<CompletionEffect>) {
        init { require(requiredProgress > 0) }
    }

    companion object {
        const val RESOURCE = "campaign/domestic-v1.json"
        const val CONFIRMED = "CONFIRMED"

        val CANON: DomesticDesign by lazy {
            parse(checkNotNull(DomesticDesign::class.java.classLoader.getResource(RESOURCE)) {
                "hwiha domestic design resource is missing: $RESOURCE"
            }.readText())
        }

        fun parse(payload: String): DomesticDesign {
            val root = Json.parseToJsonElement(payload).jsonObject
            require(root.int("schemaVersion") == 1) { "unsupported hwiha domestic schemaVersion" }
            require(root.text("ledgerId") == "domestic-v1") { "unexpected hwiha domestic ledgerId" }
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
            val directNode = root.obj("directActions")
            val directStatus = directNode.text("status")
            require(directStatus in setOf("PROPOSED", CONFIRMED)) { "unknown direct action status" }
            val directActions = directNode.getValue("rows").jsonArray.map { raw ->
                val row = raw.jsonObject
                val inputId = row.text("inputId")
                val fixed = row.obj("fixedCost")
                require(fixed.keys == setOf("money", "grain", "iron", "timber", "horses")) { "direct action cost fields for $inputId" }
                DirectAction(inputId, row.text("equivalent"),
                    row.getValue("effects").jsonArray.map { item ->
                        val effect = item.jsonObject
                        IndicatorEffect(Indicator.valueOf(effect.text("indicator").uppercase()), effect.int("amount"),
                            Unit.valueOf(effect.text("unit")), effect.stat())
                    },
                    row.getValue("resources").jsonArray.map { item ->
                        val flow = item.jsonObject
                        ResourceFlow(Resource.valueOf(flow.text("resource").uppercase()), Direction.valueOf(flow.text("direction")),
                            flow.int("perHouseholdPermille"), flow.stat())
                    },
                    Resources(fixed.long("money"), fixed.long("grain"), fixed.long("iron"), fixed.long("timber"), fixed.long("horses")),
                    row.stat("costStat"), row.int("experience"), row.int("dedication"))
            }
            require(directActions.map { it.inputId } == FieldInput.INPUT_IDS.toList()) {
                "every direct domestic action must be designed exactly once, in order"
            }
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
                    Resources(cost.long("money"), cost.long("grain"), cost.long("iron"), cost.long("timber"), cost.long("horses")),
                    row.getValue("completion").jsonArray.map { item ->
                        val entry = item.jsonObject
                        CompletionEffect(Indicator.valueOf(entry.text("indicator").uppercase()), entry.int("amount"))
                    })
            }
            require(works.map { it.work } == DomesticWork.entries) { "every work must be designed exactly once, in order" }
            val fortification = works.single { it.work == DomesticWork.FORTIFICATION }
            val phaseProgress = worksNode.int("progressPerPhase").toLong()
            val requiredProgress = fortification.requiredProgress.toLong()
            for ((inputId, indicator) in listOf("action.fortify" to Indicator.DEFENCE,
                "action.repairWall" to Indicator.WALL)) {
                val direct = directActions.single { it.inputId == inputId }
                val completion = fortification.completion.single { it.indicator == indicator }
                require(direct.equivalent == "FORTIFICATION_1_PHASE" &&
                    direct.indicators.single().indicator == indicator && direct.indicators.single().amount.toLong() ==
                    completion.amount * phaseProgress / requiredProgress && direct.fixedCost == Resources(
                    fortification.cost.money * phaseProgress / requiredProgress / 2,
                    fortification.cost.grain * phaseProgress / requiredProgress / 2,
                    fortification.cost.iron * phaseProgress / requiredProgress / 2,
                    fortification.cost.timber * phaseProgress / requiredProgress / 2,
                    fortification.cost.horses * phaseProgress / requiredProgress / 2)) {
                    "$inputId must match half of one fortification phase"
                }
            }
            return DomesticDesign(status, scaling, defaultPolicy, policies.associateBy { it.policy },
                directStatus, directActions.associateBy { it.inputId },
                worksNode.int("maxActiveWorksPerCounty").also { require(it == 1) { "only one active work per county is supported" } },
                worksNode.int("progressPerPhase").also { require(it > 0) }, works.associateBy { it.work })
        }


        private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
        private fun JsonObject.text(key: String): String = (getValue(key) as JsonPrimitive).also { require(it.isString) { "$key must be text" } }.content
        private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.also { require(!it.isString) }.int
        private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.also { require(!it.isString) }.long
        private fun JsonObject.stat(key: String = "stat"): Stat? = when (val value = getValue(key)) {
            JsonNull -> null
            is JsonPrimitive -> Stat.valueOf(value.also { require(it.isString) }.content.uppercase())
            is JsonArray, is JsonObject -> throw IllegalArgumentException("$key must be text or null")
        }
    }
}

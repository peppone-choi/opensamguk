package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticNation
import opensamguk.logic.domestic.DomesticProjection

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources

/** Each legacy stratagem command becomes one named card mode in the shared phase-three executor. */
object HwihaLegacyStratagemInput {
    val INPUT_IDS = HwihaLegacyStratagemCorrespondences.rows.map { it.inputId }.toSet()
    const val LAST_STAND = "stratagem.lastStand"
    const val PROVOKE_RIVALRY = "stratagem.provokeRivalry"
    val OWN_COUNTY_IDS = setOf("stratagem.mobilizePeople", "stratagem.raiseMilitia")
    val ENEMY_COUNTY_IDS = INPUT_IDS - OWN_COUNTY_IDS - LAST_STAND - PROVOKE_RIVALRY

    data class Request(val actorId: Int, val inputId: String, val targetCountyId: Int? = null,
        val firstNationId: Int? = null, val secondNationId: Int? = null)

    fun parse(actorId: Int, inputId: String, raw: String?): Request? {
        if (actorId <= 0 || inputId !in INPUT_IDS || raw == null) return null
        return try {
            val fields = HwihaFlatArguments(raw).read()
            fun id(key: String): Int? = (fields[key] as? JsonPrimitive)?.takeUnless { it.isString }
                ?.intOrNull?.takeIf { it > 0 }
            when (inputId) {
                LAST_STAND -> if (fields.isEmpty()) Request(actorId, inputId) else null
                PROVOKE_RIVALRY -> if (fields.keys == setOf("firstNationId", "secondNationId")) {
                    val first = id("firstNationId") ?: return null
                    val second = id("secondNationId") ?: return null
                    if (first != second) Request(actorId, inputId, firstNationId = first, secondNationId = second) else null
                } else null
                else -> if (fields.keys == setOf("targetCountyId")) id("targetCountyId")
                    ?.let { Request(actorId, inputId, targetCountyId = it) } else null
            }
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: Request): String = buildJsonObject {
        when (request.inputId) {
            LAST_STAND -> Unit
            PROVOKE_RIVALRY -> { put("firstNationId", request.firstNationId!!); put("secondNationId", request.secondNationId!!) }
            else -> put("targetCountyId", request.targetCountyId!!)
        }
    }.toString()
}

/** A named copy is consumed on success and refreshes at the next month; cold reload keeps its used set. */
data class HwihaLegacyStratagemStock(val period: String, val used: Set<String>) {
    init { require(period.matches(Regex("[0-9]{1,6}-[0-9]{1,2}")) && used.all { it in HwihaLegacyStratagemInput.INPUT_IDS }) }
    fun available(inputId: String) = inputId !in used
    fun consume(inputId: String) = copy(used = used + inputId)
    fun toMetaValue(): Map<String, Any?> = mapOf("version" to 1, "period" to period, "used" to used.sorted())
    companion object {
        const val META_KEY = "hwihaLegacyStratagemStock"
        fun forPhase(meta: Map<String, Any?>, now: HwihaPhase): HwihaLegacyStratagemStock {
            val period = "${now.year}-${now.month}"
            val raw = meta[META_KEY] ?: return HwihaLegacyStratagemStock(period, emptySet())
            val row = raw as? Map<*, *> ?: invalid()
            require(row.keys == setOf("version", "period", "used") && row["version"] == 1)
            val storedPeriod = row["period"] as? String ?: invalid()
            val ids = (row["used"] as? List<*>)?.map { it as? String ?: invalid() } ?: invalid()
            require(ids == ids.distinct().sorted())
            val stored = HwihaLegacyStratagemStock(storedPeriod, ids.toSet())
            return if (storedPeriod == period) stored else HwihaLegacyStratagemStock(period, emptySet())
        }
        private fun invalid(): Nothing = throw IllegalArgumentException("invalid legacy stratagem stock")
    }
}

enum class HwihaLegacyStratagemFailure(val message: String) {
    WRONG_RULE_PROFILE("휘하 월드에서만 계책을 사용할 수 있습니다."), INVALID_INPUT("계책의 대상을 확인해 주세요."),
    ACTOR_NOT_FOUND("장수를 찾을 수 없습니다."), BATTLE_PENDING("조우가 끝나야 계책을 사용할 수 있습니다."),
    POSITION_UNAVAILABLE("현재 육상 위치를 확인할 수 없습니다."), SOURCE_UNAVAILABLE("현재 위치에 아군 창고가 없습니다."),
    TARGET_UNAVAILABLE("사거리 안의 대상을 찾을 수 없습니다."), CARD_UNAVAILABLE("이달에 쓸 계책 카드가 없습니다."),
    INSUFFICIENT_STOCK("계책 비용을 낼 자원이 부족합니다."), STOCK_OVERFLOW("창고 자원 한도를 넘습니다."),
    STATE_UNAVAILABLE("계책 상태를 확인할 수 없습니다."), ALREADY_QUEUED("이미 실행 대기 중인 계책이 있습니다.")
}

data class HwihaLegacyStratagemReady(val actor: DomesticPerson, val source: DomesticCounty,
    val target: DomesticCounty?, val firstNation: DomesticNation?, val secondNation: DomesticNation?,
    val warehouse: HwihaCountyWarehouse, val targetWarehouse: HwihaCountyWarehouse?,
    val cardStock: HwihaLegacyStratagemStock, val cost: HwihaResources)
sealed interface HwihaLegacyStratagemAssessment {
    data class Eligible(val ready: HwihaLegacyStratagemReady) : HwihaLegacyStratagemAssessment
    data class Rejected(val reason: HwihaLegacyStratagemFailure) : HwihaLegacyStratagemAssessment
}

object HwihaLegacyStratagemRules {
    fun cost(inputId: String): HwihaResources = when (inputId) {
        "stratagem.fire", "stratagem.flood" -> HwihaResources(timber = 100)
        "stratagem.mobilizePeople", "stratagem.raiseMilitia" -> HwihaResources(grain = 300)
        "stratagem.raid" -> HwihaResources(horses = 100)
        HwihaLegacyStratagemInput.PROVOKE_RIVALRY -> HwihaResources(money = 300)
        else -> HwihaResources(money = 100)
    }

    fun assess(request: HwihaLegacyStratagemInput.Request, state: DomesticProjection): HwihaLegacyStratagemAssessment {
        fun fail(reason: HwihaLegacyStratagemFailure) = HwihaLegacyStratagemAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return fail(HwihaLegacyStratagemFailure.WRONG_RULE_PROFILE)
        if (request.actorId <= 0 || request.inputId !in HwihaLegacyStratagemInput.INPUT_IDS)
            return fail(HwihaLegacyStratagemFailure.INVALID_INPUT)
        val actor = state.person(request.actorId) ?: return fail(HwihaLegacyStratagemFailure.ACTOR_NOT_FOUND)
        if (actor.inBattle) return fail(HwihaLegacyStratagemFailure.BATTLE_PENDING)
        val node = actor.node ?: return fail(HwihaLegacyStratagemFailure.POSITION_UNAVAILABLE)
        val locals = state.counties.filter { it.provinceId == node && it.nationId == actor.nationId }
        if (locals.size != 1) return fail(HwihaLegacyStratagemFailure.SOURCE_UNAVAILABLE)
        val source = locals.single()
        return try {
            val cards = HwihaLegacyStratagemStock.forPhase(actor.meta, state.now)
            if (!cards.available(request.inputId)) return fail(HwihaLegacyStratagemFailure.CARD_UNAVAILABLE)
            val warehouse = HwihaCountyWarehouse.read(source.meta, source.id)
                ?: return fail(HwihaLegacyStratagemFailure.SOURCE_UNAVAILABLE)
            val cost = cost(request.inputId)
            if (warehouse.stock.debit(cost) == null) return fail(HwihaLegacyStratagemFailure.INSUFFICIENT_STOCK)
            val target = request.targetCountyId?.let(state::county)
            val first = request.firstNationId?.let(state::nation)
            val second = request.secondNationId?.let(state::nation)
            when (request.inputId) {
                HwihaLegacyStratagemInput.LAST_STAND -> if (target != null || first != null || second != null)
                    return fail(HwihaLegacyStratagemFailure.INVALID_INPUT)
                HwihaLegacyStratagemInput.PROVOKE_RIVALRY -> {
                    if (first == null || second == null || first.id == second.id ||
                        first.id == actor.nationId || second.id == actor.nationId ||
                        state.diplomacy.none { it.fromNationId == first.id && it.toNationId == second.id } ||
                        state.diplomacy.none { it.fromNationId == second.id && it.toNationId == first.id })
                        return fail(HwihaLegacyStratagemFailure.TARGET_UNAVAILABLE)
                }
                in HwihaLegacyStratagemInput.OWN_COUNTY_IDS -> if (target?.id != source.id)
                    return fail(HwihaLegacyStratagemFailure.TARGET_UNAVAILABLE)
                else -> if (target == null || target.nationId <= 0 || target.nationId == actor.nationId ||
                    target.id !in state.countyAdjacency[source.id].orEmpty())
                    return fail(HwihaLegacyStratagemFailure.TARGET_UNAVAILABLE)
            }
            val targetWarehouse = if (request.inputId in setOf("stratagem.steal", "stratagem.raid")) {
                HwihaCountyWarehouse.read(target!!.meta, target.id)
                    ?: return fail(HwihaLegacyStratagemFailure.TARGET_UNAVAILABLE)
            } else null
            if (request.inputId == "stratagem.steal" && targetWarehouse!!.stock.money < 100 ||
                request.inputId == "stratagem.raid" && targetWarehouse!!.stock.grain < 300)
                return fail(HwihaLegacyStratagemFailure.INSUFFICIENT_STOCK)
            HwihaLegacyStratagemAssessment.Eligible(HwihaLegacyStratagemReady(actor, source, target, first,
                second, warehouse, targetWarehouse, cards, cost))
        } catch (_: IllegalArgumentException) { fail(HwihaLegacyStratagemFailure.STATE_UNAVAILABLE) }
          catch (_: ArithmeticException) { fail(HwihaLegacyStratagemFailure.STOCK_OVERFLOW) }
    }
}
